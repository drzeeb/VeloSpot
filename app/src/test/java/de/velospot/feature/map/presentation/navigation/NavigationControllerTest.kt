package de.velospot.feature.map.presentation.navigation

import de.velospot.core.navigation.NavigationProgress
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RoutePoint
import de.velospot.feature.map.presentation.NavigationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
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

    @Test
    fun `round trip only ends after the rider departs the start and returns`() {
        // A round trip's synthetic destination sits on the start, so the crow-flies
        // distance to it is ~0 the instant navigation begins. Arrival must be held
        // back (the crow-flies fallback is disarmed) until the rider has genuinely
        // travelled away, then fire on return — for standard nav, routes and loops.
        var current = GeoCoordinate(latitude = 49.75, longitude = 6.64)
        val route = BikeRoute(
            points = listOf(RoutePoint(49.75, 6.64), RoutePoint(49.76, 6.65)),
            distanceMeters = 5_000.0,
            durationSeconds = 1_200.0
        )
        var arrivedCount = 0
        val controller = roundTripController(
            location = { current },
            route = route,
            onArrived = { arrivedCount++ }
        )
        val destination = roundTripSpace()

        controller.startRoundTrip(destination, targetDistanceMeters = 5_000.0)
        assertTrue(controller.uiState.value is NavigationUiState.Active)

        // At the start, off-route: crow-flies to the destination (== start) is ~0,
        // but the fallback is disarmed, so two fixes must NOT end the round trip.
        val atStart = offRouteProgress(remainingMeters = 5_000.0)
        controller.updateProgress(atStart)
        controller.updateProgress(atStart)
        assertTrue(controller.uiState.value is NavigationUiState.Active)
        assertEquals(0, arrivedCount)

        // Rider heads out on the loop (> arming radius) — this arms the fallback.
        current = GeoCoordinate(latitude = 49.756, longitude = 6.64)
        controller.updateProgress(offRouteProgress(remainingMeters = 2_500.0))
        assertTrue(controller.uiState.value is NavigationUiState.Active)

        // Rider returns to the start: two off-route fixes now register arrival.
        current = GeoCoordinate(latitude = 49.75, longitude = 6.64)
        controller.updateProgress(offRouteProgress(remainingMeters = 5.0))
        controller.updateProgress(offRouteProgress(remainingMeters = 5.0))
        assertEquals(NavigationUiState.Idle, controller.uiState.value)
        assertEquals(1, arrivedCount)
    }

    private fun roundTripController(
        location: () -> GeoCoordinate?,
        route: BikeRoute,
        onArrived: () -> Unit,
    ) = NavigationController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        routingRepository = mock { on { getRoundTrip(any(), any()) } doReturn route },
        currentLocation = location,
        customPinDestinationId = "custom",
        syntheticDestinationIds = setOf("roundtrip"),
        onSimulatedFix = {},
        onArrivedAtParkingSpot = { _, _ -> },
        onArrivedAtDestination = onArrived,
        onNavigationStarted = {},
        onNavigationStopped = {},
        onRerouted = {},
        onCustomPinNavigationEnded = {},
    )

    private fun offRouteProgress(remainingMeters: Double) = NavigationProgress(
        remainingMeters = remainingMeters,
        remainingSeconds = remainingMeters / 4.5,
        distanceFromRouteMeters = 40.0,
        isOffRoute = true
    )

    private fun roundTripSpace() = BikeParkingSpace(
        id = "roundtrip",
        latitude = 49.75,
        longitude = 6.64,
        type = BikeParkingType.UNKNOWN,
        capacity = null,
        name = null,
        address = null,
        isCovered = null,
        imageUrl = null,
        operator = null,
        sourceLayer = "round_trip"
    )
}

