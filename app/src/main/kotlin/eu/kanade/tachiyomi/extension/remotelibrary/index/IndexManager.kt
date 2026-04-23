package eu.kanade.tachiyomi.extension.remotelibrary.index

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryConfigProvider
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages the per-library index through [LibraryConfigProvider] so that both Mihon's process
 * (source classes) and the extension's process (ScanProgressActivity) share the same file.
 *
 * Index files live in the extension's filesDir, managed by the ContentProvider.
 * Large JSON is streamed via openFile() / ParcelFileDescriptor to avoid Binder's 1 MB limit.
 */
class IndexManager(
    private val config: LibraryConfig,
    private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val authority = LibraryConfigProvider.AUTHORITY

    /** URI that openFile() recognises for this library's index. */
    private val indexUri: Uri =
        Uri.parse("content://$authority/index/${config.id}")

    fun hasIndex(): Boolean {
        return try {
            val result = context.contentResolver.call(
                authority, LibraryConfigProvider.METHOD_HAS_INDEX, config.id, null,
            ) ?: return false
            result.getBoolean(LibraryConfigProvider.KEY_BOOL, false)
        } catch (_: Exception) {
            false
        }
    }

    fun load(): MangaIndex? {
        if (!hasIndex()) return null
        return try {
            val text = context.contentResolver
                .openInputStream(indexUri)
                ?.use { it.readBytes().decodeToString() }
                ?: return null
            json.decodeFromString<MangaIndex>(text)
        } catch (_: Exception) {
            null
        }
    }

    fun save(index: MangaIndex) {
        try {
            val bytes = json.encodeToString(index).toByteArray(Charsets.UTF_8)
            context.contentResolver
                .openOutputStream(indexUri)
                ?.use { it.write(bytes) }
        } catch (_: Exception) { /* scan failure is surfaced by the caller */ }
    }

    fun delete() {
        try {
            context.contentResolver.call(
                authority, LibraryConfigProvider.METHOD_DELETE_INDEX, config.id, null,
            )
        } catch (_: Exception) {}
    }

    fun getLastGeneratedAt(): String? {
        return try {
            val result = context.contentResolver.call(
                authority, LibraryConfigProvider.METHOD_GET_LAST_AT, config.id, null,
            ) ?: return null
            result.getString(LibraryConfigProvider.KEY_STRING)
        } catch (_: Exception) {
            null
        }
    }
}
