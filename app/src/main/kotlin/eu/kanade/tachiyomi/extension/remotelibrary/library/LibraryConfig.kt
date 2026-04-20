package eu.kanade.tachiyomi.extension.remotelibrary.library

import kotlinx.serialization.Serializable

@Serializable
data class LibraryConfig(
    val id: String,
    val displayName: String,
    val rootFolderId: String,
    val coverLoadMode: CoverLoadMode = CoverLoadMode.LAZY,
    val prefetchCount: Int = 50,
    val keepReadingCache: Boolean = false,
    val readingCacheLimitMb: Int = 500,
)

@Serializable
enum class CoverLoadMode { LAZY, EAGER, PREFETCH_N }
