package eu.kanade.tachiyomi.source.model

import android.net.Uri

// Compile-only stub — real implementation provided by Mihon at runtime.
open class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    @Transient var uri: Uri? = null,
)
