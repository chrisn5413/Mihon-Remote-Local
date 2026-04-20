package eu.kanade.tachiyomi.extension.remotelibrary

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryRegistry
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class RemoteLibraryFactory : SourceFactory {

    override fun createSources(): List<Source> {
        val context: Context = currentApplication().applicationContext
        val registry = LibraryRegistry(
            context.getSharedPreferences("remote_library", Context.MODE_PRIVATE),
        )
        val librarySources = registry.getAll().map { config -> RemoteLibrarySource(config, context) }
        // Always expose at least one source so users can reach AddLibraryActivity
        return librarySources.ifEmpty { listOf(RemoteLibrarySetupSource(context)) }
    }

    private fun currentApplication(): Application =
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as Application
}
