package eu.kanade.tachiyomi.extension.remotelibrary.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * ContentProvider that bridges all persistent storage between the two processes this extension
 * runs in:
 *
 *   - Mihon's process (app.mihon)  — RemoteLibraryFactory, RemoteLibrarySource
 *   - Extension's process           — AddLibraryActivity, ScanProgressActivity, etc.
 *
 * Android SharedPreferences and raw File I/O are per-data-directory (i.e. per-UID).  Both
 * processes call this provider via ContentResolver; the provider handles storage inside the
 * extension's own process using the extension's filesDir.
 *
 * android:exported="true" is required so Mihon's process can reach it.
 *
 * Data stored here:
 *   • Library configs     — JSON list, via call()  (small, < 1 MB)
 *   • Library index       — JSON file, via openFile() / call()  (can be large → file streaming)
 */
class LibraryConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "eu.kanade.tachiyomi.extension.remotelibrary.config"

        // call() methods — library configs
        const val METHOD_GET_ALL   = "getAll"
        const val METHOD_SAVE_ALL  = "saveAll"

        // call() methods — index metadata (small payloads)
        const val METHOD_HAS_INDEX    = "hasIndex"
        const val METHOD_DELETE_INDEX = "deleteIndex"
        const val METHOD_GET_LAST_AT  = "getLastGeneratedAt"

        // call() methods — OAuth token storage (cross-process safe)
        const val METHOD_GET_TOKENS   = "getTokens"
        const val METHOD_SAVE_TOKENS  = "saveTokens"
        const val METHOD_CLEAR_TOKENS = "clearTokens"

        // Bundle keys — general
        const val KEY_JSON   = "json"
        const val KEY_BOOL   = "bool"
        const val KEY_STRING = "string"

        // Bundle keys — token fields
        const val KEY_ACCESS_TOKEN  = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_TOKEN_EXPIRY  = "token_expiry"
        const val KEY_ACCOUNT_EMAIL = "account_email"

        // URI paths
        private const val PATH_INDEX  = "index"   // content://AUTHORITY/index/{libraryId}
        private const val CODE_INDEX  = 1
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "$PATH_INDEX/*", CODE_INDEX)
    }

    @Volatile private var configFile: File? = null

    private fun configFile(): File = configFile ?: synchronized(this) {
        configFile ?: File(context!!.filesDir, "library_configs.json").also { configFile = it }
    }

    private fun indexFile(libraryId: String): File =
        File(context!!.filesDir, "mihon-remote/libraries/$libraryId/index.json")

    private fun tokenFile(): File =
        File(context!!.filesDir, "auth_tokens.json")

    // -------------------------------------------------------------------------
    // call() — small payloads (config list + index metadata)
    // -------------------------------------------------------------------------

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {

            METHOD_GET_ALL -> Bundle().apply {
                synchronized(this@LibraryConfigProvider) {
                    putString(KEY_JSON, configFile().takeIf { it.exists() }?.readText() ?: "[]")
                }
            }

            METHOD_SAVE_ALL -> {
                synchronized(this@LibraryConfigProvider) {
                    configFile().also { it.parentFile?.mkdirs() }.writeText(arg ?: "[]")
                }
                Bundle()
            }

            METHOD_HAS_INDEX -> Bundle().apply {
                val f = indexFile(arg ?: return Bundle())
                putBoolean(KEY_BOOL, f.exists() && f.length() > 0)
            }

            METHOD_DELETE_INDEX -> {
                val libraryId = arg ?: return Bundle()
                val f = indexFile(libraryId)
                f.delete()
                File(f.parent, "index.json.tmp").delete()
                Bundle()
            }

            METHOD_GET_LAST_AT -> Bundle().apply {
                val f = indexFile(arg ?: return Bundle())
                if (!f.exists()) return Bundle()
                try {
                    val text = f.readText()
                    val match = Regex(""""generated_at"\s*:\s*"([^"]+)"""").find(text)
                    putString(KEY_STRING, match?.groupValues?.get(1))
                } catch (_: Exception) { /* no-op */ }
            }

            // ------------------------------------------------------------------
            // Token storage — all token reads/writes go through here so that
            // both the extension's own process (Activities) and Mihon's process
            // (RemoteLibrarySource) share a single token store.
            // ------------------------------------------------------------------

            METHOD_GET_TOKENS -> Bundle().apply {
                synchronized(this@LibraryConfigProvider) {
                    val f = tokenFile()
                    if (!f.exists()) return Bundle()
                    try {
                        val json = org.json.JSONObject(f.readText())
                        putString(KEY_ACCESS_TOKEN,  json.optString(KEY_ACCESS_TOKEN).takeIf { it.isNotEmpty() })
                        putString(KEY_REFRESH_TOKEN, json.optString(KEY_REFRESH_TOKEN).takeIf { it.isNotEmpty() })
                        putLong(KEY_TOKEN_EXPIRY,    json.optLong(KEY_TOKEN_EXPIRY, 0L))
                        putString(KEY_ACCOUNT_EMAIL, json.optString(KEY_ACCOUNT_EMAIL).takeIf { it.isNotEmpty() })
                    } catch (_: Exception) { /* corrupt file — return empty bundle */ }
                }
            }

            METHOD_SAVE_TOKENS -> {
                // arg = JSON string produced by DriveAuthManager
                synchronized(this@LibraryConfigProvider) {
                    try {
                        val incoming = org.json.JSONObject(arg ?: return Bundle())
                        val existing = tokenFile().takeIf { it.exists() }
                            ?.let { runCatching { org.json.JSONObject(it.readText()) }.getOrNull() }
                            ?: org.json.JSONObject()
                        // Merge: only overwrite keys that are present and non-empty in the incoming object
                        for (key in listOf(KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_ACCOUNT_EMAIL)) {
                            val v = incoming.optString(key)
                            if (v.isNotEmpty()) existing.put(key, v)
                        }
                        // expiry always overwrites if present
                        if (incoming.has(KEY_TOKEN_EXPIRY)) existing.put(KEY_TOKEN_EXPIRY, incoming.getLong(KEY_TOKEN_EXPIRY))
                        tokenFile().also { it.parentFile?.mkdirs() }.writeText(existing.toString())
                    } catch (_: Exception) {}
                }
                Bundle()
            }

            METHOD_CLEAR_TOKENS -> {
                synchronized(this@LibraryConfigProvider) { tokenFile().delete() }
                Bundle()
            }

            else -> Bundle()
        }
    }

    // -------------------------------------------------------------------------
    // openFile() — large payloads (index JSON) via ParcelFileDescriptor streaming
    // -------------------------------------------------------------------------

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uriMatcher.match(uri) != CODE_INDEX) return null
        val libraryId = uri.lastPathSegment ?: return null
        val file = indexFile(libraryId)

        return if (mode.startsWith("r")) {
            if (!file.exists()) null
            else ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            file.parentFile?.mkdirs()
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }
    }

    override fun getType(uri: Uri): String? =
        if (uriMatcher.match(uri) == CODE_INDEX) "application/json" else null

    // -------------------------------------------------------------------------
    // Lifecycle + unused stubs
    // -------------------------------------------------------------------------

    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, p: Array<String>?, s: String?, sa: Array<String>?, so: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sa: Array<String>?): Int = 0
}
