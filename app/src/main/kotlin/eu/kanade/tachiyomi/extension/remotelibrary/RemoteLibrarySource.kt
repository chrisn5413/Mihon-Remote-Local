package eu.kanade.tachiyomi.extension.remotelibrary

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.extension.remotelibrary.cache.CoverCache
import eu.kanade.tachiyomi.extension.remotelibrary.cache.ReadingCache
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexManager
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexSeries
import eu.kanade.tachiyomi.extension.remotelibrary.index.MangaIndex
import eu.kanade.tachiyomi.extension.remotelibrary.library.CoverLoadMode
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryConfig
import eu.kanade.tachiyomi.extension.remotelibrary.storage.MangaStorage
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.GoogleDriveClient
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.GoogleDriveStorage
import eu.kanade.tachiyomi.extension.remotelibrary.ui.ScanProgressActivity
import eu.kanade.tachiyomi.extension.remotelibrary.util.LocalPageServer
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import rx.Observable

/**
 * Per-library manga source backed by Google Drive.
 *
 * Extends [HttpSource] so that Mihon's ChapterLoader recognises it and creates an
 * HttpPageLoader (which calls fetchPageList). A plain CatalogueSource falls through to
 * the `else` branch in getPageLoader() and throws "Source not found" regardless of
 * whether the source ID matches.
 *
 * Page images are served via [LocalPageServer] — a minimal loopback TCP server that
 * maps http://127.0.0.1:{port}/cache/... URLs back to files in our reading cache.
 * This lets Mihon's own OkHttp client fetch pages normally without any actual network
 * traffic, while avoiding the file:// scheme that OkHttp cannot handle.
 *
 * Does NOT implement ConfigurableSource. Mihon's runtime ConfigurableSource provides a
 * default getChapterList() that conflicts with CatalogueSource's default, causing
 * IncompatibleClassChangeError. Per-library settings are accessed via RemoteLibrarySetupSource.
 */
class RemoteLibrarySource(
    private val config: LibraryConfig,
    private val context: Context,
) : HttpSource() {

    companion object {
        private const val PAGE_SIZE = 25
    }

    private fun extIntent(cls: Class<*>, block: Intent.() -> Unit = {}): Intent =
        Intent().apply {
            component = ComponentName(EXT_PACKAGE, cls.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            block()
        }

    // ------------------------------------------------------------------
    // HttpSource / Source identity
    // ------------------------------------------------------------------

    override val id: Long = stableId("eu.kanade.tachiyomi.extension.remotelibrary/${config.id}")
    override val name: String = "Remote Library: ${config.displayName}"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    /**
     * baseUrl is unused — we never make HTTP requests to an external host.
     * Required by HttpSource but overridden here to satisfy the abstract contract.
     */
    override val baseUrl: String = ""

    // Mihon uses source.toString() to build a SAF download-directory path.
    // Returning a plain string (no '@', no ':') keeps the SAF path well-formed.
    override fun toString(): String = "Remote Library ${config.displayName}"

    // ------------------------------------------------------------------
    // Infrastructure
    // ------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val authManager by lazy {
        eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.DriveAuthManager(context)
    }
    private val driveClient by lazy { GoogleDriveClient(authManager) }
    private val storage: MangaStorage by lazy { GoogleDriveStorage(driveClient, config.rootFolderId) }
    private val indexManager by lazy { IndexManager(config, context) }
    private val coverCache by lazy { CoverCache(config.id, context.filesDir, storage) }
    /**
     * Reading cache lives in filesDir (persistent app storage), NOT cacheDir.
     *
     * cacheDir is Mihon's own transient cache directory — Mihon or Android can clear it
     * at any time (e.g. when the reader closes), deleting pages the user just downloaded.
     * filesDir is private to the app and is only cleared when the user explicitly wipes
     * app data. Our own evictIfNeeded() manages the size cap.
     */
    private val readingCache by lazy { ReadingCache(context.filesDir, storage, config.readingCacheLimitMb) }

    /**
     * Loopback HTTP server that serves cached page images to Mihon's HttpPageLoader.
     * Started lazily on first page request; lives as long as the source object.
     * Must use the same base directory as [readingCache].
     */
    private val pageServer by lazy { LocalPageServer(context.filesDir).also { it.start() } }

    @Volatile private var index: MangaIndex? = null

    private fun loadIndex(): MangaIndex? {
        if (index == null) synchronized(this) {
            if (index == null) index = indexManager.load()
        }
        return index
    }

    // ------------------------------------------------------------------
    // HttpSource abstract method stubs
    //
    // These are never called because we override all fetch* Observable methods below.
    // They exist only to satisfy the abstract class contract so the file compiles.
    // ------------------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request =
        throw UnsupportedOperationException("Not used — fetchPopularManga is overridden")

    override fun popularMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException("Not used — fetchPopularManga is overridden")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException("Not used — fetchSearchManga is overridden")

    override fun searchMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException("Not used — fetchSearchManga is overridden")

    override fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException("Not used — fetchLatestUpdates is overridden")

    override fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException("Not used — fetchLatestUpdates is overridden")

    override fun mangaDetailsParse(response: Response): SManga =
        throw UnsupportedOperationException("Not used — fetchMangaDetails is overridden")

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException("Not used — fetchChapterList is overridden")

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException("Not used — fetchPageList is overridden")

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used — fetchImageUrl is overridden")

    // ------------------------------------------------------------------
    // CatalogueSource implementation
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

    /**
     * Downloads and caches the chapter's pages, then returns http://127.0.0.1:{port}/cache/...
     * URLs served by [pageServer]. Mihon's HttpPageLoader fetches these via its own OkHttp
     * client — loopback only, no actual network traffic.
     */
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        runBlocking(Dispatchers.IO) {
            try {
                val (seriesId, chapterId) = chapter.url.split("::", limit = 2)
                android.util.Log.d("RemoteLibrary", "fetchPageList: seriesId=$seriesId chapterId=$chapterId")

                val current = loadIndex() ?: error("Library index not available")
                val series = current.series.firstOrNull { it.id == seriesId }
                    ?: error("Series not found in index: $seriesId")
                val indexChapter = series.chapters.firstOrNull { it.id == chapterId }
                    ?: error("Chapter not found in index: $chapterId")

                android.util.Log.d(
                    "RemoteLibrary",
                    "fetchPageList: isArchive=${indexChapter.isArchive} " +
                        "folderId=${indexChapter.folderId} " +
                        "archiveFileId=${indexChapter.archiveFileId}",
                )

                val pageFiles = readingCache.getPages(series, indexChapter)
                android.util.Log.d("RemoteLibrary", "fetchPageList: got ${pageFiles.size} pages from cache")

                // Ensure the server is running before building URLs.
                val port = pageServer.port
                pageFiles.mapIndexed { idx, file ->
                    // Path is relative to filesDir (which LocalPageServer also uses as baseDir).
                    val relPath = file.relativeTo(context.filesDir).path
                        .replace(java.io.File.separatorChar, '/')
                    Page(idx, "", "http://127.0.0.1:$port/cache/$relPath")
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "RemoteLibrary",
                    "fetchPageList FAILED: ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
                throw e
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
            "ongoing"   -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus"    -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else        -> SManga.UNKNOWN
        }
        // Serve covers via LocalPageServer (same loopback server used for pages).
        // Using file:// URIs caused Coil to cache the "file not found" failure permanently
        // — even after the cover downloaded in the background, Coil wouldn't retry.
        // HTTP 404s are not cached the same way, so Coil retries on the next grid refresh
        // once the cover file has been written by the background download coroutine.
        val coverRelPath = "mihon-remote/libraries/${config.id}/covers/${this@toSManga.id}.jpg"
        thumbnail_url = "http://127.0.0.1:${pageServer.port}/cache/$coverRelPath"

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
