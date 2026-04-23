package eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "DriveClient"

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val parents: List<String> = emptyList(),
    val mimeType: String = "",
    val size: String? = null,
) {
    val isFolder get() = mimeType == "application/vnd.google-apps.folder"
    val isCbz get() = mimeType == "application/zip" ||
        mimeType == "application/x-cbz" ||
        name.endsWith(".cbz", ignoreCase = true) ||
        name.endsWith(".zip", ignoreCase = true)
    val isImage get() = mimeType.startsWith("image/") ||
        name.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE))
    val sizeLong get() = size?.toLongOrNull()
}

@Serializable
private data class FilesListResponse(
    @SerialName("nextPageToken") val nextPageToken: String? = null,
    val files: List<DriveFile> = emptyList(),
)

/**
 * Google Drive REST API v3 client implemented with [HttpURLConnection].
 *
 * Why not OkHttp: the extension uses Mihon's HttpSource so that ChapterLoader recognises
 * it as an http source. To avoid a classloader conflict (ChildFirstPathClassLoader would
 * find the bundled OkHttp before Mihon's, giving two distinct okhttp3.Request class
 * objects that make the JVM verifier reject the class), OkHttp is compile-only. Any
 * actual HTTP work must use java.net.HttpURLConnection, which is always present on Android.
 */
class GoogleDriveClient(
    private val authManager: DriveAuthManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://www.googleapis.com/drive/v3"

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Lists the direct children of [folderId] (one level only). Paginates automatically.
     */
    suspend fun listDirectChildren(folderId: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val all = mutableListOf<DriveFile>()
        var pageToken: String? = null

        do {
            val url = buildString {
                append("$baseUrl/files")
                append("?q=${encode("'$folderId' in parents and trashed=false")}")
                append("&fields=${encode("nextPageToken,files(id,name,parents,mimeType,size)")}")
                append("&pageSize=1000")
                append("&orderBy=name")
                if (pageToken != null) append("&pageToken=$pageToken")
            }
            val responseStr = executeForString(url)
            val page = json.decodeFromString<FilesListResponse>(responseStr)
            all.addAll(page.files)
            pageToken = page.nextPageToken
        } while (pageToken != null)

        all
    }

    /**
     * Fetches all files/folders two levels deep under [folderId]:
     *   Level 1 — direct children of [folderId]  (series folders)
     *   Level 2 — direct children of each Level-1 folder  (chapters / CBZ files)
     */
    suspend fun listAllUnderFolder(folderId: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val all = mutableListOf<DriveFile>()

        val level1 = listDirectChildren(folderId)
        all.addAll(level1)

        for (folder in level1.filter { it.isFolder }) {
            all.addAll(listDirectChildren(folder.id))
        }

        all
    }

    /**
     * Returns a streaming [InputStream] for the file. The caller MUST close the stream
     * when done; closing it also disconnects the underlying [HttpURLConnection].
     */
    suspend fun downloadFile(fileId: String): InputStream = withContext(Dispatchers.IO) {
        val url = "$baseUrl/files/$fileId?alt=media"
        val conn = openConnection(url)
        // Wrap so that close() also disconnects the connection.
        object : FilterInputStream(conn.inputStream) {
            override fun close() {
                super.close()
                conn.disconnect()
            }
        }
    }

    suspend fun downloadFilePartial(fileId: String, startByte: Long, endByte: Long): ByteArray =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/files/$fileId?alt=media"
            val conn = openConnection(url, mapOf("Range" to "bytes=$startByte-$endByte"))
            try {
                conn.inputStream.readBytes()
            } finally {
                conn.disconnect()
            }
        }

    suspend fun getFileMetadata(fileId: String): DriveFile = withContext(Dispatchers.IO) {
        val fields = encode("id,name,parents,mimeType,size")
        val responseStr = executeForString("$baseUrl/files/$fileId?fields=$fields")
        json.decodeFromString<DriveFile>(responseStr)
    }

    // ------------------------------------------------------------------
    // Internal HTTP helpers — HttpURLConnection only
    // ------------------------------------------------------------------

    /**
     * Opens a connection to [url] (with optional extra headers), handles 429 / 401 retry
     * logic, and returns the open [HttpURLConnection] on a 2xx response. The caller is
     * responsible for calling [HttpURLConnection.disconnect].
     */
    private suspend fun openConnection(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        var rateLimitAttempts = 0
        var isAuthRetried = false
        var currentToken = authManager.getValidAccessToken()

        while (true) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                // Large CBZ files can take minutes to download on slow connections.
                readTimeout   = 300_000
                instanceFollowRedirects = true
                setRequestProperty("Authorization", "Bearer $currentToken")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val code = conn.responseCode   // triggers the actual network call
            Log.d(TAG, "HTTP $code for ${url.take(120)}")
            when {
                code in 200..299 -> return conn

                code == 429 -> {
                    conn.disconnect()
                    if (rateLimitAttempts >= 3) throw IOException("Drive API rate limit exceeded after 3 retries")
                    delay(1_000L shl rateLimitAttempts)
                    rateLimitAttempts++
                }

                code == 401 -> {
                    conn.disconnect()
                    if (isAuthRetried) throw AuthException("Authentication failed after token refresh")
                    currentToken = authManager.forceRefreshToken()
                    isAuthRetried = true
                    // Loop again with the fresh token
                }

                code == 403 -> {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    conn.disconnect()
                    throw IOException("Access denied to this file or folder: $body")
                }

                code == 404 -> {
                    conn.disconnect()
                    throw IOException("File or folder not found (404)")
                }

                else -> {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    conn.disconnect()
                    throw IOException("Drive API error $code: $body")
                }
            }
        }
    }

    /** Convenience: opens a connection, reads the full body as a string, and disconnects. */
    private suspend fun executeForString(url: String, extraHeaders: Map<String, String> = emptyMap()): String {
        val conn = openConnection(url, extraHeaders)
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
