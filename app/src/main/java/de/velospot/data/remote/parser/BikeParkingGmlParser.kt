package de.velospot.data.remote.parser

import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.inject.Inject

class BikeParkingGmlParser @Inject constructor() {

    fun parse(
        xml: String,
        sourceLayer: String,
        type: BikeParkingType
    ): List<BikeParkingSpace> {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        return document
            .getElementsByTagName("*")
            .asElementSequence()
            .filter { element -> element.localNameSafe().startsWith("fahrrad") }
            .mapNotNull { feature -> parseFeature(feature, sourceLayer, type) }
            .toList()
    }

    private fun parseFeature(
        feature: Element,
        sourceLayer: String,
        type: BikeParkingType
    ): BikeParkingSpace? {
        val gmlId = feature.getAttribute("gml:id")
            .ifBlank { feature.getAttribute("id") }
            .ifBlank {
                feature.getAttributeNS("http://www.opengis.net/gml/3.2", "id")
            }
            .ifBlank { null }

        val gid = feature.findText("gid")
        val name = feature.findText("bez")
        val streetAndNo = feature.findText("str_hsnr")
        val cityAndZip = feature.findText("plz_ort")
        val description = feature.findText("beschr")
        val imageUrl = description.extractImageUrlFromEncodedDescription()
        val position = feature.findText("pos")
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val latitude = position.getOrNull(0)?.toDoubleOrNull()
        val longitude = position.getOrNull(1)?.toDoubleOrNull()
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
            isCovered = null,
            imageUrl = imageUrl,
            operator = null,
            sourceLayer = sourceLayer
        )
    }

    private fun org.w3c.dom.NodeList.asElementSequence(): Sequence<Element> = sequence {
        for (index in 0 until length) {
            val node = item(index)
            if (node is Element) {
                yield(node)
            }
        }
    }

    private fun Element.localNameSafe(): String {
        return localName ?: tagName.substringAfter(':')
    }

    private fun Element.findText(localName: String): String? {
        return getElementsByTagNameNS("*", localName)
            .asElementSequence()
            .firstOrNull()
            ?.textContent
            ?.trim()
            ?.ifBlank { null }
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

    private fun String?.extractImageUrlFromEncodedDescription(): String? {
        val raw = this ?: return null
        val encodedMatch = Regex("""src=&quot;(.+?)&quot;""", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
        val plainMatch = Regex("src=\"(.+?)\"", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)

        val candidate = (encodedMatch ?: plainMatch)
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.replace("&auml;", "ä")
            ?.replace("&ouml;", "ö")
            ?.replace("&uuml;", "ü")
            ?.replace("&Auml;", "Ä")
            ?.replace("&Ouml;", "Ö")
            ?.replace("&Uuml;", "Ü")
            ?.replace("&szlig;", "ß")
            ?.trim()

        return candidate?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
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

