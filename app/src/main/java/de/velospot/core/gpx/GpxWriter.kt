package de.velospot.core.gpx

import de.velospot.domain.model.RecordedRide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Serialises [RecordedRide]s into a **GPX 1.1** document.
 *
 * Each ride becomes one `<trk>` (track) with a single `<trkseg>`; every captured
 * [de.velospot.domain.model.TrackPoint] is a `<trkpt>` carrying its WGS84
 * coordinate, optional elevation and an ISO-8601 UTC timestamp. Passing several
 * rides yields one document with several `<trk>` elements (the "combine into one
 * file" export option); passing a single-element list yields a one-track file.
 *
 * Pure and side-effect free so it can be unit-tested without Android.
 */
object GpxWriter {

    private const val CREATOR = "VeloSpot"

    /** Builds a GPX 1.1 document string containing every ride in [rides]. */
    fun write(rides: List<RecordedRide>): String {
        val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder(1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<gpx version=\"1.1\" creator=\"").append(CREATOR).append("\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 " +
                "http://www.topografix.com/GPX/1/1/gpx.xsd\">\n"
        )
        rides.firstOrNull()?.let {
            sb.append("  <metadata><time>").append(isoUtc.format(Date(it.startedAt)))
                .append("</time></metadata>\n")
        }
        for (ride in rides) {
            sb.append("  <trk>\n")
            ride.name?.let { sanitizeText(it) }?.takeIf { it.isNotBlank() }?.let {
                sb.append("    <name>").append(escape(it)).append("</name>\n")
            }
            // Standard GPX activity type (matches what Garmin & co. emit).
            sb.append("    <type>cycling</type>\n")
            sb.append("    <trkseg>\n")
            for (p in ride.points) {
                sb.append("      <trkpt lat=\"").append(coord(p.latitude))
                    .append("\" lon=\"").append(coord(p.longitude)).append("\">\n")
                p.altitudeMeters?.let {
                    sb.append("        <ele>").append(elevation(it)).append("</ele>\n")
                }
                sb.append("        <time>").append(isoUtc.format(Date(p.timestamp)))
                    .append("</time>\n")
                sb.append("      </trkpt>\n")
            }
            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * Formats a WGS84 coordinate as a fixed 7-decimal (≈ 1 cm) plain-decimal string
     * using a dot separator. Avoids `Double.toString` artefacts (long float tails or
     * scientific notation like `1.0E-4`) that some strict GPX readers reject.
     */
    private fun coord(value: Double): String = String.format(Locale.US, "%.7f", value)

    /** Formats an elevation in metres to one decimal place, dot-separated. */
    private fun elevation(value: Double): String = String.format(Locale.US, "%.1f", value)

    /**
     * Drops characters that are **illegal in XML 1.0** (control chars below 0x20
     * except tab/newline/CR). A single such character in a ride name would otherwise
     * make the whole file unparseable in *every* GPX reader.
     */
    private fun sanitizeText(value: String): String = buildString(value.length) {
        for (c in value) if (c == '\t' || c == '\n' || c == '\r' || c.code >= 0x20) append(c)
    }

    /** Escapes the five XML predefined entities for safe inclusion in text/attributes. */
    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

