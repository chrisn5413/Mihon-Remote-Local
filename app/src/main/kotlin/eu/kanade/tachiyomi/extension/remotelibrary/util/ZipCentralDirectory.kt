package eu.kanade.tachiyomi.extension.remotelibrary.util

import android.util.Log
import eu.kanade.tachiyomi.extension.remotelibrary.storage.MangaStorage
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

private const val TAG = "ZipCentralDirectory"

/**
 * Extracts the alphabetically-first image from a CBZ archive by reading its ZIP central
 * directory rather than iterating local file headers via ZipInputStream.
 *
 * Why ZipInputStream fails for these archives:
 * Many CBZ creators set the EXT bit (bit 3 of the general purpose bit flags, 0x0008) in
 * every local file header, even for STORED (uncompressed) entries. This causes the local
 * file header to store compressedSize=0 / uncompressedSize=0 — the real sizes are in the
 * data descriptor that follows the file data. For DEFLATE entries, ZipInputStream uses the
 * inflater's end-of-stream signal to locate the data boundary. For STORED entries there is
 * no such signal, so ZipInputStream reads 0 bytes, then scans the actual file content as if
 * it were the data descriptor, completely corrupting its stream position. nextEntry() then
 * searches for a PK\x03\x04 magic inside file content, finds nothing, and returns null.
 *
 * The central directory (at the end of every ZIP) always has correct compressed sizes and
 * local header offsets regardless of whether the EXT bit is set. By downloading the end of
 * the file, parsing the central directory, and issuing a targeted Range request for exactly
 * the first image entry, we extract the cover reliably.
 *
 * Download pattern: 2 Range requests per cover (tail + image data), both cached after first use.
 */
object ZipCentralDirectory {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    /** Size of the tail download. 64 KB covers the EOCD + central directory for archives
     *  with up to ~1000 entries (each CD entry is ~50–120 bytes). */
    private const val TAIL_SIZE = 65_536L

    /**
     * Downloads the central directory from [fileId] (which must be [fileSize] bytes long),
     * finds the alphabetically-first image entry, downloads its raw data, decompresses if
     * needed, and returns an InputStream over the image bytes.
     *
     * Returns null on any failure (logs the reason).
     */
    suspend fun extractFirstImage(
        fileId: String,
        fileSize: Long,
        storage: MangaStorage,
    ): InputStream? {
        return try {
            // ── Step 1: download the end of the file ────────────────────────────────
            val tailStart = maxOf(0L, fileSize - TAIL_SIZE)
            val tail = storage.downloadFilePartial(fileId, tailStart, fileSize - 1)
            Log.d(TAG, "Tail download: ${tail.size} bytes (fileSize=$fileSize tailStart=$tailStart)")

            // ── Step 2: locate EOCD (End of Central Directory) ──────────────────────
            // Signature: PK\x05\x06 = 0x06054b50 (little-endian: 50 4B 05 06)
            // EOCD is at least 22 bytes from the end; search backward to handle ZIP comments.
            var eocdPos = -1
            for (i in tail.size - 22 downTo 0) {
                if (tail[i]     == 0x50.toByte() &&
                    tail[i + 1] == 0x4B.toByte() &&
                    tail[i + 2] == 0x05.toByte() &&
                    tail[i + 3] == 0x06.toByte()) {
                    eocdPos = i
                    break
                }
            }
            if (eocdPos == -1) {
                Log.w(TAG, "EOCD signature not found in last ${tail.size} bytes")
                return null
            }

            // ── Step 3: parse EOCD fields ────────────────────────────────────────────
            // EOCD layout (22 bytes fixed + comment):
            //   [4] signature  [2] disk#  [2] start disk#  [2] entries on disk
            //   [2] total entries  [4] CD size  [4] CD offset  [2] comment length
            val cdSize   = tail.uint32LE(eocdPos + 12) // size of central directory
            val cdOffset = tail.uint32LE(eocdPos + 16) // CD offset from start of archive
            Log.d(TAG, "EOCD at tailPos=$eocdPos: cdOffset=$cdOffset cdSize=$cdSize")

            // ── Step 4: check the central directory is in our downloaded tail ────────
            val cdInTail = (cdOffset - tailStart).toInt()
            if (cdInTail < 0 || cdInTail >= tail.size) {
                Log.w(TAG, "Central directory not in tail " +
                    "(cdOffset=$cdOffset tailStart=$tailStart — need larger tail download)")
                return null
            }

            // ── Step 5: parse central directory entries ──────────────────────────────
            // Central directory entry layout (46 bytes fixed + variable):
            //   [4] sig=0x02014b50  [2] verMade  [2] verNeeded  [2] flags  [2] method
            //   [2] mtime  [2] mdate  [4] crc  [4] compSize  [4] uncompSize
            //   [2] fnLen  [2] exLen  [2] cmtLen  [2] diskStart  [2] intAttr  [4] extAttr
            //   [4] localHeaderOffset  + filename[fnLen] + extra[exLen] + comment[cmtLen]
            data class CdImage(val name: String, val lhOffset: Long, val compSize: Long, val method: Int)
            val images = mutableListOf<CdImage>()
            var pos = cdInTail
            while (pos + 46 <= eocdPos) {
                // Verify central directory entry signature
                if (tail[pos]     != 0x50.toByte() ||
                    tail[pos + 1] != 0x4B.toByte() ||
                    tail[pos + 2] != 0x01.toByte() ||
                    tail[pos + 3] != 0x02.toByte()) break

                val method   = tail.uint16LE(pos + 10)
                val compSize = tail.uint32LE(pos + 20)
                val fnLen    = tail.uint16LE(pos + 28)
                val exLen    = tail.uint16LE(pos + 30)
                val cmtLen   = tail.uint16LE(pos + 32)
                val lhOffset = tail.uint32LE(pos + 42)
                val name     = String(tail, pos + 46, fnLen, Charsets.UTF_8)

                val ext = name.substringAfterLast('.').lowercase()
                Log.d(TAG, "CD entry: '$name' method=$method compSize=$compSize lhOffset=$lhOffset")
                if (ext in imageExtensions && !name.endsWith('/')) {
                    images.add(CdImage(name, lhOffset, compSize, method))
                }
                pos += 46 + fnLen + exLen + cmtLen
            }

            if (images.isEmpty()) {
                Log.w(TAG, "No image entries found in central directory")
                return null
            }

            // ── Step 6: pick the alphabetically-first image ──────────────────────────
            val best = images.minByOrNull { it.name }!!
            Log.d(TAG, "First image: '${best.name}' method=${best.method} " +
                "compSize=${best.compSize} lhOffset=${best.lhOffset}")

            // ── Step 7: parse local file header to find where the data starts ────────
            // LFH layout (30 bytes fixed + variable):
            //   [4] sig  [2] verNeeded  [2] flags  [2] method  [2] mtime  [2] mdate
            //   [4] crc  [4] compSize(may be 0 if EXT)  [4] uncompSize(may be 0)
            //   [2] fnLen  [2] exLen  + filename[fnLen] + extra[exLen]
            // Note: we use compSize from the central directory (always correct), NOT the
            // local file header (may be 0 when EXT bit is set).
            val lfhFixed = storage.downloadFilePartial(fileId, best.lhOffset, best.lhOffset + 29)
            val lfhFnLen = lfhFixed.uint16LE(26).toLong()
            val lfhExLen = lfhFixed.uint16LE(28).toLong()
            val dataStart = best.lhOffset + 30L + lfhFnLen + lfhExLen
            val dataEnd   = dataStart + best.compSize - 1L
            Log.d(TAG, "Image data: bytes $dataStart–$dataEnd (${best.compSize} bytes)")

            // ── Step 8: download the image data and decompress if needed ─────────────
            val imageBytes = storage.downloadFilePartial(fileId, dataStart, dataEnd)
            when (best.method) {
                0 -> {
                    // STORED — raw uncompressed bytes
                    imageBytes.inputStream()
                }
                8 -> {
                    // DEFLATE — raw deflate stream (no zlib header); Inflater(nowrap=true)
                    InflaterInputStream(imageBytes.inputStream(), Inflater(true)).readBytes().inputStream()
                }
                else -> {
                    Log.w(TAG, "Unsupported compression method: ${best.method}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractFirstImage failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    // ── Little-endian helpers ────────────────────────────────────────────────────────

    /** Reads an unsigned 16-bit little-endian integer from this ByteArray at [offset]. */
    private fun ByteArray.uint16LE(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)

    /** Reads an unsigned 32-bit little-endian integer from this ByteArray at [offset].
     *  Returned as Long to avoid signed overflow for values ≥ 0x80000000. */
    private fun ByteArray.uint32LE(offset: Int): Long =
        (this[offset].toLong()     and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
}
