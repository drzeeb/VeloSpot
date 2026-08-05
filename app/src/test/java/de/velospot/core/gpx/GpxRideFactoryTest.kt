package de.velospot.core.gpx

import de.velospot.core.tracking.AltitudeSample
import de.velospot.core.tracking.ElevationAccumulator
import de.velospot.testsupport.ElevationFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxRideFactoryTest {

    /** ~111.32 m per 0.001° of latitude — handy for round distances. */
    @Test
    fun `builds a ride with distance, duration and name from a timed track`() {
        val track = ParsedTrack(
            name = "Trier",
            points = listOf(
                ParsedTrackPoint(0.0, 0.0, 100.0, 0L),
                ParsedTrackPoint(0.001, 0.0, 110.0, 10_000L) // ~111 m in 10 s
            )
        )
        val ride = GpxRideFactory.toRecordedRide(track)
        assertNotNull(ride)
        ride!!
        assertEquals("Trier", ride.name)
        assertTrue("distance ~111 m", ride.distanceMeters in 100.0..125.0)
        assertEquals(10L, ride.elapsedSeconds)
        // ~11 m/s over the 10 s segment.
        assertTrue(ride.maxSpeedMps in 9.0..13.0)
    }

    @Test
    fun `rejects a track that is too short`() {
        val track = ParsedTrack(
            name = null,
            points = listOf(
                ParsedTrackPoint(0.0, 0.0, null, 0L),
                ParsedTrackPoint(0.00001, 0.0, null, 1_000L) // ~1 m
            )
        )
        assertNull(GpxRideFactory.toRecordedRide(track))
    }

    @Test
    fun `handles a track without timestamps (no duration, distance still counted)`() {
        val track = ParsedTrack(
            name = null,
            points = listOf(
                ParsedTrackPoint(0.0, 0.0, null, null),
                ParsedTrackPoint(0.001, 0.0, null, null)
            )
        )
        val ride = GpxRideFactory.toRecordedRide(track)
        assertNotNull(ride)
        ride!!
        assertEquals(0L, ride.elapsedSeconds)
        assertEquals(0.0, ride.maxSpeedMps, 0.0)
        assertTrue(ride.distanceMeters > 100.0)
    }

    @Test
    fun `elevation matches the shared accumulator for a real altitude profile`() {
        // GPX import must derive gain/loss through the SAME shared integrator as the
        // live recorder, so a track carrying a known altitude profile yields exactly
        // ElevationAccumulator.compute() on the same altitudes. This keeps RideTracker
        // and GpxRideFactory consistent (the whole point of the shared accumulator).
        val altitudes = ElevationFixtures.NET_DESCENT_ABF570DF
        var lat = 0.0
        val points = altitudes.mapIndexed { i, alt ->
            val p = ParsedTrackPoint(lat, 0.0, alt, i * 3_000L)
            lat += 0.0003
            p
        }
        val ride = GpxRideFactory.toRecordedRide(ParsedTrack(name = "Profile", points = points))
        assertNotNull(ride)
        ride!!

        val expected = ElevationAccumulator.compute(altitudes.map { AltitudeSample(it) })
        assertEquals(expected.gainMeters, ride.elevationGainMeters, 1e-6)
        assertEquals(expected.lossMeters, ride.elevationLossMeters, 1e-6)
        // And the values are the physically sensible ones (not the old under-counts).
        assertTrue("loss realistic", ride.elevationLossMeters > 6.0)
        assertTrue("gain not zero-forced on a net descent", ride.elevationGainMeters > 3.0)
    }
}

