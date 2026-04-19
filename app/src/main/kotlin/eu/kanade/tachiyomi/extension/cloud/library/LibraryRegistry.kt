package eu.kanade.tachiyomi.extension.cloud.library

import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LibraryRegistry(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }
    private val key = "libraries"

    fun getAll(): List<LibraryConfig> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<LibraryConfig>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(libraryId: String): LibraryConfig? = getAll().firstOrNull { it.id == libraryId }

    fun add(config: LibraryConfig) {
        val list = getAll().toMutableList()
        list.add(config)
        save(list)
    }

    fun update(config: LibraryConfig) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        save(list)
    }

    fun remove(libraryId: String) {
        save(getAll().filter { it.id != libraryId })
    }

    private fun save(list: List<LibraryConfig>) {
        prefs.edit().putString(key, json.encodeToString(list)).apply()
    }
}
