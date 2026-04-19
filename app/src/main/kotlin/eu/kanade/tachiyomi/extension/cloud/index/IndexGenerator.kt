package eu.kanade.tachiyomi.extension.cloud.index

import android.util.Log
import eu.kanade.tachiyomi.extension.cloud.library.LibraryConfig
import eu.kanade.tachiyomi.extension.cloud.storage.MangaStorage
import eu.kanade.tachiyomi.extension.cloud.storage.googledrive.DriveFile
import eu.kanade.tachiyomi.extension.cloud.storage.googledrive.GoogleDriveClient
import eu.kanade.tachiyomi.extension.cloud.util.ChapterNumberParser
import eu.kanade.tachiyomi.extension.cloud.util.ComicInfoParser
import eu.kanade.tachiyomi.extension.cloud.util.ZipPartialReader
import java.time.Instant

private const val TAG = "IndexGenerator"
private const val COMIC_INFO_MAX_SIZE_BYTES = 10L * 1024 * 1024 // 10 MB
private const val COMIC_INFO_PARTIAL_SIZE = 524_287L // 512 KB - 1

class IndexGenerator(
    private val driveClient: GoogleDriveClient,
    private val storage: MangaStorage,
) {
    suspend fun generate(
        config: LibraryConfig,
        onProgress: suspend (current: Int, total: Int, seriesName: String) -> Unit,
    ): MangaIndex {
        // Step 1: Bulk fetch of all files under root
        val allFiles = driveClient.listAllUnderFolder(config.rootFolderId)

        // Step 2: Build in-memory parent → children map
        val childrenByParent = mutableMapOf<String, MutableList<DriveFile>>()
        for (file in allFiles) {
            for (parentId in file.parents) {
                childrenByParent.getOrPut(parentId) { mutableListOf() }.add(file)
            }
        }

        // Step 3: Identify series (direct folder children of root)
        val seriesFolders = childrenByParent[config.rootFolderId]
            ?.filter { it.isFolder }
            ?.sortedBy { it.name }
            ?: emptyList()

        val total = seriesFolders.size
        val seriesList = mutableListOf<IndexSeries>()
        val usedSeriesIds = mutableSetOf<String>()

        // Step 4: Process each series from in-memory data
        for ((index, seriesFolder) in seriesFolders.withIndex()) {
            onProgress(index + 1, total, seriesFolder.name)
            try {
                val series = processSeries(
                    seriesFolder = seriesFolder,
                    childrenByParent = childrenByParent,
                    usedIds = usedSeriesIds,
                )
                if (series != null) {
                    seriesList.add(series)
                    usedSeriesIds.add(series.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process series '${seriesFolder.name}': ${e.message}")
            }
        }

        return MangaIndex(
            libraryId = config.id,
            libraryName = config.displayName,
            rootFolderId = config.rootFolderId,
            generatedAt = Instant.now().toString(),
            series = seriesList,
        )
    }

    private suspend fun processSeries(
        seriesFolder: DriveFile,
        childrenByParent: Map<String, List<DriveFile>>,
        usedIds: Set<String>,
    ): IndexSeries? {
        val seriesChildren = childrenByParent[seriesFolder.id] ?: emptyList()
        val chapterFolders = seriesChildren.filter { it.isFolder }.sortedBy { it.name }
        val cbzFiles = seriesChildren.filter { it.isCbz }.sortedBy { it.name }

        // All chapters in order: interleave folders and CBZ files sorted by name
        val allChapterItems = (chapterFolders + cbzFiles).sortedBy { it.name }
        if (allChapterItems.isEmpty()) return null

        val seriesId = generateSeriesId(seriesFolder.name, usedIds)
        val usedChapterIds = mutableSetOf<String>()

        // Find cover
        val firstChapter = allChapterItems.first()
        val (coverFileId, coverIsArchive) = findCoverForChapter(firstChapter, childrenByParent)

        // Optionally parse ComicInfo from first CBZ chapter
        val comicInfo = tryParseComicInfo(firstChapter)

        // Build chapters
        val chapters = allChapterItems.mapNotNull { item ->
            buildChapter(item, seriesId, childrenByParent, usedChapterIds)
        }

        return IndexSeries(
            id = seriesId,
            title = seriesFolder.name,
            folderId = seriesFolder.id,
            coverFileId = coverFileId,
            coverIsArchive = coverIsArchive,
            description = comicInfo?.summary,
            author = comicInfo?.writer,
            artist = comicInfo?.penciller,
            tags = comicInfo?.genre?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList(),
            status = comicInfo?.status,
            chapters = chapters,
        )
    }

    private fun findCoverForChapter(
        chapter: DriveFile,
        childrenByParent: Map<String, List<DriveFile>>,
    ): Pair<String?, Boolean> {
        return if (chapter.isFolder) {
            val firstImage = childrenByParent[chapter.id]
                ?.filter { it.isImage }
                ?.minByOrNull { it.name }
            firstImage?.id to false
        } else if (chapter.isCbz) {
            chapter.id to true
        } else {
            null to false
        }
    }

    private suspend fun tryParseComicInfo(chapter: DriveFile): eu.kanade.tachiyomi.extension.cloud.util.ComicInfo? {
        if (!chapter.isCbz) return null
        val size = chapter.sizeLong ?: return null
        if (size > COMIC_INFO_MAX_SIZE_BYTES) return null

        return try {
            val bytes = storage.downloadFilePartial(chapter.id, 0, COMIC_INFO_PARTIAL_SIZE)
            val comicInfoStream = ZipPartialReader.findComicInfo(bytes) ?: return null
            ComicInfoParser.parse(comicInfoStream)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildChapter(
        item: DriveFile,
        seriesId: String,
        childrenByParent: Map<String, List<DriveFile>>,
        usedIds: MutableSet<String>,
    ): IndexChapter? {
        val number = ChapterNumberParser.parse(item.name)
        var chapterId = generateChapterId(seriesId, number)

        // Handle ID collision (rare but possible with unknown chapters)
        if (chapterId in usedIds) {
            chapterId = "$chapterId-${item.id.take(6)}"
        }
        usedIds.add(chapterId)

        return if (item.isFolder) {
            val pageCount = childrenByParent[item.id]?.count { it.isImage }
            IndexChapter(
                id = chapterId,
                number = number,
                title = item.name,
                folderId = item.id,
                archiveFileId = null,
                isArchive = false,
                pageCount = pageCount,
            )
        } else if (item.isCbz) {
            IndexChapter(
                id = chapterId,
                number = number,
                title = item.name,
                folderId = null,
                archiveFileId = item.id,
                isArchive = true,
                pageCount = null,
            )
        } else {
            null
        }
    }
}
