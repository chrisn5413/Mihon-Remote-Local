package eu.kanade.tachiyomi.extension.remotelibrary.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexSeries
import eu.kanade.tachiyomi.extension.remotelibrary.storage.MangaStorage
import eu.kanade.tachiyomi.extension.remotelibrary.util.ZipPartialReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "CoverCache"

private const val COVER_MAX_WIDTH = 300
private const val COVER_MAX_HEIGHT = 450
// Manga page images are typically 1–3 MB each. 4 MB gives us a good chance of containing
// the first complete image entry in a CBZ archive's sequential ZIP stream.
private const val COVER_PARTIAL_SIZE = 4_194_304L

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
        if (coverFile.exists()) {
            Log.d(TAG, "getCover[${series.id}]: cache hit → ${coverFile.absolutePath}")
            return@withContext coverFile
        }

        val coverFileId = series.coverFileId
        if (coverFileId == null) {
            Log.w(TAG, "getCover[${series.id}]: no coverFileId in index, skipping")
            return@withContext null
        }
        val stor = storage
        if (stor == null) {
            Log.w(TAG, "getCover[${series.id}]: storage is null, skipping")
            return@withContext null
        }

        Log.d(TAG, "getCover[${series.id}]: fetching coverFileId=$coverFileId coverIsArchive=${series.coverIsArchive}")
        try {
            val imageStream = if (series.coverIsArchive) {
                Log.d(TAG, "getCover[${series.id}]: downloading partial CBZ (${COVER_PARTIAL_SIZE} bytes)")
                val bytes = stor.downloadFilePartial(coverFileId, 0, COVER_PARTIAL_SIZE)
                Log.d(TAG, "getCover[${series.id}]: partial download got ${bytes.size} bytes, extracting first image")
                val stream = ZipPartialReader.findFirstImage(bytes)
                if (stream == null) {
                    Log.w(TAG, "getCover[${series.id}]: findFirstImage returned null — no image found in first ${bytes.size} bytes of CBZ")
                    return@withContext null
                }
                stream
            } else {
                Log.d(TAG, "getCover[${series.id}]: downloading direct image file")
                stor.downloadFile(coverFileId)
            }

            Log.d(TAG, "getCover[${series.id}]: decoding bitmap")
            val bitmap = BitmapFactory.decodeStream(imageStream)
            if (bitmap == null) {
                Log.w(TAG, "getCover[${series.id}]: BitmapFactory.decodeStream returned null")
                return@withContext null
            }
            Log.d(TAG, "getCover[${series.id}]: decoded ${bitmap.width}×${bitmap.height}, scaling and saving")
            val scaled = scaleBitmap(bitmap)
            coverFile.parentFile?.mkdirs()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, coverFile.outputStream())
            bitmap.recycle()
            if (scaled !== bitmap) scaled.recycle()

            Log.d(TAG, "getCover[${series.id}]: saved to ${coverFile.absolutePath} (${coverFile.length()} bytes)")
            coverFile
        } catch (e: Exception) {
            Log.e(TAG, "getCover[${series.id}]: FAILED — ${e.javaClass.simpleName}: ${e.message}", e)
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
