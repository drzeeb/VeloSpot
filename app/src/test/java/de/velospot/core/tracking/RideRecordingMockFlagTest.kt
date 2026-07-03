package de.velospot.core.tracking

import android.content.Context
import de.velospot.core.location.LocationController
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Locks in the mock-flag decoupling behind problem #1: a **normal navigation**
 * brakes its puck with a synthetic speed-0 fix fed through [RideRecordingManager.feedExternal]
 * when it ends — that must **never** save the real ride as a mock. Only an explicit
 * [RideRecordingManager.markMockRecording] (raised when the debug route simulator
 * actually starts) flags the ride.
 *
 * The manager stamps each fix with the wall clock, so the helper feeds a handful of
 * plausibly-spaced fixes with short real pauses to clear the tracker's too-short and
 * teleport guards. Uses an unconfined scope so the feed + save run synchronously.
 */
class RideRecordingMockFlagTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(onSaved: (RecordedRide) -> Unit): RideRecordingManager {
        val ctx = mock<Context> { whenever(it.filesDir).doReturn(tmp.root) }
        val locationRepo = mock<LocationRepository> {
            whenever(it.getCurrentLocationFlow()).doReturn(emptyFlow())
        }
        val location = LocationController(locationRepo)
        val repo = mock<RecordedRidesRepository>()
        val repoCapturing = object : RecordedRidesRepository by repo {
            override suspend fun saveRide(ride: RecordedRide) { onSaved(ride) }
        }
        return RideRecordingManager(
            context = ctx,
            locationController = location,
            recordedRidesRepository = repoCapturing,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    /**
     * Feeds four fixes ~12 m apart with ~1.2 s real pauses (≈10 m/s — a plausible
     * bike speed), clearing the ≥20 m / ≥2-point too-short guards and the teleport
     * gate so the ride actually persists on [RideRecordingManager.stop].
     */
    private fun feedPlausibleRide(m: RideRecordingManager) {
        val stepLon = 0.00017 // ≈12.2 m at lat 50°
        repeat(4) { i ->
            m.feedExternal(
                GeoCoordinate(
                    latitude = 50.0000,
                    longitude = 8.0000 + stepLon * i,
                    speedMetersPerSecond = 10f
                )
            )
            if (i < 3) Thread.sleep(1_200)
        }
    }

    @Test
    fun `external fixes alone do not flag the ride as mock`() {
        var saved: RecordedRide? = null
        val m = manager { saved = it }
        m.start()
        feedPlausibleRide(m)
        m.stop()
        assertNotNull("ride should have been saved", saved)
        assertFalse("a real navigation must not be flagged mock", saved!!.isMock)
    }

    @Test
    fun `markMockRecording flags the ride as mock`() {
        var saved: RecordedRide? = null
        val m = manager { saved = it }
        m.start()
        m.markMockRecording()
        feedPlausibleRide(m)
        m.stop()
        assertNotNull("ride should have been saved", saved)
        assertTrue("a simulator ride must be flagged mock", saved!!.isMock)
    }
}





