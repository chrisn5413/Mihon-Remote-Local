package eu.kanade.tachiyomi.extension.remotelibrary.library

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists library configs via [LibraryConfigProvider], a ContentProvider that runs in
 * the extension's process. Using ContentResolver means both Mihon's process (source factory,
 * source classes) and the extension's process (Activities) access the same data via IPC —
 * solving the cross-process SharedPreferences limitation.
 */
class LibraryRegistry(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val authority = LibraryConfigProvider.AUTHORITY

    fun getAll(): List<LibraryConfig> {
        return try {
            val result = context.contentResolver.call(
                authority,
                LibraryConfigProvider.METHOD_GET_ALL,
                null,
                null,
            ) ?: return emptyList()
            val raw = result.getString(LibraryConfigProvider.KEY_JSON) ?: return emptyList()
            json.decodeFromString<List<LibraryConfig>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(libraryId: String): LibraryConfig? = getAll().firstOrNull { it.id == libraryId }

    fun add(config: LibraryConfig) {
        val list = getAll().toMutableList()
        list.add(config)
        saveAll(list)
    }

    fun update(config: LibraryConfig) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        saveAll(list)
    }

    fun remove(libraryId: String) {
        saveAll(getAll().filter { it.id != libraryId })
    }

    private fun saveAll(list: List<LibraryConfig>) {
        try {
            context.contentResolver.call(
                authority,
                LibraryConfigProvider.METHOD_SAVE_ALL,
                json.encodeToString(list),
                null,
            )
        } catch (_: Exception) {}
    }
}
