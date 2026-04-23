package eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive

import eu.kanade.tachiyomi.extension.remotelibrary.storage.ConnectionResult
import eu.kanade.tachiyomi.extension.remotelibrary.storage.MangaStorage
import eu.kanade.tachiyomi.extension.remotelibrary.storage.StorageItem
import java.io.InputStream

class GoogleDriveStorage(
    private val client: GoogleDriveClient,
    private val rootFolderId: String,
) : MangaStorage {

    override suspend fun listFolder(folderId: String): List<StorageItem> {
        // Use the direct-children API — no filtering needed, no wasted recursive fetch.
        return client.listDirectChildren(folderId).map { it.toStorageItem() }
    }

    override suspend fun downloadFile(fileId: String): InputStream {
        return client.downloadFile(fileId)
    }

    override suspend fun downloadFilePartial(fileId: String, startByte: Long, endByte: Long): ByteArray {
        return client.downloadFilePartial(fileId, startByte, endByte)
    }

    override suspend fun getFileMetadata(fileId: String): StorageItem {
        return client.getFileMetadata(fileId).toStorageItem()
    }

    override suspend fun testConnection(): ConnectionResult {
        return try {
            val rootMeta = client.getFileMetadata(rootFolderId)
            val children = listFolder(rootFolderId)
            val seriesCount = children.count { it.isFolder }
            ConnectionResult.Success(rootFolderName = rootMeta.name, seriesCount = seriesCount)
        } catch (e: AuthException) {
            ConnectionResult.Failure(reason = e.message ?: "Authentication error", isAuthError = true)
        } catch (e: Exception) {
            ConnectionResult.Failure(reason = e.message ?: "Connection failed")
        }
    }

    override suspend fun resolvePath(path: String): StorageItem? = null

    private fun DriveFile.toStorageItem() = StorageItem(
        id = id,
        name = name,
        isFolder = isFolder,
        sizeBytes = sizeLong,
        mimeType = mimeType.ifEmpty { null },
    )
}
