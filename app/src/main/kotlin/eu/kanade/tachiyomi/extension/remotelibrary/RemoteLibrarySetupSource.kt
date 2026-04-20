package eu.kanade.tachiyomi.extension.remotelibrary

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.DriveAuthManager
import eu.kanade.tachiyomi.extension.remotelibrary.ui.AddLibraryActivity
import eu.kanade.tachiyomi.extension.remotelibrary.ui.GlobalSettingsActivity
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

/**
 * Placeholder source shown when no libraries have been configured yet.
 * Browsing it opens AddLibraryActivity. Settings gear opens the preference screen.
 */
class RemoteLibrarySetupSource(private val context: Context) : CatalogueSource, ConfigurableSource {

    // Our extension's applicationId — needed because screen.context / stored context
    // is Mihon's process context, so Intent(ctx, Class) would resolve to app.mihon/...
    private fun extIntent(cls: Class<*>, block: Intent.() -> Unit = {}): Intent =
        Intent().apply {
            component = ComponentName(EXT_PACKAGE, cls.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            block()
        }

    override val id: Long = 7891234567890123L
    override val name: String = "Remote Library"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    // ------------------------------------------------------------------
    // ConfigurableSource
    // ------------------------------------------------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context

        Preference(ctx).apply {
            title = "Add Library"
            summary = "Connect a Google Drive folder as a manga library"
            setOnPreferenceClickListener {
                ctx.startActivity(extIntent(AddLibraryActivity::class.java))
                true
            }
            screen.addPreference(this)
        }

        val auth = DriveAuthManager(ctx)
        Preference(ctx).apply {
            title = "Google Account"
            summary = if (auth.isAuthenticated())
                "Connected: ${auth.getAccountEmail() ?: "Google Account"}"
            else
                "Not connected — tap to connect"
            setOnPreferenceClickListener {
                ctx.startActivity(extIntent(GlobalSettingsActivity::class.java))
                true
            }
            screen.addPreference(this)
        }

        Preference(ctx).apply {
            title = "How to get started"
            summary = "1. Connect your Google Account above\n2. Tap \"Add Library\" and paste your Drive folder URL\n3. Restart Mihon — your library appears as a source"
            isSelectable = false
            screen.addPreference(this)
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
