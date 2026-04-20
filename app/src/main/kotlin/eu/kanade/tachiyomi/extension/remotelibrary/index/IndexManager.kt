package eu.kanade.tachiyomi.extension.remotelibrary.index

import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class IndexManager(config: LibraryConfig, filesDir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val libraryDir = File(filesDir, "mihon-remote/libraries/${config.id}").also { it.mkdirs() }
    private val indexFile = File(libraryDir, "index.json")
    private val tempFile = File(libraryDir, "index.json.tmp")

    fun hasIndex(): Boolean = indexFile.exists() && indexFile.length() > 0

    fun load(): MangaIndex? {
        if (!hasIndex()) return null
        return try {
            json.decodeFromString<MangaIndex>(indexFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun save(index: MangaIndex) {
        tempFile.writeText(json.encodeToString(index))
        if (indexFile.exists()) indexFile.delete()
        tempFile.renameTo(indexFile)
    }

    fun delete() {
        indexFile.delete()
        tempFile.delete()
    }

    fun getLastGeneratedAt(): String? {
        if (!hasIndex()) return null
        return try {
            // Quick scan without full parse
            val text = indexFile.readText()
            val match = Regex(""""generated_at"\s*:\s*"([^"]+)"""").find(text)
            match?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }
}
