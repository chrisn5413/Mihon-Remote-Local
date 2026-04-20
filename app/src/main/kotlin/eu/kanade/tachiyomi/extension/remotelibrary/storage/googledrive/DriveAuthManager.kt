package eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import eu.kanade.tachiyomi.extension.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

class DriveAuthManager(private val context: Context) {

    companion object {
        const val REQUEST_CODE_SIGN_IN = 9001
        private const val PREFS_NAME = "drive_auth_secure"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val httpClient = OkHttpClient()

    fun isAuthenticated(): Boolean = prefs.getString(KEY_REFRESH_TOKEN, null) != null

    fun getAccountEmail(): String? = prefs.getString(KEY_ACCOUNT_EMAIL, null)

    fun startAuthFlow(activity: Activity) {
        val clientId = BuildConfig.DRIVE_CLIENT_ID
        require(clientId.isNotEmpty()) {
            "DRIVE_CLIENT_ID is not configured. Add driveClientId to gradle.properties or local.properties."
        }
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .requestServerAuthCode(clientId, true)
            .build()
        val client = GoogleSignIn.getClient(activity, options)
        activity.startActivityForResult(client.signInIntent, REQUEST_CODE_SIGN_IN)
    }

    suspend fun handleAuthResult(data: Intent?): Boolean {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val authCode = account.serverAuthCode ?: return false
            exchangeAuthCode(authCode, account.email)
            true
        } catch (_: ApiException) {
            false
        }
    }

    suspend fun getValidAccessToken(): String = withContext(Dispatchers.IO) {
        val expiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        val currentToken = prefs.getString(KEY_ACCESS_TOKEN, null)

        // Return cached token if it won't expire within the next 60 seconds
        if (currentToken != null && System.currentTimeMillis() < expiry - 60_000L) {
            return@withContext currentToken
        }

        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
            ?: throw AuthException("Not authenticated. Please connect your Google Account in extension settings.")

        refreshAccessToken(refreshToken)
    }

    suspend fun forceRefreshToken(): String = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
            ?: throw AuthException("Not authenticated.")
        refreshAccessToken(refreshToken)
    }

    fun disconnect() {
        prefs.edit().clear().apply()
    }

    private suspend fun exchangeAuthCode(authCode: String, email: String?) = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("code", authCode)
            .add("client_id", BuildConfig.DRIVE_CLIENT_ID)
            .add("client_secret", BuildConfig.DRIVE_CLIENT_SECRET)
            .add("redirect_uri", "")
            .add("grant_type", "authorization_code")
            .build()

        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty token response")

        if (!response.isSuccessful) throw AuthException("Token exchange failed (${response.code})")

        val json = JSONObject(responseBody)
        storeTokens(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
            expiresIn = json.getLong("expires_in"),
            email = email,
        )
    }

    private suspend fun refreshAccessToken(refreshToken: String): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("refresh_token", refreshToken)
            .add("client_id", BuildConfig.DRIVE_CLIENT_ID)
            .add("client_secret", BuildConfig.DRIVE_CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty refresh response")

        when (response.code) {
            400, 401 -> {
                disconnect()
                throw AuthException("Session expired. Please reconnect your Google Account.")
            }
        }
        if (!response.isSuccessful) throw AuthException("Token refresh failed (${response.code})")

        val json = JSONObject(responseBody)
        val newToken = json.getString("access_token")
        storeTokens(
            accessToken = newToken,
            refreshToken = null,
            expiresIn = json.getLong("expires_in"),
            email = null,
        )
        newToken
    }

    private fun storeTokens(accessToken: String, refreshToken: String?, expiresIn: Long, email: String?) {
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (!refreshToken.isNullOrEmpty()) putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + expiresIn * 1_000L)
            if (email != null) putString(KEY_ACCOUNT_EMAIL, email)
        }.apply()
    }
}
