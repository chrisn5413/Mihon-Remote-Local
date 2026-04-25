package eu.kanade.tachiyomi.extension.remotelibrary

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryRegistry
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class RemoteLibraryFactory : SourceFactory {

    override fun createSources(): List<Source> {
        val context: Context = currentApplication().applicationContext
        val registry = LibraryRegistry(context)
        val librarySources = registry.getAll().map { config -> RemoteLibrarySource(config, context) }
        // SetupSource is always included so the gear-icon settings screen (ConfigurableSource)
        // remains accessible regardless of how many libraries are configured. It appears first
        // so it anchors the settings entry point even as library sources come and go.
        return listOf(RemoteLibrarySetupSource(context)) + librarySources
    }

    private fun currentApplication(): Application =
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as Application
}
