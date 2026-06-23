package de.velospot.feature.map.presentation.navigation

import de.velospot.domain.model.GeoCoordinate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class NavigationControllerTest {

    private fun controller(
        currentLocation: () -> GeoCoordinate?,
        onSimulatedFix: (GeoCoordinate) -> Unit
    ) = NavigationController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        routingRepository = mock(),
        currentLocation = currentLocation,
        customPinDestinationId = "custom",
        syntheticDestinationIds = emptySet(),
        onSimulatedFix = onSimulatedFix,
        onArrivedAtParkingSpot = { _, _ -> },
        onArrivedAtDestination = {},
        onNavigationStarted = {},
        onNavigationStopped = {},
        onRerouted = {},
        onCustomPinNavigationEnded = {},
    )

    @Test
    fun `stopSimulation feeds a stationary fix so the puck stops coasting`() {
        // The simulator stops sending fixes, so without this the navigation puck would
        // keep dead-reckoning forward at the last simulated speed. stopSimulation must
        // feed one final speed-0 fix at the current position to brake the puck.
        val last = GeoCoordinate(
            latitude = 49.75, longitude = 6.64, bearing = 90f, speedMetersPerSecond = 13.9f
        )
        val emitted = mutableListOf<GeoCoordinate>()
        val controller = controller(currentLocation = { last }, onSimulatedFix = { emitted.add(it) })

        controller.stopSimulation()

        assertEquals("exactly one stationary fix is fed", 1, emitted.size)
        val fix = emitted.single()
        assertEquals(0f, fix.speedMetersPerSecond)
        assertEquals(last.latitude, fix.latitude, 0.0)
        assertEquals(last.longitude, fix.longitude, 0.0)
    }

    @Test
    fun `stopSimulation without a known location feeds nothing`() {
        val emitted = mutableListOf<GeoCoordinate>()
        val controller = controller(currentLocation = { null }, onSimulatedFix = { emitted.add(it) })

        controller.stopSimulation()

        assertTrue(emitted.isEmpty())
    }
}

