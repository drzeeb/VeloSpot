package de.velospot.core.tracking

import android.content.Context
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Verifies the crash-recovery streaming of an in-progress recording:
 * begin → append → writeMeta → recover round-trips the partial ride, the
 * too-short guards mirror the tracker's, and clear / a missing session yield
 * nothing.
 */
class RideRecordingPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun persistence(): RideRecordingPersistence {
        val ctx = mock<Context>()
        whenever(ctx.filesDir).thenReturn(tmp.root)
        return RideRecordingPersistence(ctx)
    }

    @Test
    fun `recover returns null when there is no session`() {
        assertFalse(persistence().hasActiveSession())
        assertNull(persistence().recover())
    }

    @Test
    fun `recover rebuilds the ride from streamed points and meta`() {
        val p = persistence()
        p.begin(1_000L)
        p.appendPoint(TrackPoint(50.0000, 8.0000, 1_000L, 5f, 100.0, 4f))
        p.appendPoint(TrackPoint(50.0010, 8.0010, 4_000L, 6f, 102.0, 4f))
        p.writeMeta(
            startedAt = 1_000L,
            distanceMeters = 140.0,
            movingSeconds = 3,
            maxSpeedMps = 6.0,
            elevationGain = 2.0,
            elevationLoss = 0.0,
            name = "Evening loop"
        )

        assertTrue(p.hasActiveSession())
        val ride = p.recover()
        assertNotNull(ride)
        ride!!
        assertEquals(2, ride.points.size)
        assertEquals(1_000L, ride.startedAt)
        assertEquals(4_000L, ride.endedAt)
        assertEquals(140.0, ride.distanceMeters, 1e-6)
        assertEquals(3L, ride.movingSeconds)
        assertEquals(6.0, ride.maxSpeedMps, 1e-6)
        assertEquals(2.0, ride.elevationGainMeters, 1e-6)
        assertEquals("Evening loop", ride.name)
        assertFalse(ride.isMock)
        assertEquals(50.0010, ride.points[1].latitude, 1e-9)
    }

    @Test
    fun `recover returns null for too few points`() {
        val p = persistence()
        p.begin(1_000L)
        p.appendPoint(TrackPoint(50.0, 8.0, 1_000L, null, null, null))
        p.writeMeta(1_000L, 999.0, 0, 0.0, 0.0, 0.0)
        assertNull(p.recover())
    }

    @Test
    fun `recover returns null when the ride is too short in distance`() {
        val p = persistence()
        p.begin(1_000L)
        p.appendPoint(TrackPoint(50.0, 8.0, 1_000L, null, null, null))
        p.appendPoint(TrackPoint(50.00001, 8.00001, 2_000L, null, null, null))
        p.writeMeta(1_000L, 5.0, 0, 0.0, 0.0, 0.0)
        assertNull(p.recover())
    }

    @Test
    fun `null sensor fields round-trip through the stream`() {
        val p = persistence()
        p.begin(1_000L)
        p.appendPoint(TrackPoint(50.0, 8.0, 1_000L, null, null, null))
        p.appendPoint(TrackPoint(50.01, 8.01, 5_000L, null, null, null))
        p.writeMeta(1_000L, 100.0, 4, 0.0, 0.0, 0.0)

        val ride = p.recover()!!
        assertNull(ride.points[0].speedMps)
        assertNull(ride.points[0].altitudeMeters)
        assertNull(ride.points[0].accuracyMeters)
    }

    @Test
    fun `clear removes the session`() {
        val p = persistence()
        p.begin(1L)
        p.appendPoint(TrackPoint(1.0, 2.0, 1L))
        assertTrue(p.hasActiveSession())

        p.clear()
        assertFalse(p.hasActiveSession())
        assertNull(p.recover())
    }

    @Test
    fun `begin truncates a previous session`() {
        val p = persistence()
        p.begin(1L)
        p.appendPoint(TrackPoint(1.0, 2.0, 1L))
        p.appendPoint(TrackPoint(1.1, 2.1, 2L))
        // A new session starts fresh — the old points must not leak in.
        p.begin(10L)
        p.appendPoint(TrackPoint(3.0, 4.0, 10L))
        p.appendPoint(TrackPoint(3.5, 4.5, 20L))
        p.writeMeta(10L, 100.0, 5, 0.0, 0.0, 0.0)

        val ride = p.recover()!!
        assertEquals(2, ride.points.size)
        assertEquals(3.0, ride.points[0].latitude, 1e-9)
    }
}

