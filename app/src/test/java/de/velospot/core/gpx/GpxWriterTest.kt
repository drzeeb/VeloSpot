package de.velospot.core.gpx

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxWriterTest {

    private fun ride(name: String?, points: List<TrackPoint>) = RecordedRide(
        id = "id", startedAt = 0L, endedAt = 10_000L,
        distanceMeters = 0.0, elapsedSeconds = 10, movingSeconds = 10,
        avgSpeedMps = 0.0, maxSpeedMps = 0.0,
        elevationGainMeters = 0.0, elevationLossMeters = 0.0,
        points = points, name = name
    )

    @Test
    fun `writes a valid gpx skeleton with one track per ride`() {
        val gpx = GpxWriter.write(
            listOf(
                ride("Trier", listOf(TrackPoint(49.75, 6.64, 0L, 5f, 130.0))),
                ride("Koblenz", listOf(TrackPoint(50.35, 7.59, 1_000L, null, null)))
            )
        )
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
        // One <trk> per ride.
        assertEquals(2, Regex("<trk>").findAll(gpx).count())
        assertTrue(gpx.contains("<name>Trier</name>"))
        assertTrue(gpx.contains("<name>Koblenz</name>"))
    }

    @Test
    fun `emits coordinates, elevation and iso time for points`() {
        val gpx = GpxWriter.write(listOf(ride("R", listOf(TrackPoint(49.75, 6.64, 0L, 5f, 130.0)))))
        // Coordinates are fixed-7-decimal, dot-separated (no scientific notation).
        assertTrue(gpx.contains("lat=\"49.7500000\""))
        assertTrue(gpx.contains("lon=\"6.6400000\""))
        assertTrue(gpx.contains("<ele>130.0</ele>"))
        // Epoch 0 in UTC.
        assertTrue(gpx.contains("<time>1970-01-01T00:00:00Z</time>"))
    }

    @Test
    fun `output is always well-formed and validatable`() {
        val gpx = GpxWriter.write(listOf(ride("Trier", listOf(TrackPoint(49.75, 6.64, 0L, 5f, 130.0)))))
        val result = GpxValidator.validate(gpx)
        assertTrue(result.wellFormed)
        assertEquals(1, result.trackPointCount)
        assertTrue(result.isValid)
    }

    @Test
    fun `strips xml-illegal control characters from the name`() {
        // A NUL/control char in the name would make the file unparseable everywhere.
        val gpx = GpxWriter.write(listOf(ride("Bad\u0000Na\u0007me", listOf(TrackPoint(0.0, 0.0, 0L, null, null)))))
        assertTrue("control chars must be stripped", !gpx.contains("\u0000") && !gpx.contains("\u0007"))
        assertTrue("file still parses", GpxValidator.validate(gpx).wellFormed)
        assertTrue(gpx.contains("<name>BadName</name>"))
    }

    @Test
    fun `omits elevation when missing and escapes xml in names`() {
        val gpx = GpxWriter.write(listOf(ride("A & B <x>", listOf(TrackPoint(0.0, 0.0, 0L, null, null)))))
        assertTrue(!gpx.contains("<ele>"))
        assertTrue(gpx.contains("<name>A &amp; B &lt;x&gt;</name>"))
    }
}

