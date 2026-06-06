package de.velospot.data.remote.parser

import android.util.Xml
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import javax.inject.Inject

class BikeParkingGmlParser @Inject constructor() {

    fun parse(
        xml: String,
        sourceLayer: String,
        type: BikeParkingType
    ): List<BikeParkingSpace> {
        val parser = Xml.newPullParser().apply {
            setInput(StringReader(xml))
        }

        val result = mutableListOf<BikeParkingSpace>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.localNameSafe().startsWith("fahrrad")) {
                parseFeature(parser, sourceLayer, type)?.let(result::add)
            }
            eventType = parser.next()
        }
        return result
    }

    private fun parseFeature(
        parser: XmlPullParser,
        sourceLayer: String,
        type: BikeParkingType
    ): BikeParkingSpace? {
        val featureTag = parser.localNameSafe()
        val gmlId = parser.getAttributeValue(null, "id") ?: parser.getAttributeValue("http://www.opengis.net/gml/3.2", "id")

        var gid: String? = null
        var name: String? = null
        var streetAndNo: String? = null
        var cityAndZip: String? = null
        var description: String? = null
        var latitude: Double? = null
        var longitude: Double? = null

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.localNameSafe() == featureTag)) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.localNameSafe()) {
                    "gid" -> gid = parser.readTextValue()
                    "bez" -> name = parser.readTextValue()
                    "str_hsnr" -> streetAndNo = parser.readTextValue()
                    "plz_ort" -> cityAndZip = parser.readTextValue()
                    "beschr" -> description = parser.readTextValue()
                    "pos" -> {
                        val parts = parser.readTextValue().split(' ').filter { it.isNotBlank() }
                        if (parts.size >= 2) {
                            // WFS liefert hier EPSG:4326 in der Reihenfolge lat lon.
                            latitude = parts[0].toDoubleOrNull()
                            longitude = parts[1].toDoubleOrNull()
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val lat = latitude ?: return null
        val lon = longitude ?: return null
        val address = listOfNotNull(streetAndNo, cityAndZip).joinToString(", ").ifBlank { null }
        val decodedDescription = description.orEmpty().decodeHtmlEntities()

        return BikeParkingSpace(
            id = gmlId ?: gid ?: "$sourceLayer-$lat-$lon",
            latitude = lat,
            longitude = lon,
            type = type,
            capacity = decodedDescription.extractCapacity(),
            name = name,
            address = address,
            isCovered = decodedDescription.toCoveredFlag(),
            operator = null,
            sourceLayer = sourceLayer
        )
    }

    private fun XmlPullParser.localNameSafe(): String {
        return (name ?: "").substringAfter(':')
    }

    private fun XmlPullParser.readTextValue(): String {
        val text = nextText()
        return text.trim()
    }

    private fun String.extractCapacity(): Int? {
        val patterns = listOf(
            Regex("Stellpl[aä]tze\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\d+)\\s+Fahrradstellpl[aä]tze", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    private fun String.toCoveredFlag(): Boolean? {
        val normalized = lowercase()
        return when {
            "überdacht" in normalized || "ueberdacht" in normalized -> true
            "nicht überdacht" in normalized || "nicht ueberdacht" in normalized -> false
            else -> null
        }
    }

    private fun String.decodeHtmlEntities(): String {
        return this
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace("&auml;", "ä")
            .replace("&ouml;", "ö")
            .replace("&uuml;", "ü")
            .replace("&Auml;", "Ä")
            .replace("&Ouml;", "Ö")
            .replace("&Uuml;", "Ü")
            .replace("&szlig;", "ß")
            .replace("<br />", " ")
            .replace("<br/>", " ")
            .replace("<br>", " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

