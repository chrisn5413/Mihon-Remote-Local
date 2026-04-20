package eu.kanade.tachiyomi.extension.remotelibrary.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexSeries
import eu.kanade.tachiyomi.extension.remotelibrary.storage.MangaStorage
import eu.kanade.tachiyomi.extension.remotelibrary.util.ZipPartialReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val COVER_MAX_WIDTH = 300
private const val COVER_MAX_HEIGHT = 450
private const val COVER_PARTIAL_SIZE = 524_287L

class CoverCache(
    libraryId: String,
    filesDir: File,
    private val storage: MangaStorage? = null,
) {
    private val coversDir = File(filesDir, "mihon-remote/libraries/$libraryId/covers").also { it.mkdirs() }

    fun coverFileFor(seriesId: String): File = File(coversDir, "$seriesId.jpg")

    fun isCached(seriesId: String): Boolean = coverFileFor(seriesId).exists()

    suspend fun getCover(series: IndexSeries): File? = withContext(Dispatchers.IO) {
        val coverFile = coverFileFor(series.id)
        if (coverFile.exists()) return@withContext coverFile

        val coverFileId = series.coverFileId ?: return@withContext null
        val stor = storage ?: return@withContext null

        try {
            val imageStream = if (series.coverIsArchive) {
                val bytes = stor.downloadFilePartial(coverFileId, 0, COVER_PARTIAL_SIZE)
                ZipPartialReader.findFirstImage(bytes) ?: return@withContext null
            } else {
                stor.downloadFile(coverFileId)
            }

            val bitmap = BitmapFactory.decodeStream(imageStream) ?: return@withContext null
            val scaled = scaleBitmap(bitmap)
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, coverFile.outputStream())
            bitmap.recycle()
            if (scaled !== bitmap) scaled.recycle()

            coverFile
        } catch (_: Exception) {
            null
        }
    }

    fun clearAll() {
        coversDir.listFiles()?.forEach { it.delete() }
    }

    fun clearFor(seriesId: String) {
        coverFileFor(seriesId).delete()
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= COVER_MAX_WIDTH && bitmap.height <= COVER_MAX_HEIGHT) return bitmap
        val ratio = minOf(
            COVER_MAX_WIDTH.toFloat() / bitmap.width,
            COVER_MAX_HEIGHT.toFloat() / bitmap.height,
        )
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
