package de.velospot.core.gpx

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Validates a GPX document string before it is shared or saved, so a malformed file
 * is never handed to the user (who couldn't open it elsewhere).
 *
 * Uses the JVM/Android XML DOM parser, so it catches **any** well-formedness error
 * (illegal characters, unbalanced tags, bad encoding) exactly like the external
 * readers (Garmin Connect, Strava, …) would, and additionally requires the document
 * to actually contain track points. Pure and unit-testable.
 */
object GpxValidator {

    data class Result(val wellFormed: Boolean, val trackPointCount: Int) {
        /** A file is exportable only when it parses **and** carries at least one point. */
        val isValid: Boolean get() = wellFormed && trackPointCount > 0
    }

    /** Parses [gpx] and reports whether it is well-formed and how many `<trkpt>`s it has. */
    fun validate(gpx: String): Result = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // We only ever parse our own output; block DOCTYPE/external entities anyway.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(gpx.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        val rootIsGpx = root != null && (root.localName == "gpx" || root.tagName == "gpx")
        val trkpts = doc.getElementsByTagNameNS("*", "trkpt").length
        Result(wellFormed = rootIsGpx, trackPointCount = trkpts)
    }.getOrElse { Result(wellFormed = false, trackPointCount = 0) }
}

