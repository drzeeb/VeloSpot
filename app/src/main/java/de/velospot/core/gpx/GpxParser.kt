package de.velospot.core.gpx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One track parsed from a GPX file: its optional name and its raw points. */
data class ParsedTrack(
    val name: String?,
    val points: List<ParsedTrackPoint>
)

/** A single `<trkpt>`: coordinate, optional elevation and (epoch-millis) time. */
data class ParsedTrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val timestampMillis: Long?
)

/**
 * Minimal, dependency-free **GPX reader** for import. Extracts every `<trk>` with
 * its `<name>` and the `<trkpt>`s of its `<trkseg>`s (segments of one track are
 * merged). Routes (`<rte>`) and standalone waypoints are ignored — VeloSpot rides
 * are tracks. Tolerant of missing `<ele>`/`<time>` and of unknown elements.
 */
object GpxParser {

    /** Parses [input] (a GPX document stream) into its tracks. */
    fun parse(input: InputStream): List<ParsedTrack> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

        val tracks = mutableListOf<ParsedTrack>()
        var trackName: String? = null
        var trackPoints: MutableList<ParsedTrackPoint>? = null

        // Current trkpt being assembled.
        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        var timeMillis: Long? = null
        var inTrkpt = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "trk" -> { trackName = null; trackPoints = mutableListOf() }
                    "trkpt" -> {
                        inTrkpt = true
                        ele = null
                        timeMillis = null
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                    }
                    "name" -> if (trackPoints != null && !inTrkpt) {
                        trackName = parser.nextText().trim().ifBlank { null }
                    }
                    "ele" -> if (inTrkpt) ele = parser.nextText().trim().toDoubleOrNull()
                    "time" -> if (inTrkpt) timeMillis = parseTime(parser.nextText().trim())
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "trkpt" -> {
                        inTrkpt = false
                        trackPoints?.add(ParsedTrackPoint(lat, lon, ele, timeMillis))
                    }
                    "trk" -> {
                        val pts = trackPoints
                        if (pts != null && pts.isNotEmpty()) tracks.add(ParsedTrack(trackName, pts.toList()))
                        trackPoints = null
                        trackName = null
                    }
                }
            }
            event = parser.next()
        }
        return tracks
    }

    /** Parses an ISO-8601 GPX `<time>` (with or without milliseconds) to epoch ms. */
    private fun parseTime(raw: String): Long? {
        if (raw.isBlank()) return null
        for (pattern in TIME_PATTERNS) {
            runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return fmt.parse(raw)?.time
            }
        }
        return null
    }

    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    )
}

