package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

// Compile-only stub — real implementation provided by Mihon at runtime.
interface CatalogueSource : Source {
    val supportsLatest: Boolean
    fun fetchPopularManga(page: Int): Observable<MangasPage>
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage>
    fun fetchLatestUpdates(page: Int): Observable<MangasPage>
    fun fetchMangaDetails(manga: SManga): Observable<SManga>
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>>
    fun fetchPageList(chapter: SChapter): Observable<List<Page>>
    fun fetchImageUrl(page: Page): Observable<String>
    fun getFilterList(): FilterList
}
