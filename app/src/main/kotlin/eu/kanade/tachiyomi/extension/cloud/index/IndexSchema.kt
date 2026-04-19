package eu.kanade.tachiyomi.extension.cloud.index

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MangaIndex(
    val version: Int = 1,
    @SerialName("library_id") val libraryId: String,
    @SerialName("library_name") val libraryName: String,
    @SerialName("root_folder_id") val rootFolderId: String,
    @SerialName("generated_at") val generatedAt: String,
    val series: List<IndexSeries> = emptyList(),
)

@Serializable
data class IndexSeries(
    val id: String,
    val title: String,
    @SerialName("folder_id") val folderId: String,
    @SerialName("cover_file_id") val coverFileId: String? = null,
    @SerialName("cover_is_archive") val coverIsArchive: Boolean = false,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val tags: List<String> = emptyList(),
    val status: String? = null,
    val chapters: List<IndexChapter> = emptyList(),
)

@Serializable
data class IndexChapter(
    val id: String,
    val number: Float,
    val title: String,
    @SerialName("folder_id") val folderId: String? = null,
    @SerialName("archive_file_id") val archiveFileId: String? = null,
    @SerialName("is_archive") val isArchive: Boolean,
    @SerialName("page_count") val pageCount: Int? = null,
)

fun generateSeriesId(title: String, existingIds: Set<String> = emptySet()): String {
    val base = title
        .lowercase()
        .replace(Regex("[^a-z0-9\\s-]"), "")
        .trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("-+"), "-")
        .take(50)
        .trimEnd('-')

    if (base !in existingIds) return base
    var counter = 2
    while ("$base-$counter" in existingIds) counter++
    return "$base-$counter"
}

fun generateChapterId(seriesId: String, number: Float): String {
    return when {
        number < 0 -> "$seriesId-ch-unknown-${System.nanoTime()}"
        number == number.toLong().toFloat() ->
            "$seriesId-ch${number.toLong().toString().padStart(3, '0')}"
        else -> {
            val parts = number.toString().split(".")
            "$seriesId-ch${parts[0].padStart(3, '0')}-${parts[1]}"
        }
    }
}
