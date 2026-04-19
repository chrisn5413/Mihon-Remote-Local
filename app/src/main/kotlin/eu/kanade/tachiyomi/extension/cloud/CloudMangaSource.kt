package eu.kanade.tachiyomi.extension.cloud

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.extension.cloud.cache.CoverCache
import eu.kanade.tachiyomi.extension.cloud.cache.ReadingCache
import eu.kanade.tachiyomi.extension.cloud.index.IndexManager
import eu.kanade.tachiyomi.extension.cloud.index.IndexSeries
import eu.kanade.tachiyomi.extension.cloud.index.MangaIndex
import eu.kanade.tachiyomi.extension.cloud.library.CoverLoadMode
import eu.kanade.tachiyomi.extension.cloud.library.LibraryConfig
import eu.kanade.tachiyomi.extension.cloud.storage.MangaStorage
import eu.kanade.tachiyomi.extension.cloud.storage.googledrive.DriveAuthManager
import eu.kanade.tachiyomi.extension.cloud.storage.googledrive.GoogleDriveClient
import eu.kanade.tachiyomi.extension.cloud.storage.googledrive.GoogleDriveStorage
import eu.kanade.tachiyomi.extension.cloud.ui.ScanProgressActivity
import eu.kanade.tachiyomi.source.CatalogueSource
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

class CloudMangaSource(
    private val config: LibraryConfig,
    private val context: Context,
) : CatalogueSource {

    companion object {
        private const val PAGE_SIZE = 25
    }

    override val id: Long = stableId("eu.kanade.tachiyomi.extension.cloud/${config.id}")
    override val name: String = "Cloud: ${config.displayName}"
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
        // Point to where the cover file will live on disk (Coil handles file:// URIs natively)
        thumbnail_url = "file://${coverCache.coverFileFor(this@toSManga.id).absolutePath}"

        // Lazy: kick off background download if not yet cached
        if (config.coverLoadMode == CoverLoadMode.LAZY && !coverCache.isCached(this@toSManga.id)) {
            val ref = this@toSManga
            scope.launch { coverCache.getCover(ref) }
        }
    }

    private fun findSeries(url: String): IndexSeries? =
        loadIndex()?.series?.firstOrNull { it.id == url }

    private fun launchScanActivity() {
        context.startActivity(
            Intent(context, ScanProgressActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
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
