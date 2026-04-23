package eu.kanade.tachiyomi.extension.remotelibrary.util

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

private const val TAG = "ZipPartialReader"

/**
 * Reads image/ComicInfo entries from a partial ZIP byte array (first N bytes of a CBZ).
 *
 * Uses ZipInputStream because ZipFile requires a complete file (it reads the central
 * directory at EOF, which won't exist in a partial download). ZipInputStream reads the
 * local file headers sequentially from the beginning of the stream.
 *
 * Known limitation: ZipInputStream.closeEntry() can mis-position the stream when the
 * local file header has EXT bit set (bit 3 of flags) and the data descriptor that follows
 * omits the optional 0x08074b50 signature. To work around this, we explicitly drain each
 * entry's content via read() before calling closeEntry() — this forces the inflater to
 * consume all bytes and leaves the stream correctly positioned for the next local header.
 */
object ZipPartialReader {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    /**
     * Drains all remaining bytes from the current ZipInputStream entry without storing them.
     * Call this before closeEntry() for any entry whose content you do not need, to ensure
     * the stream advances correctly to the next local file header.
     */
    private fun ZipInputStream.drain() {
        val buf = ByteArray(8_192)
        @Suppress("ControlFlowWithEmptyBody")
        while (read(buf) != -1) {}
    }

    fun findComicInfo(bytes: ByteArray): InputStream? {
        return try {
            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.equals("ComicInfo.xml", ignoreCase = true)) {
                    val data = zip.readBytes()
                    zip.drain()
                    zip.closeEntry()
                    zip.close()
                    return data.inputStream()
                }
                zip.drain()
                zip.closeEntry()
                entry = zip.nextEntry
            }
            zip.close()
            null
        } catch (e: Exception) {
            Log.w(TAG, "findComicInfo failed: ${e.message}")
            null
        }
    }

    fun findFirstImage(bytes: ByteArray): InputStream? {
        // Collected before any exception — returned even if the loop is aborted by a
        // truncation error at the end of the partial byte array.
        val imageEntries = mutableListOf<Pair<String, ByteArray>>()
        return try {
            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            var entry = zip.nextEntry
            while (entry != null) {
                val ext = entry.name.substringAfterLast('.').lowercase()
                Log.d(TAG, "ZIP entry: '${entry.name}' isDir=${entry.isDirectory} ext=$ext")
                if (!entry.isDirectory && ext in imageExtensions) {
                    try {
                        imageEntries.add(entry.name to zip.readBytes())
                        // readBytes() consumed the entry; drain+close for stream positioning.
                        zip.drain()
                        zip.closeEntry()
                    } catch (e: Exception) {
                        // The image data extends past the end of our partial download.
                        // ZipInputStream is now in an undefined state — stop iterating.
                        // Whatever imageEntries we've collected so far are still usable.
                        Log.w(TAG, "findFirstImage: truncated read for '${entry.name}', stopping: ${e.message}")
                        break
                    }
                } else {
                    zip.drain()
                    zip.closeEntry()
                }
                // Advancing to the next entry can throw if the next local file header
                // falls beyond the end of our partial byte array — that's expected and fine.
                entry = try {
                    zip.nextEntry
                } catch (e: Exception) {
                    Log.d(TAG, "findFirstImage: EOF advancing after '${entry.name}' (partial archive): ${e.message}")
                    break
                }
            }
            zip.close()
            Log.d(TAG, "findFirstImage: found ${imageEntries.size} image entries")
            imageEntries.minByOrNull { it.first }?.second?.inputStream()
        } catch (e: Exception) {
            Log.w(TAG, "findFirstImage failed: ${e.message}")
            // Return any images collected before the unexpected exception.
            if (imageEntries.isNotEmpty()) {
                Log.d(TAG, "findFirstImage: returning best of ${imageEntries.size} pre-exception entries")
                imageEntries.minByOrNull { it.first }?.second?.inputStream()
            } else {
                null
            }
        }
    }
}
