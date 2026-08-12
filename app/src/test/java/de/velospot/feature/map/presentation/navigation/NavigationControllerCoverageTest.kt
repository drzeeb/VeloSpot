package de.velospot.feature.map.presentation.navigation

import de.velospot.core.navigation.NavigationProgress
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RoutingFailedException
import de.velospot.feature.map.presentation.NavigationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extra coverage for [NavigationController]: start/reroute/cancel/error paths. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationControllerCoverageTest {

    private val route = BikeRoute(
        points = listOf(RoutePoint(49.75, 6.64), RoutePoint(49.76, 6.65)),
        distanceMeters = 1_000.0,
        durationSeconds = 360.0
    )

    private class Callbacks {
        var started = 0
        var stopped = 0
        var rerouted = 0
        var arrivedDestination = 0
        var arrivedParking = 0
    }

    private fun controller(
        scope: CoroutineScope,
        location: GeoCoordinate? = GeoCoordinate(49.75, 6.64),
        routeError: Throwable? = null,
        cb: Callbacks = Callbacks(),
    ) = NavigationController(
        scope = scope,
        routingRepository = object : de.velospot.domain.repository.RoutingRepository {
            override suspend fun getBikeRoute(from: GeoCoordinate, to: GeoCoordinate): BikeRoute {
                routeError?.let { throw it }; return route
            }
            override suspend fun getBikeRouteVia(waypoints: List<GeoCoordinate>): BikeRoute {
                routeError?.let { throw it }; return route
            }
            override suspend fun getRoundTrip(from: GeoCoordinate, targetDistanceMeters: Double): BikeRoute {
                routeError?.let { throw it }; return route
            }
        },
        currentLocation = { location },
        customPinDestinationId = "custom",
        syntheticDestinationIds = setOf("saved"),
        onSimulatedFix = {},
        onArrivedAtParkingSpot = { _, _ -> cb.arrivedParking++ },
        onArrivedAtDestination = { cb.arrivedDestination++ },
        onNavigationStarted = { cb.started++ },
        onNavigationStopped = { cb.stopped++ },
        onRerouted = { cb.rerouted++ },
        onCustomPinNavigationEnded = {},
    )

    private fun space(id: String) = BikeParkingSpace(
        id = id, latitude = 49.76, longitude = 6.65, type = BikeParkingType.BIKE_RACK,
        capacity = null, name = "Dest", address = null, isCovered = null,
        imageUrl = null, operator = null, sourceLayer = "layer"
    )

    @Test
    fun `start computes an active route and signals started`() = runTest {
        val cb = Callbacks()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope, cb = cb)

        c.start(space("rack"))
        advanceUntilIdle()

        assertTrue(c.uiState.value is NavigationUiState.Active)
        assertEquals(route, c.activeRoute)
        assertEquals(1, cb.started)
        assertTrue(c.isActive)
    }

    @Test
    fun `start without a location surfaces LocationUnavailable`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope, location = null)
        c.start(space("rack"))
        assertEquals(NavigationUiState.Error(MapError.LocationUnavailable), c.uiState.value)
    }

    @Test
    fun `startVia routes through the waypoints`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        c.startVia(space("saved"), listOf(GeoCoordinate(49.755, 6.645), GeoCoordinate(49.76, 6.65)))
        advanceUntilIdle()
        assertTrue(c.uiState.value is NavigationUiState.Active)
    }

    @Test
    fun `startAlong rides the given route without re-routing`() = runTest {
        val cb = Callbacks()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        // A distinct precomputed route the controller must use verbatim (the fake
        // routing repository would return a different `route`, so any re-routing
        // would surface here).
        val precomputed = BikeRoute(
            points = listOf(RoutePoint(49.70, 6.60), RoutePoint(49.71, 6.61), RoutePoint(49.72, 6.62)),
            distanceMeters = 2_345.0,
            durationSeconds = 500.0
        )
        val c = controller(scope, cb = cb)
        c.startAlong(space("saved"), precomputed)
        advanceUntilIdle()
        assertTrue(c.uiState.value is NavigationUiState.Active)
        assertEquals(precomputed, c.activeRoute)
        assertEquals(1, cb.started)
    }

    @Test
    fun `start maps a routing failure to an error state`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope, routeError = RoutingFailedException("nope"))
        c.start(space("rack"))
        advanceUntilIdle()
        assertEquals(NavigationUiState.Error(MapError.RoutingFailed("nope")), c.uiState.value)

        c.clearError()
        assertEquals(NavigationUiState.Idle, c.uiState.value)
    }

    @Test
    fun `showError surfaces an external error`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        c.showError(MapError.NoInternetConnection)
        assertEquals(NavigationUiState.Error(MapError.NoInternetConnection), c.uiState.value)
    }

    @Test
    fun `stop returns to idle and signals stopped`() = runTest {
        val cb = Callbacks()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope, cb = cb)
        c.start(space("rack"))
        advanceUntilIdle()

        c.stop()
        assertEquals(NavigationUiState.Idle, c.uiState.value)
        assertEquals(1, cb.stopped)
    }

    @Test
    fun `off-route triggers a silent reroute`() = runTest {
        val cb = Callbacks()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope, cb = cb)
        c.start(space("rack"))
        advanceUntilIdle()

        c.onUserWentOffRoute()
        advanceUntilIdle()

        assertTrue(c.uiState.value is NavigationUiState.Active)
        assertEquals(1, cb.rerouted)
    }

    @Test
    fun `arrival at a synthetic destination ends navigation without parking`() = runTest {
        val cb = Callbacks()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope, cb = cb)
        c.startVia(space("saved"), listOf(GeoCoordinate(49.76, 6.65)))
        advanceUntilIdle()

        val onRoute = NavigationProgress(
            remainingMeters = 5.0, remainingSeconds = 1.0,
            distanceFromRouteMeters = 1.0, isOffRoute = false
        )
        c.updateProgress(onRoute)
        c.updateProgress(onRoute)
        advanceUntilIdle()

        assertEquals(NavigationUiState.Idle, c.uiState.value)
        assertEquals(1, cb.arrivedDestination)
        assertEquals(0, cb.arrivedParking)
    }

    @Test
    fun `cancelRouteCalculation returns to idle from loading`() = runTest {
        // A never-completing route keeps the controller in Loading so cancel is exercised.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = NavigationController(
            scope = scope,
            routingRepository = object : de.velospot.domain.repository.RoutingRepository {
                override suspend fun getBikeRoute(from: GeoCoordinate, to: GeoCoordinate): BikeRoute {
                    kotlinx.coroutines.awaitCancellation()
                }
                override suspend fun getBikeRouteVia(waypoints: List<GeoCoordinate>): BikeRoute =
                    kotlinx.coroutines.awaitCancellation()
                override suspend fun getRoundTrip(from: GeoCoordinate, targetDistanceMeters: Double): BikeRoute =
                    kotlinx.coroutines.awaitCancellation()
            },
            currentLocation = { GeoCoordinate(49.75, 6.64) },
            customPinDestinationId = "custom",
            syntheticDestinationIds = setOf("saved"),
            onSimulatedFix = {},
            onArrivedAtParkingSpot = { _, _ -> },
            onArrivedAtDestination = {},
            onNavigationStarted = {},
            onNavigationStopped = {},
            onRerouted = {},
            onCustomPinNavigationEnded = {},
        )
        c.start(space("rack"))
        assertTrue(c.uiState.value is NavigationUiState.Loading)

        c.cancelRouteCalculation()
        assertEquals(NavigationUiState.Idle, c.uiState.value)
    }
}

