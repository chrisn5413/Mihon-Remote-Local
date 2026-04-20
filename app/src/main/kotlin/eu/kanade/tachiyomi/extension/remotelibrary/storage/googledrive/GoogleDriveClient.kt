package eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder

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

class GoogleDriveClient(
    private val authManager: DriveAuthManager,
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://www.googleapis.com/drive/v3"

    suspend fun listAllUnderFolder(folderId: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val all = mutableListOf<DriveFile>()
        var pageToken: String? = null

        do {
            val url = buildString {
                append("$baseUrl/files")
                append("?q=${encode("'$folderId' in ancestors and trashed=false")}")
                append("&fields=${encode("nextPageToken,files(id,name,parents,mimeType,size)")}")
                append("&pageSize=1000")
                append("&orderBy=name")
                if (pageToken != null) append("&pageToken=$pageToken")
            }
            val responseStr = getJson(url)
            val page = json.decodeFromString<FilesListResponse>(responseStr)
            all.addAll(page.files)
            pageToken = page.nextPageToken
        } while (pageToken != null)

        all
    }

    suspend fun downloadFile(fileId: String): InputStream = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken()
        val request = Request.Builder()
            .url("$baseUrl/files/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .build()
        val response = executeWithRetry(request)
        response.body?.byteStream() ?: throw IOException("Empty body for file $fileId")
    }

    suspend fun downloadFilePartial(fileId: String, startByte: Long, endByte: Long): ByteArray =
        withContext(Dispatchers.IO) {
            val token = authManager.getValidAccessToken()
            val request = Request.Builder()
                .url("$baseUrl/files/$fileId?alt=media")
                .header("Authorization", "Bearer $token")
                .header("Range", "bytes=$startByte-$endByte")
                .build()
            val response = executeWithRetry(request)
            response.body?.bytes() ?: throw IOException("Empty body for partial download of $fileId")
        }

    suspend fun getFileMetadata(fileId: String): DriveFile = withContext(Dispatchers.IO) {
        val fields = encode("id,name,parents,mimeType,size")
        val responseStr = getJson("$baseUrl/files/$fileId?fields=$fields")
        json.decodeFromString<DriveFile>(responseStr)
    }

    private suspend fun getJson(url: String): String {
        val token = authManager.getValidAccessToken()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        val response = executeWithRetry(request)
        return response.body?.string() ?: throw IOException("Empty JSON response from $url")
    }

    private suspend fun executeWithRetry(
        request: Request,
        isAuthRetry: Boolean = false,
    ): okhttp3.Response {
        var rateLimitAttempts = 0
        while (true) {
            val response = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
            when (response.code) {
                in 200..299 -> return response
                429 -> {
                    response.close()
                    if (rateLimitAttempts >= 3) throw IOException("Drive API rate limit exceeded after 3 retries")
                    delay(1_000L shl rateLimitAttempts)
                    rateLimitAttempts++
                }
                401 -> {
                    response.close()
                    if (isAuthRetry) throw AuthException("Authentication failed after token refresh")
                    val newToken = authManager.forceRefreshToken()
                    val newRequest = request.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                    return executeWithRetry(newRequest, isAuthRetry = true)
                }
                403 -> {
                    val body = response.body?.string() ?: ""
                    response.close()
                    throw IOException("Access denied to this file or folder: $body")
                }
                404 -> {
                    response.close()
                    throw IOException("File or folder not found (404)")
                }
                else -> {
                    val body = response.body?.string() ?: ""
                    response.close()
                    throw IOException("Drive API error ${response.code}: $body")
                }
            }
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
