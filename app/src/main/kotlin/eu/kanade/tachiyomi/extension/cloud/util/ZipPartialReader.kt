package eu.kanade.tachiyomi.extension.cloud.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object ZipPartialReader {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun findComicInfo(bytes: ByteArray): InputStream? {
        return try {
            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.equals("ComicInfo.xml", ignoreCase = true)) {
                    return zip.readBytes().inputStream()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            zip.close()
            null
        } catch (_: Exception) {
            null
        }
    }

    fun findFirstImage(bytes: ByteArray): InputStream? {
        return try {
            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            // Collect all image entries, sort by name, return first
            val imageEntries = mutableListOf<Pair<String, ByteArray>>()
            var entry = zip.nextEntry
            while (entry != null) {
                val ext = entry.name.substringAfterLast('.').lowercase()
                if (!entry.isDirectory && ext in imageExtensions) {
                    imageEntries.add(entry.name to zip.readBytes())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            zip.close()
            imageEntries.minByOrNull { it.first }?.second?.inputStream()
        } catch (_: Exception) {
            null
        }
    }
}
