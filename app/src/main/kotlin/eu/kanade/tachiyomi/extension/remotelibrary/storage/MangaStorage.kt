package eu.kanade.tachiyomi.extension.remotelibrary.storage

import java.io.InputStream

interface MangaStorage {
    suspend fun listFolder(folderId: String): List<StorageItem>
    suspend fun downloadFile(fileId: String): InputStream
    suspend fun downloadFilePartial(fileId: String, startByte: Long, endByte: Long): ByteArray
    suspend fun getFileMetadata(fileId: String): StorageItem
    suspend fun testConnection(): ConnectionResult
    suspend fun resolvePath(path: String): StorageItem?
}

data class StorageItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long?,
    val mimeType: String?,
)

sealed class ConnectionResult {
    data class Success(val rootFolderName: String, val seriesCount: Int) : ConnectionResult()
    data class Failure(val reason: String, val isAuthError: Boolean = false) : ConnectionResult()
}

sealed class StorageResult<T> {
    data class Success<T>(val value: T) : StorageResult<T>()
    data class Error<T>(
        val message: String,
        val isAuthError: Boolean = false,
        val isNetworkError: Boolean = false,
        val cause: Throwable? = null,
    ) : StorageResult<T>()
}
