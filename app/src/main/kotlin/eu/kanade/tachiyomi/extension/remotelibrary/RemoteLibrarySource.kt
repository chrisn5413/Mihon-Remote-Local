package eu.kanade.tachiyomi.extension.remotelibrary

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.remotelibrary.cache.CoverCache
import eu.kanade.tachiyomi.extension.remotelibrary.cache.ReadingCache
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexManager
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexSeries
import eu.kanade.tachiyomi.extension.remotelibrary.index.MangaIndex
import eu.kanade.tachiyomi.extension.remotelibrary.library.CoverLoadMode
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryConfig
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryRegistry
import eu.kanade.tachiyomi.extension.remotelibrary.storage.MangaStorage
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.DriveAuthManager
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.GoogleDriveClient
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.GoogleDriveStorage
import eu.kanade.tachiyomi.extension.remotelibrary.ui.GlobalSettingsActivity
import eu.kanade.tachiyomi.extension.remotelibrary.ui.ScanProgressActivity
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import rx.Observable

class RemoteLibrarySource(
    private val config: LibraryConfig,
    private val context: Context,
) : CatalogueSource, ConfigurableSource {

    companion object {
        private const val PAGE_SIZE = 25
    }

    // screen.context (and our stored context) is Mihon's process context — packageName = app.mihon.
    // Intent(ctx, Class) would produce {app.mihon/our.Activity} which Android can't resolve.
    // Use an explicit ComponentName with our own applicationId instead.
    private fun extIntent(cls: Class<*>, block: Intent.() -> Unit = {}): Intent =
        Intent().apply {
            component = ComponentName(EXT_PACKAGE, cls.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            block()
        }

    override val id: Long = stableId("eu.kanade.tachiyomi.extension.remotelibrary/${config.id}")
    override val name: String = "Remote Library: ${config.displayName}"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val okHttpClient = OkHttpClient()

    private val authManager by lazy { DriveAuthManager(context) }
    private val driveClient by lazy { GoogleDriveClient(authManager, okHttpClient) }
    private val storage: MangaStorage by lazy { GoogleDriveStorage(driveClient, config.rootFolderId) }
    private val indexManager by lazy { IndexManager(config, context.filesDir) }
    private val coverCache by lazy { CoverCache(config.id, context.filesDir, storage) }
    private val readingCache by lazy { ReadingCache(context.cacheDir, storage, config.readingCacheLimitMb) }

    @Volatile private var index: MangaIndex? = null

    private fun loadIndex(): MangaIndex? {
        if (index == null) synchronized(this) {
            if (index == null) index = indexManager.load()
        }
        return index
    }

    // ------------------------------------------------------------------
    // ConfigurableSource — gear icon in Extension Info
    // ------------------------------------------------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context

        // Google account
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

        // Rescan library
        Preference(ctx).apply {
            title = "Rescan Library"
            summary = "Last scanned: ${indexManager.getLastGeneratedAt() ?: "Never"}"
            setOnPreferenceClickListener {
                indexManager.delete()
                coverCache.clearAll()
                index = null
                ctx.startActivity(
                    extIntent(ScanProgressActivity::class.java) {
                        putExtra(ScanProgressActivity.EXTRA_LIBRARY_JSON, json.encodeToString(config))
                    },
                )
                true
            }
            screen.addPreference(this)
        }

        // Clear cover cache
        Preference(ctx).apply {
            title = "Clear Cover Cache"
            summary = "Delete all cached cover images for this library"
            setOnPreferenceClickListener {
                coverCache.clearAll()
                Toast.makeText(ctx, "Cover cache cleared", Toast.LENGTH_SHORT).show()
                true
            }
            screen.addPreference(this)
        }

        // Remove library
        Preference(ctx).apply {
            title = "Remove Library"
            summary = "Remove \"${config.displayName}\" and delete all local data. Restart Mihon after."
            setOnPreferenceClickListener {
                val registry = LibraryRegistry(
                    ctx.getSharedPreferences("remote_library", Context.MODE_PRIVATE),
                )
                registry.remove(config.id)
                ctx.filesDir.resolve("mihon-remote/libraries/${config.id}").deleteRecursively()
                Toast.makeText(
                    ctx,
                    "\"${config.displayName}\" removed. Restart Mihon to update sources.",
                    Toast.LENGTH_LONG,
                ).show()
                true
            }
            screen.addPreference(this)
        }
    }

    // ------------------------------------------------------------------
    // CatalogueSource
    // ------------------------------------------------------------------

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        val current = loadIndex()
        if (current == null) {
            launchScanActivity()
            return@fromCallable MangasPage(emptyList(), false)
        }

        val series = current.series
        val start = (page - 1) * PAGE_SIZE
        if (start >= series.size) return@fromCallable MangasPage(emptyList(), false)
        val end = minOf(start + PAGE_SIZE, series.size)

        when (config.coverLoadMode) {
            CoverLoadMode.EAGER -> if (page == 1) scope.launch {
                series.forEach { coverCache.getCover(it) }
            }
            CoverLoadMode.PREFETCH_N -> if (page == 1) scope.launch {
                series.take(config.prefetchCount).forEach { coverCache.getCover(it) }
            }
            CoverLoadMode.LAZY -> Unit
        }

        MangasPage(series.subList(start, end).map { it.toSManga() }, end < series.size)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable {
            val current = loadIndex() ?: return@fromCallable MangasPage(emptyList(), false)
            val filtered = current.series.filter { it.title.contains(query, ignoreCase = true) }
            val start = (page - 1) * PAGE_SIZE
            if (start >= filtered.size) return@fromCallable MangasPage(emptyList(), false)
            val end = minOf(start + PAGE_SIZE, filtered.size)
            MangasPage(filtered.subList(start, end).map { it.toSManga() }, end < filtered.size)
        }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        Observable.just(MangasPage(emptyList(), false))

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        findSeries(manga.url)?.toSManga() ?: manga
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val series = findSeries(manga.url) ?: return@fromCallable emptyList<SChapter>()
        series.chapters
            .sortedWith(compareBy({ it.number }, { it.title }))
            .map { ch ->
                SChapter.create().apply {
                    url = "${series.id}::${ch.id}"
                    name = ch.title
                    chapter_number = ch.number
                    date_upload = 0L
                }
            }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        runBlocking(Dispatchers.IO) {
            val (seriesId, chapterId) = chapter.url.split("::", limit = 2)
            val current = loadIndex() ?: error("Library index not available")
            val series = current.series.first { it.id == seriesId }
            val indexChapter = series.chapters.first { it.id == chapterId }

            val pageFiles = readingCache.getPages(series, indexChapter)
            pageFiles.mapIndexed { idx, file ->
                Page(idx, "", "file://${file.absolutePath}")
            }
        }
    }

    override fun fetchImageUrl(page: Page): Observable<String> =
        Observable.just(page.imageUrl ?: "")

    override fun getFilterList(): FilterList = FilterList()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun IndexSeries.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.id
        title = this@toSManga.title
        author = this@toSManga.author
        artist = this@toSManga.artist
        description = this@toSManga.description
        genre = this@toSManga.tags.joinToString(", ").ifEmpty { null }
        status = when (this@toSManga.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        thumbnail_url = "file://${coverCache.coverFileFor(this@toSManga.id).absolutePath}"

        if (config.coverLoadMode == CoverLoadMode.LAZY && !coverCache.isCached(this@toSManga.id)) {
            val ref = this@toSManga
            scope.launch { coverCache.getCover(ref) }
        }
    }

    private fun findSeries(url: String): IndexSeries? =
        loadIndex()?.series?.firstOrNull { it.id == url }

    private fun launchScanActivity() {
        context.startActivity(
            extIntent(ScanProgressActivity::class.java) {
                putExtra(ScanProgressActivity.EXTRA_LIBRARY_JSON, json.encodeToString(config))
            },
        )
    }

    private fun stableId(key: String): Long {
        var h = 1125899906842597L
        for (c in key) h = 31 * h + c.code
        return h
    }
}
