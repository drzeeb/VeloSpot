package de.velospot.core.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxValidatorTest {

    private val validGpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="VeloSpot" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>R</name><trkseg>
            <trkpt lat="49.75" lon="6.64"><ele>130.0</ele><time>1970-01-01T00:00:00Z</time></trkpt>
            <trkpt lat="49.76" lon="6.65"><time>1970-01-01T00:00:01Z</time></trkpt>
          </trkseg></trk>
        </gpx>
    """.trimIndent()

    @Test
    fun `valid gpx with track points passes`() {
        val result = GpxValidator.validate(validGpx)
        assertTrue(result.wellFormed)
        assertEquals(2, result.trackPointCount)
        assertTrue(result.isValid)
    }

    @Test
    fun `malformed xml fails`() {
        val broken = "<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\"></trkseg></gpx>" // unclosed trkpt
        val result = GpxValidator.validate(broken)
        assertFalse(result.wellFormed)
        assertFalse(result.isValid)
    }

    @Test
    fun `well-formed gpx without track points is not valid`() {
        val empty = "<?xml version=\"1.0\"?><gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\"></gpx>"
        val result = GpxValidator.validate(empty)
        assertTrue(result.wellFormed)
        assertEquals(0, result.trackPointCount)
        assertFalse(result.isValid)
    }

    @Test
    fun `illegal control character makes it invalid`() {
        val withCtrl = "<?xml version=\"1.0\"?><gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>a\u0000b</name></trk></gpx>"
        assertFalse(GpxValidator.validate(withCtrl).wellFormed)
    }
}

