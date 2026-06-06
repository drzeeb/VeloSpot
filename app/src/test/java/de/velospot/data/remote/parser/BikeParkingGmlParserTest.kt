package de.velospot.data.remote.parser

import de.velospot.domain.model.BikeParkingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeParkingGmlParserTest {

    private val parser = BikeParkingGmlParser()

    @Test
    fun `parse extracts name address coordinates and capacity`() {
        val xml = """
            <wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0"
                                   xmlns:gml="http://www.opengis.net/gml/3.2"
                                   xmlns:ms="http://mapserver.gis.umn.edu/mapserver">
                <wfs:member>
                    <ms:fahrradabstellanlagen gml:id="rack-1">
                        <ms:gid>11</ms:gid>
                        <ms:bez>City Center Rack</ms:bez>
                        <ms:str_hsnr>Main Street 5</ms:str_hsnr>
                        <ms:plz_ort>54290 Trier</ms:plz_ort>
                        <ms:beschr>12 Fahrradstellplätze</ms:beschr>
                        <gml:pos>49.756 6.641</gml:pos>
                    </ms:fahrradabstellanlagen>
                </wfs:member>
            </wfs:FeatureCollection>
        """.trimIndent()

        val result = parser.parse(
            xml = xml,
            sourceLayer = "fahrradabstellanlagen",
            type = BikeParkingType.BIKE_RACK
        )

        assertEquals(1, result.size)
        val space = result.first()
        assertEquals("rack-1", space.id)
        assertEquals("City Center Rack", space.name)
        assertEquals("Main Street 5, 54290 Trier", space.address)
        assertEquals(49.756, space.latitude, 0.0)
        assertEquals(6.641, space.longitude, 0.0)
        assertEquals(12, space.capacity)
        assertEquals(null, space.isCovered)
        assertEquals(null, space.imageUrl)
        assertEquals(BikeParkingType.BIKE_RACK, space.type)
    }

    @Test
    fun `parse skips entries without coordinates`() {
        val xml = """
            <wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0"
                                   xmlns:ms="http://mapserver.gis.umn.edu/mapserver">
                <wfs:member>
                    <ms:fahrradgaragen>
                        <ms:bez>Broken Entry</ms:bez>
                    </ms:fahrradgaragen>
                </wfs:member>
            </wfs:FeatureCollection>
        """.trimIndent()

        val result = parser.parse(
            xml = xml,
            sourceLayer = "fahrradgaragen",
            type = BikeParkingType.GARAGE
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse decodes simple html entities in description and extracts image URL`() {
        val xml = """
            <wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0"
                                   xmlns:gml="http://www.opengis.net/gml/3.2"
                                   xmlns:ms="http://mapserver.gis.umn.edu/mapserver">
                <wfs:member>
                    <ms:fahrradgaragen gml:id="garage-1">
                        <ms:beschr>&lt;img src=&quot;https://geoportal.trier.de/images/fahrradabstellanlagen/Pfuetzenstra&amp;szlig;e_2.jpg&quot; /&gt;&lt;br /&gt;8 Fahrradstellpl&amp;auml;tze</ms:beschr>
                        <gml:pos>49.750 6.640</gml:pos>
                    </ms:fahrradgaragen>
                </wfs:member>
            </wfs:FeatureCollection>
        """.trimIndent()

        val result = parser.parse(
            xml = xml,
            sourceLayer = "fahrradgaragen",
            type = BikeParkingType.GARAGE
        )

        val space = result.firstOrNull()
        assertNotNull(space)
        assertEquals(8, space?.capacity)
        assertEquals(null, space?.isCovered)
        assertEquals(
            "https://geoportal.trier.de/images/fahrradabstellanlagen/Pfuetzenstraße_2.jpg",
            space?.imageUrl
        )
    }
}

