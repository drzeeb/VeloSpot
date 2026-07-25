package de.velospot.core.share

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure document-building logic of [GpxExporter]. */
class GpxExporterTest {

    private fun ride(id: String, name: String?, startedAt: Long = 1_700_000_000_000L) = RecordedRide(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + 60,
        distanceMeters = 100.0,
        elapsedSeconds = 60,
        movingSeconds = 60,
        avgSpeedMps = 1.6,
        maxSpeedMps = 2.0,
        elevationGainMeters = 1.0,
        elevationLossMeters = 1.0,
        points = listOf(
            TrackPoint(49.75, 6.64, startedAt),
            TrackPoint(49.76, 6.65, startedAt + 30),
        ),
        name = name,
    )

    @Test
    fun `no rides yields no documents`() {
        assertTrue(GpxExporter.buildDocuments(emptyList(), combineIntoSingleFile = false, combinedFileName = "x").isEmpty())
    }

    @Test
    fun `single ride yields one document named after the ride`() {
        val docs = GpxExporter.buildDocuments(listOf(ride("r1", "My Ride")), combineIntoSingleFile = false, combinedFileName = "ignored")
        assertEquals(1, docs.size)
        assertEquals("My Ride.gpx", docs.first().fileName)
        assertTrue(docs.first().content.contains("<gpx"))
    }

    @Test
    fun `combine into single file uses the combined name`() {
        val docs = GpxExporter.buildDocuments(
            listOf(ride("r1", "A"), ride("r2", "B")),
            combineIntoSingleFile = true,
            combinedFileName = "All rides",
        )
        assertEquals(1, docs.size)
        assertEquals("All rides.gpx", docs.first().fileName)
    }

    @Test
    fun `separate export yields one document per ride`() {
        val docs = GpxExporter.buildDocuments(
            listOf(ride("r1", "Morning"), ride("r2", "Evening")),
            combineIntoSingleFile = false,
            combinedFileName = "ignored",
        )
        assertEquals(2, docs.size)
        assertEquals(setOf("Morning.gpx", "Evening.gpx"), docs.map { it.fileName }.toSet())
    }

    @Test
    fun `duplicate names are disambiguated within the batch`() {
        val docs = GpxExporter.buildDocuments(
            listOf(ride("r1", "Trip"), ride("r2", "Trip"), ride("r3", "Trip")),
            combineIntoSingleFile = false,
            combinedFileName = "ignored",
        )
        assertEquals(listOf("Trip.gpx", "Trip-2.gpx", "Trip-3.gpx"), docs.map { it.fileName })
    }

    @Test
    fun `filesystem-unsafe characters are sanitised`() {
        val docs = GpxExporter.buildDocuments(listOf(ride("r1", "a/b:c*?\"<>|d")), combineIntoSingleFile = false, combinedFileName = "x")
        val name = docs.first().fileName
        assertTrue("was: $name", name.endsWith(".gpx"))
        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
    }

    @Test
    fun `blank ride name falls back to a date-stamped name`() {
        val docs = GpxExporter.buildDocuments(listOf(ride("r1", "   ")), combineIntoSingleFile = false, combinedFileName = "x")
        assertTrue(docs.first().fileName.startsWith("VeloSpot-"))
        assertTrue(docs.first().fileName.endsWith(".gpx"))
    }

    private fun assertFalse(condition: Boolean) = assertTrue(!condition)
    private fun assertFalse(message: String, condition: Boolean) = assertTrue(message, !condition)
}

