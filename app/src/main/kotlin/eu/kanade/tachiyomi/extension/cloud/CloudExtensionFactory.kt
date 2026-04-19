package eu.kanade.tachiyomi.extension.cloud

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.extension.cloud.library.LibraryRegistry
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CloudExtensionFactory : SourceFactory {

    private val application: Application = Injekt.get()

    override fun createSources(): List<Source> {
        val context: Context = application.applicationContext
        val registry = LibraryRegistry(
            context.getSharedPreferences("cloud_extension", Context.MODE_PRIVATE),
        )
        return registry.getAll().map { config ->
            CloudMangaSource(config, context)
        }
    }
}
