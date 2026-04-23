package eu.kanade.tachiyomi.extension.remotelibrary.cache

import android.util.Log
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexChapter
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexSeries
import eu.kanade.tachiyomi.extension.remotelibrary.storage.MangaStorage
import eu.kanade.tachiyomi.extension.remotelibrary.storage.StorageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

private const val TAG = "ReadingCache"
private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")

class ReadingCache(
    baseDir: File,
    private val storage: MangaStorage,
    private val maxSizeMb: Int = 500,
) {
    private val readingRoot = File(baseDir, "mihon-remote/reading").also { it.mkdirs() }
    private val accessLogFile = File(baseDir, "mihon-remote/reading_access.json")
    private val json = Json { ignoreUnknownKeys = true }

    // In-memory cache of folder-chapter page lists (avoids repeated listFolder calls)
    private val folderPageCache = mutableMapOf<String, List<StorageItem>>()

    fun chapterDir(seriesId: String, chapterId: String) =
        File(readingRoot, "$seriesId/$chapterId")

    fun isComplete(seriesId: String, chapterId: String): Boolean {
        val dir = chapterDir(seriesId, chapterId)
        return dir.exists() && (dir.listFiles()?.any { it.extension.lowercase() in imageExtensions } == true)
    }

    suspend fun getPages(
        series: IndexSeries,
        chapter: IndexChapter,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): List<File> = withContext(Dispatchers.IO) {
        val dir = chapterDir(series.id, chapter.id)

        if (isComplete(series.id, chapter.id)) {
            recordAccess(series.id, chapter.id)
            return@withContext sortedImageFiles(dir)
        }

        if (!dir.exists() && !dir.mkdirs()) {
            error("Failed to create chapter cache directory: ${dir.absolutePath}")
        }
        Log.d(TAG, "Chapter dir ready: ${dir.absolutePath} exists=${dir.exists()}")

        try {
            if (chapter.isArchive) {
                downloadCbzChapter(chapter, dir, onProgress)
            } else {
                downloadFolderChapter(series, chapter, dir, onProgress)
            }
            recordAccess(series.id, chapter.id)
            evictIfNeeded()
            sortedImageFiles(dir)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    private suspend fun downloadFolderChapter(
        series: IndexSeries,
        chapter: IndexChapter,
        dir: File,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val folderId = chapter.folderId ?: error("Folder chapter missing folderId")
        val cacheKey = "${series.id}::${chapter.id}"

        val pages = folderPageCache.getOrPut(cacheKey) {
            val all = storage.listFolder(folderId)
            Log.d(TAG, "listFolder($folderId): ${all.size} items total")
            all.filter { item ->
                val isImg = item.mimeType?.startsWith("image/") == true ||
                    item.name.substringAfterLast('.').lowercase() in imageExtensions
                if (!isImg) Log.d(TAG, "  skipping non-image: '${item.name}' mime=${item.mimeType}")
                isImg
            }.also { Log.d(TAG, "  → ${it.size} image files to download") }
        }

        val total = pages.size.toLong()
        var downloaded = 0L

        coroutineScope {
            pages.chunked(4).forEach { batch ->
                batch.map { page ->
                    async(Dispatchers.IO) {
                        val destFile = File(dir, page.name)
                        if (!destFile.exists()) {
                            // Safety net: ensure the directory still exists at write time.
                            // mkdirs() is a no-op if the dir already exists.
                            destFile.parentFile?.mkdirs()
                            try {
                                storage.downloadFile(page.id).use { stream ->
                                    destFile.outputStream().use { out -> stream.copyTo(out) }
                                }
                                Log.d(TAG, "Downloaded page '${page.name}' → ${destFile.length()} bytes")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to download page '${page.name}' (id=${page.id}): ${e.message}")
                                throw e
                            }
                        } else {
                            Log.d(TAG, "Page '${page.name}' already cached (${destFile.length()} bytes)")
                        }
                        synchronized(this@ReadingCache) {
                            downloaded++
                            onProgress?.invoke(downloaded, total)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun downloadCbzChapter(
        chapter: IndexChapter,
        dir: File,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val archiveId = chapter.archiveFileId ?: error("Archive chapter missing archiveFileId")
        val tempCbz = File(dir.parent, "${chapter.id}.cbz.tmp")
        tempCbz.parentFile?.mkdirs()

        try {
            var downloadedBytes = 0L
            storage.downloadFile(archiveId).use { inputStream ->
                tempCbz.outputStream().use { out ->
                    val buffer = ByteArray(65_536)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress?.invoke(downloadedBytes, -1L)
                    }
                }
            }
            Log.d(TAG, "CBZ downloaded: $downloadedBytes bytes → ${tempCbz.absolutePath}")
            if (downloadedBytes == 0L) error("CBZ download produced 0 bytes for archiveId=${chapter.archiveFileId}")

            // Use ZipFile (reads the central directory at EOF) rather than ZipInputStream
            // (reads local headers sequentially). ZipInputStream can stop early if it
            // cannot skip an unread entry's data; ZipFile enumerates all entries robustly.
            var imageCount = 0
            var skippedCount = 0
            ZipFile(tempCbz).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val ext = entry.name.substringAfterLast('.').lowercase()
                    Log.d(TAG, "ZIP entry: '${entry.name}' isDir=${entry.isDirectory} ext=$ext")
                    if (!entry.isDirectory && ext in imageExtensions) {
                        val destFile = File(dir, entry.name.substringAfterLast('/'))
                        destFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            destFile.outputStream().use { out -> input.copyTo(out) }
                        }
                        imageCount++
                    } else {
                        skippedCount++
                    }
                }
            }
            Log.d(TAG, "CBZ extraction complete: $imageCount images, $skippedCount skipped → ${dir.absolutePath}")
        } finally {
            tempCbz.delete()
        }
    }

    fun clearAll() {
        readingRoot.deleteRecursively()
        readingRoot.mkdirs()
        accessLogFile.delete()
        folderPageCache.clear()
    }

    fun evictIfNeeded() {
        val totalMb = readingRoot.totalSize() / (1024 * 1024)
        if (totalMb <= maxSizeMb) return

        val accessLog = loadAccessLog().toMutableMap()
        val chapterDirs = readingRoot.walkTopDown()
            .filter { it.isDirectory && it.parentFile?.parentFile == readingRoot }
            .sortedBy { accessLog[it.absolutePath] ?: 0L }
            .toList()

        var currentMb = totalMb
        for (dir in chapterDirs) {
            if (currentMb <= maxSizeMb) break
            val sizeMb = dir.totalSize() / (1024 * 1024)
            dir.deleteRecursively()
            accessLog.remove(dir.absolutePath)
            currentMb -= sizeMb
        }
        saveAccessLog(accessLog)
    }

    private fun recordAccess(seriesId: String, chapterId: String) {
        val dir = chapterDir(seriesId, chapterId)
        val log = loadAccessLog().toMutableMap()
        log[dir.absolutePath] = System.currentTimeMillis()
        saveAccessLog(log)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadAccessLog(): Map<String, Long> {
        if (!accessLogFile.exists()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, Long>>(accessLogFile.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveAccessLog(log: Map<String, Long>) {
        try {
            accessLogFile.writeText(json.encodeToString(log))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save access log: ${e.message}")
        }
    }

    private fun sortedImageFiles(dir: File): List<File> {
        return dir.listFiles()
            ?.filter { it.extension.lowercase() in imageExtensions }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun File.totalSize(): Long {
        return walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
