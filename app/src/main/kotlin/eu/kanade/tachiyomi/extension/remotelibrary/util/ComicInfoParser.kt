package eu.kanade.tachiyomi.extension.remotelibrary.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class ComicInfo(
    val title: String? = null,
    val series: String? = null,
    val summary: String? = null,
    val writer: String? = null,
    val penciller: String? = null,
    val genre: String? = null,
    val status: String? = null,
)

object ComicInfoParser {

    fun parse(stream: InputStream): ComicInfo? {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(stream, null)

            val fields = mutableMapOf<String, String>()
            var currentTag: String? = null
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> currentTag = parser.name
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim()
                        if (currentTag != null && !text.isNullOrEmpty()) {
                            fields[currentTag] = text
                        }
                    }
                    XmlPullParser.END_TAG -> currentTag = null
                }
                eventType = parser.next()
            }

            ComicInfo(
                title = fields["Title"],
                series = fields["Series"],
                summary = fields["Summary"],
                writer = fields["Writer"],
                penciller = fields["Penciller"],
                genre = fields["Genre"],
                status = mapStatus(fields["Status"]),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun mapStatus(status: String?): String? = when (status) {
        "Ongoing" -> "ongoing"
        "Completed" -> "completed"
        "Hiatus" -> "hiatus"
        "Abandoned" -> "cancelled"
        else -> null
    }
}
