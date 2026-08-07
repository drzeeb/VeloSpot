package de.velospot.core.gpx

import de.velospot.core.tracking.AltitudeSample
import de.velospot.core.tracking.ElevationAccumulator
import de.velospot.core.tracking.RideTracker
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
    fun `ignores a GPX segment above the shared plausibility ceiling for max speed`() {
        // The import ceiling is now shared with the live RideTracker: a segment
        // faster than ~27 m/s is treated as a bad timestamp/coordinate and must not
        // contribute to maxSpeed, while a plausible fast segment below it is kept.
        // ~111.32 m per 0.001 deg latitude.
        val track = ParsedTrack(
            name = null,
            points = listOf(
                ParsedTrackPoint(0.0, 0.0, null, 0L),
                // ~222.6 m in 10 s -> ~22.3 m/s (below ceiling -> kept)
                ParsedTrackPoint(0.002, 0.0, null, 10_000L),
                // ~333.9 m in 10 s -> ~33.4 m/s (above 27 m/s ceiling -> rejected;
                // the old 35 m/s ceiling would have wrongly accepted it)
                ParsedTrackPoint(0.005, 0.0, null, 20_000L)
            )
        )
        val ride = GpxRideFactory.toRecordedRide(track)
        assertNotNull(ride)
        ride!!
        assertTrue(
            "implausible >ceiling segment must not set maxSpeed",
            ride.maxSpeedMps < RideTracker.MAX_PLAUSIBLE_SPEED_MPS
        )
        assertTrue("kept segment ~22 m/s drives maxSpeed", ride.maxSpeedMps in 20.0..24.0)
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

    @Test
    fun `toRecordedRides maps every usable track and drops the too-short ones`() {
        // Two long tracks + one tiny one: the opened-GPX flow keeps a ride per usable
        // <trk> and preserves order (the first drives the preview), dropping shorts.
        val long1 = ParsedTrack(
            name = "Leg A",
            points = listOf(
                ParsedTrackPoint(0.0, 0.0, null, 0L),
                ParsedTrackPoint(0.001, 0.0, null, 10_000L)
            )
        )
        val tiny = ParsedTrack(
            name = "Tiny",
            points = listOf(
                ParsedTrackPoint(0.0, 0.0, null, 0L),
                ParsedTrackPoint(0.00001, 0.0, null, 1_000L) // ~1 m -> dropped
            )
        )
        val long2 = ParsedTrack(
            name = "Leg B",
            points = listOf(
                ParsedTrackPoint(1.0, 0.0, null, 0L),
                ParsedTrackPoint(1.001, 0.0, null, 10_000L)
            )
        )

        val rides = GpxRideFactory.toRecordedRides(listOf(long1, tiny, long2))

        assertEquals(2, rides.size)
        assertEquals("Leg A", rides[0].name)
        assertEquals("Leg B", rides[1].name)
    }

    @Test
    fun `toRecordedRides returns an empty list when no track is usable`() {
        val tiny = ParsedTrack(
            name = null,
            points = listOf(
                ParsedTrackPoint(0.0, 0.0, null, 0L),
                ParsedTrackPoint(0.00001, 0.0, null, 1_000L)
            )
        )
        assertTrue(GpxRideFactory.toRecordedRides(listOf(tiny)).isEmpty())
        assertTrue(GpxRideFactory.toRecordedRides(emptyList()).isEmpty())
    }
}

