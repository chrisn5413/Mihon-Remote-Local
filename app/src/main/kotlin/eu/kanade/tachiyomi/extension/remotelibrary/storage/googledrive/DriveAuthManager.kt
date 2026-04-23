package eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryConfigProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Manages OAuth tokens for Google Drive access.
 *
 * Token storage goes through [LibraryConfigProvider] via ContentResolver so that
 * both the extension's own process (Activities) and Mihon's process (RemoteLibrarySource)
 * share exactly the same token state. EncryptedSharedPreferences cannot be used because
 * Android's per-UID data isolation makes it invisible across process boundaries.
 *
 * HTTP calls use [HttpURLConnection] rather than OkHttp. OkHttp is compile-only in this
 * project (needed only for the HttpSource stub method signatures); bundling it would cause
 * a classloader conflict inside Mihon's ChildFirstPathClassLoader.
 */
class DriveAuthManager(private val context: Context) {

    companion object {
        const val REQUEST_CODE_SIGN_IN = 9001
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

        private val KEY_ACCESS_TOKEN  = LibraryConfigProvider.KEY_ACCESS_TOKEN
        private val KEY_REFRESH_TOKEN = LibraryConfigProvider.KEY_REFRESH_TOKEN
        private val KEY_TOKEN_EXPIRY  = LibraryConfigProvider.KEY_TOKEN_EXPIRY
        private val KEY_ACCOUNT_EMAIL = LibraryConfigProvider.KEY_ACCOUNT_EMAIL
    }

    private val authority = LibraryConfigProvider.AUTHORITY

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun isAuthenticated(): Boolean = getRefreshToken() != null

    fun getAccountEmail(): String? = getTokens().getString(KEY_ACCOUNT_EMAIL)

    fun startAuthFlow(activity: Activity) {
        val clientId = BuildConfig.DRIVE_CLIENT_ID
        require(clientId.isNotEmpty()) {
            "DRIVE_CLIENT_ID is not configured. Add driveClientId to gradle.properties or local.properties."
        }
        android.util.Log.d("DriveAuth", "startAuthFlow: clientId prefix=${clientId.take(12)}... length=${clientId.length}")
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
            val authCode = account.serverAuthCode ?: run {
                android.util.Log.e("DriveAuth", "handleAuthResult: serverAuthCode is null (account=${account.email})")
                return false
            }
            exchangeAuthCode(authCode, account.email)
            true
        } catch (e: ApiException) {
            android.util.Log.e("DriveAuth", "handleAuthResult: ApiException statusCode=${e.statusCode} message=${e.message}")
            false
        } catch (e: Exception) {
            android.util.Log.e("DriveAuth", "handleAuthResult: unexpected error", e)
            false
        }
    }

    suspend fun getValidAccessToken(): String = withContext(Dispatchers.IO) {
        val tokens = getTokens()
        val expiry = tokens.getLong(KEY_TOKEN_EXPIRY, 0L)
        val currentToken = tokens.getString(KEY_ACCESS_TOKEN)
        val refreshToken = tokens.getString(KEY_REFRESH_TOKEN)

        android.util.Log.d(
            "DriveAuth",
            "getValidAccessToken: hasAccessToken=${currentToken != null} " +
                "hasRefreshToken=${refreshToken != null} " +
                "expiryMs=$expiry nowMs=${System.currentTimeMillis()}",
        )

        if (currentToken != null && System.currentTimeMillis() < expiry - 60_000L) {
            return@withContext currentToken
        }

        if (refreshToken == null) {
            android.util.Log.e("DriveAuth", "getValidAccessToken: no refresh token — user must re-sign in")
            throw AuthException("Not authenticated. Please connect your Google Account in extension settings.")
        }

        refreshAccessToken(refreshToken)
    }

    suspend fun forceRefreshToken(): String = withContext(Dispatchers.IO) {
        val refreshToken = getRefreshToken()
            ?: throw AuthException("Not authenticated.")
        refreshAccessToken(refreshToken)
    }

    fun disconnect() {
        try {
            context.contentResolver.call(authority, LibraryConfigProvider.METHOD_CLEAR_TOKENS, null, null)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // Token storage via ContentProvider
    // ------------------------------------------------------------------

    private fun getTokens(): android.os.Bundle {
        return try {
            context.contentResolver.call(authority, LibraryConfigProvider.METHOD_GET_TOKENS, null, null)
                ?: android.os.Bundle()
        } catch (_: Exception) {
            android.os.Bundle()
        }
    }

    private fun getRefreshToken(): String? = getTokens().getString(KEY_REFRESH_TOKEN)

    private fun saveTokens(accessToken: String, refreshToken: String?, expiresIn: Long, email: String?) {
        try {
            val json = JSONObject().apply {
                put(KEY_ACCESS_TOKEN, accessToken)
                if (!refreshToken.isNullOrEmpty()) put(KEY_REFRESH_TOKEN, refreshToken)
                put(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + expiresIn * 1_000L)
                if (email != null) put(KEY_ACCOUNT_EMAIL, email)
            }
            context.contentResolver.call(
                authority,
                LibraryConfigProvider.METHOD_SAVE_TOKENS,
                json.toString(),
                null,
            )
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // OAuth token exchange / refresh — HttpURLConnection (no OkHttp)
    // ------------------------------------------------------------------

    private suspend fun exchangeAuthCode(authCode: String, email: String?) = withContext(Dispatchers.IO) {
        val formBody = buildFormBody(
            "code"          to authCode,
            "client_id"     to BuildConfig.DRIVE_CLIENT_ID,
            "client_secret" to BuildConfig.DRIVE_CLIENT_SECRET,
            "redirect_uri"  to "",
            "grant_type"    to "authorization_code",
        )

        val (code, body) = postForm(TOKEN_URL, formBody)
        if (code !in 200..299) throw AuthException("Token exchange failed ($code): $body")

        val json = JSONObject(body)
        saveTokens(
            accessToken  = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
            expiresIn    = json.getLong("expires_in"),
            email        = email,
        )
    }

    private suspend fun refreshAccessToken(refreshToken: String): String = withContext(Dispatchers.IO) {
        val formBody = buildFormBody(
            "refresh_token" to refreshToken,
            "client_id"     to BuildConfig.DRIVE_CLIENT_ID,
            "client_secret" to BuildConfig.DRIVE_CLIENT_SECRET,
            "grant_type"    to "refresh_token",
        )

        val (code, body) = postForm(TOKEN_URL, formBody)

        if (code == 400 || code == 401) {
            disconnect()
            throw AuthException("Session expired. Please reconnect your Google Account.")
        }
        if (code !in 200..299) throw AuthException("Token refresh failed ($code)")

        val json = JSONObject(body)
        val newToken = json.getString("access_token")
        saveTokens(
            accessToken  = newToken,
            refreshToken = null,
            expiresIn    = json.getLong("expires_in"),
            email        = null,
        )
        newToken
    }

    // ------------------------------------------------------------------
    // Low-level helpers
    // ------------------------------------------------------------------

    /**
     * POSTs [formBody] (URL-encoded) to [url]. Returns (HTTP status code, response body string).
     * Uses [HttpURLConnection] — no OkHttp dependency.
     */
    private fun postForm(url: String, formBody: String): Pair<Int, String> {
        val bytes = formBody.toByteArray(Charsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout    = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Content-Length", bytes.size.toString())
        }
        return try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            code to body
        } finally {
            conn.disconnect()
        }
    }

    /** Builds a URL-encoded form body string from [pairs]. */
    private fun buildFormBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
}
