package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable

/**
 * Compile-only stub — the real class lives in Mihon's APK and is resolved by the parent
 * classloader at runtime. This stub exists only so our source can compile against it and
 * satisfy the `source is HttpSource` check in Mihon's ChapterLoader.getPageLoader().
 *
 * Mihon's ChapterLoader will only create an HttpPageLoader (which calls fetchPageList)
 * when the source passes `source is HttpSource`. Plain CatalogueSource sources fall to the
 * `else` branch and throw "Source not found" even when the source ID is correct.
 *
 * The real HttpSource provides a network client via Mihon's DI system. We do NOT override
 * `client` — Mihon's default client handles our http://127.0.0.1:PORT/ page URLs just fine.
 */
abstract class HttpSource : CatalogueSource {

    abstract val baseUrl: String

    /** Provided by Mihon's network infrastructure at runtime. */
    open val client: OkHttpClient = OkHttpClient()

    // ------------------------------------------------------------------
    // Concrete fetch methods — we override all of these in RemoteLibrarySource,
    // so the *Request/*Parse abstract methods below are never actually called.
    // ------------------------------------------------------------------

    override fun fetchPopularManga(page: Int): Observable<MangasPage> =
        throw UnsupportedOperationException("stub")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        throw UnsupportedOperationException("stub")

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        throw UnsupportedOperationException("stub")

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw UnsupportedOperationException("stub")

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw UnsupportedOperationException("stub")

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw UnsupportedOperationException("stub")

    override fun fetchImageUrl(page: Page): Observable<String> =
        throw UnsupportedOperationException("stub")

    override fun getFilterList(): FilterList = FilterList()

    // ------------------------------------------------------------------
    // Abstract methods required by the real HttpSource.
    // Our source stubs these out since we override all fetch* observables above.
    // ------------------------------------------------------------------

    abstract fun popularMangaRequest(page: Int): Request
    abstract fun popularMangaParse(response: Response): MangasPage

    abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request
    abstract fun searchMangaParse(response: Response): MangasPage

    abstract fun latestUpdatesRequest(page: Int): Request
    abstract fun latestUpdatesParse(response: Response): MangasPage

    abstract fun mangaDetailsParse(response: Response): SManga

    abstract fun chapterListParse(response: Response): List<SChapter>

    abstract fun pageListParse(response: Response): List<Page>

    abstract fun imageUrlParse(response: Response): String
}
