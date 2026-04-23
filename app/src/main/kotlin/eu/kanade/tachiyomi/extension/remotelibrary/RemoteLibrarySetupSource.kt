package eu.kanade.tachiyomi.extension.remotelibrary

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.remotelibrary.cache.CoverCache
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexManager
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryConfig
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryRegistry
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.DriveAuthManager
import eu.kanade.tachiyomi.extension.remotelibrary.ui.AddLibraryActivity
import eu.kanade.tachiyomi.extension.remotelibrary.ui.GlobalSettingsActivity
import eu.kanade.tachiyomi.extension.remotelibrary.ui.ScanProgressActivity
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rx.Observable

/**
 * Placeholder source shown when no libraries are configured, and the central hub for
 * extension-wide + per-library settings accessed via the gear icon.
 *
 * This class safely implements both CatalogueSource and ConfigurableSource because
 * getChapterList() is never invoked on it (users never browse chapters through this source).
 * The conflicting interface defaults only trigger at call time, not at class-load time.
 */
class RemoteLibrarySetupSource(private val context: Context) : CatalogueSource, ConfigurableSource {

    private fun extIntent(cls: Class<*>, block: Intent.() -> Unit = {}): Intent =
        Intent().apply {
            component = ComponentName(EXT_PACKAGE, cls.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            block()
        }

    override val id: Long = 7891234567890123L
    override val name: String = "Remote Library"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // ConfigurableSource — gear icon. Hosts global AND per-library settings
    // because per-library sources cannot implement ConfigurableSource without
    // triggering an IncompatibleClassChangeError in this version of Mihon.
    // ------------------------------------------------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context

        // ---- Global settings ----

        Preference(ctx).apply {
            title = "Add Library"
            summary = "Connect a Google Drive folder as a manga library"
            setOnPreferenceClickListener {
                ctx.startActivity(extIntent(AddLibraryActivity::class.java))
                true
            }
            screen.addPreference(this)
        }

        Preference(ctx).apply {
            title = "Google Account"
            summaryProvider = Preference.SummaryProvider<Preference> { _ ->
                val auth = DriveAuthManager(ctx)
                if (auth.isAuthenticated()) "Connected: ${auth.getAccountEmail() ?: "Google Account"}"
                else "Not connected — tap to connect"
            }
            setOnPreferenceClickListener {
                ctx.startActivity(extIntent(GlobalSettingsActivity::class.java))
                true
            }
            screen.addPreference(this)
        }

        // ---- Per-library settings ----

        val registry = LibraryRegistry(ctx)
        val libraries = registry.getAll()

        if (libraries.isEmpty()) {
            Preference(ctx).apply {
                title = "How to get started"
                summary = "1. Connect your Google Account above\n" +
                    "2. Tap \"Add Library\" and paste your Drive folder URL\n" +
                    "3. Restart Mihon — your library appears as a source"
                isSelectable = false
                screen.addPreference(this)
            }
        } else {
            libraries.forEach { config ->
                addLibraryPreferences(screen, ctx, config, registry)
            }
        }
    }

    private fun addLibraryPreferences(screen: PreferenceScreen, ctx: android.content.Context, config: LibraryConfig, registry: LibraryRegistry) {
        val indexManager = IndexManager(config, ctx)

        PreferenceCategory(ctx).apply {
            title = config.displayName
            screen.addPreference(this)

            Preference(ctx).apply {
                title = "Rescan Library"
                summary = "Last scanned: ${indexManager.getLastGeneratedAt() ?: "Never"}"
                setOnPreferenceClickListener {
                    indexManager.delete()
                    CoverCache(config.id, ctx.filesDir).clearAll()
                    ctx.startActivity(
                        extIntent(ScanProgressActivity::class.java) {
                            putExtra(ScanProgressActivity.EXTRA_LIBRARY_JSON, json.encodeToString(config))
                        },
                    )
                    true
                }
                addPreference(this)
            }

            Preference(ctx).apply {
                title = "Clear Cover Cache"
                summary = "Delete all cached covers for this library"
                setOnPreferenceClickListener {
                    CoverCache(config.id, ctx.filesDir).clearAll()
                    Toast.makeText(ctx, "Cover cache cleared for \"${config.displayName}\"", Toast.LENGTH_SHORT).show()
                    true
                }
                addPreference(this)
            }

            Preference(ctx).apply {
                title = "Remove Library"
                summary = "Remove \"${config.displayName}\" and delete all local data. Restart Mihon after."
                setOnPreferenceClickListener {
                    registry.remove(config.id)
                    ctx.filesDir.resolve("mihon-remote/libraries/${config.id}").deleteRecursively()
                    Toast.makeText(
                        ctx,
                        "\"${config.displayName}\" removed. Restart Mihon to update sources.",
                        Toast.LENGTH_LONG,
                    ).show()
                    true
                }
                addPreference(this)
            }
        }
    }

    // ------------------------------------------------------------------
    // CatalogueSource — browsing opens AddLibraryActivity
    // ------------------------------------------------------------------

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        context.startActivity(extIntent(AddLibraryActivity::class.java))
        return Observable.just(MangasPage(emptyList(), false))
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.just(MangasPage(emptyList(), false))

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        Observable.just(MangasPage(emptyList(), false))

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.just(manga)

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Observable.just(emptyList())

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        Observable.just(emptyList())

    override fun fetchImageUrl(page: Page): Observable<String> = Observable.just("")

    override fun getFilterList(): FilterList = FilterList()
}
