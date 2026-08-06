package de.velospot.core.location

import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.repository.LocationPowerProfile
import de.velospot.domain.repository.LocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the three-state GPS power derivation in [LocationController]:
 * recording+moving → high accuracy, recording+stationary → the idle power-saving
 * profile, navigation always high, plain map browsing unchanged, and that the
 * request is only re-issued on real transitions.
 */
class LocationControllerTest {

    /** Records every start/stop request so the derivation can be asserted in order. */
    private class RecordingRepo : LocationRepository {
        val starts = mutableListOf<LocationPowerProfile>()
        var stops = 0
        override fun getCurrentLocationFlow(): Flow<GeoCoordinate?> = emptyFlow()
        override fun startLocationUpdates(profile: LocationPowerProfile) { starts += profile }
        override fun stopLocationUpdates() { stops++ }
    }

    @Test
    fun `map browsing uses the balanced-power browse profile`() {
        val repo = RecordingRepo()
        val controller = LocationController(repo)

        controller.setMapVisible(true)

        assertEquals(listOf(LocationPowerProfile.BROWSE), repo.starts)
    }

    @Test
    fun `navigation always forces high accuracy regardless of movement`() {
        val repo = RecordingRepo()
        val controller = LocationController(repo)

        controller.setMapVisible(true)              // run the radio (browse)
        controller.setNavigating(true)              // navigation forces high accuracy
        // A stationary flag must not downgrade navigation.
        controller.setRecordingStationary(true)

        assertEquals(
            "navigation stays high accuracy",
            listOf(LocationPowerProfile.BROWSE, LocationPowerProfile.NAVIGATION_OR_MOVING),
            repo.starts
        )
    }

    @Test
    fun `recording while moving is high accuracy, stationary drops to idle profile`() {
        val repo = RecordingRepo()
        val controller = LocationController(repo)

        controller.setRecording(true)               // moving by default → high accuracy
        controller.setRecordingStationary(true)     // sustained stop → idle profile
        controller.setRecordingStationary(false)    // moves again → back to high accuracy

        assertEquals(
            listOf(
                LocationPowerProfile.NAVIGATION_OR_MOVING,
                LocationPowerProfile.IDLE_RECORDING,
                LocationPowerProfile.NAVIGATION_OR_MOVING,
            ),
            repo.starts
        )
    }

    @Test
    fun `navigation overrides a stationary recording`() {
        val repo = RecordingRepo()
        val controller = LocationController(repo)

        controller.setRecording(true)
        controller.setRecordingStationary(true)     // idle profile
        controller.setNavigating(true)              // navigation forces high again

        assertEquals(
            listOf(
                LocationPowerProfile.NAVIGATION_OR_MOVING,
                LocationPowerProfile.IDLE_RECORDING,
                LocationPowerProfile.NAVIGATION_OR_MOVING,
            ),
            repo.starts
        )
    }

    @Test
    fun `apply only re-requests on real transitions`() {
        val repo = RecordingRepo()
        val controller = LocationController(repo)

        controller.setRecording(true)
        // Repeated stationary=true must not spam the radio with identical requests.
        controller.setRecordingStationary(true)
        controller.setRecordingStationary(true)
        controller.setRecordingStationary(true)

        assertEquals(
            listOf(
                LocationPowerProfile.NAVIGATION_OR_MOVING,
                LocationPowerProfile.IDLE_RECORDING,
            ),
            repo.starts
        )
    }

    @Test
    fun `stopping a recording stops updates and clears the stationary claim`() {
        val repo = RecordingRepo()
        val controller = LocationController(repo)

        controller.setRecording(true)
        controller.setRecordingStationary(true)
        controller.setRecording(false)             // map not visible → stop

        assertEquals(1, repo.stops)
        // A fresh recording opens at high accuracy (stationary was cleared on stop).
        controller.setRecording(true)
        assertEquals(LocationPowerProfile.NAVIGATION_OR_MOVING, repo.starts.last())
    }
}

