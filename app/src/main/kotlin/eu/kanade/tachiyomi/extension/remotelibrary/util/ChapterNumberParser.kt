package eu.kanade.tachiyomi.extension.remotelibrary.util

object ChapterNumberParser {

    private val patterns = listOf(
        Regex("""(?:Chapter|Ch\.?)\s*0*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Vol\.\s*\d+\s+)?Ch\.?\s*0*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
        Regex("""^0*(\d+(?:\.\d+)?)"""),
    )

    fun parse(name: String): Float {
        // Strip file extension before parsing
        val cleaned = name.substringBeforeLast('.').trim()

        for (pattern in patterns) {
            val match = pattern.find(cleaned) ?: continue
            val numberStr = match.groupValues[1]
            val number = numberStr.toFloatOrNull() ?: continue
            if (number >= 0) return number
        }
        return -1f
    }
}
