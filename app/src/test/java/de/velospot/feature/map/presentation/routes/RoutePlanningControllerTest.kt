package de.velospot.feature.map.presentation.routes

import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RouteAttempt
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.TrackPoint
import de.velospot.domain.repository.PlannedRoutesRepository
import de.velospot.domain.repository.RoutingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [RoutePlanningController] with in-memory fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlanningControllerTest {

    private val route = BikeRoute(
        points = listOf(RoutePoint(49.75, 6.64), RoutePoint(49.76, 6.65)),
        distanceMeters = 1_200.0,
        durationSeconds = 400.0
    )

    private fun controller(scope: CoroutineScope, repo: FakeRoutes = FakeRoutes()) =
        RoutePlanningController(scope, repo, FakeRouting(route))

    @Test
    fun `planning drops waypoints and computes a preview at two stops`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)

        c.startPlanning()
        assertTrue(c.isPlanning.value)

        c.addWaypoint(49.75, 6.64)
        advanceUntilIdle()
        assertNull(c.previewRoute.value)  // one stop → no route yet

        c.addWaypoint(49.76, 6.65, label = "Stop 2")
        advanceUntilIdle()
        assertEquals(route, c.previewRoute.value)
        assertFalse(c.isComputingPreview.value)
        assertEquals(2, c.waypoints.value.size)
    }

    @Test
    fun `labelLastWaypoint relabels the most recent stop`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        c.startPlanning()
        c.addWaypoint(49.75, 6.64)
        c.labelLastWaypoint("  Home  ")
        assertEquals("Home", c.waypoints.value.last().label)
        // Blank label is ignored.
        c.labelLastWaypoint("   ")
        assertEquals("Home", c.waypoints.value.last().label)
    }

    @Test
    fun `undo and removeAt drop stops`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        c.startPlanning()
        c.addWaypoint(1.0, 1.0)
        c.addWaypoint(2.0, 2.0)
        c.addWaypoint(3.0, 3.0)
        advanceUntilIdle()

        c.undoLastWaypoint()
        assertEquals(2, c.waypoints.value.size)
        c.removeWaypointAt(0)
        advanceUntilIdle()
        assertEquals(1, c.waypoints.value.size)
        c.removeWaypointAt(9) // out of range → no-op
        assertEquals(1, c.waypoints.value.size)
    }

    @Test
    fun `saveRoute persists a valid route and leaves planning`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRoutes()
        val c = controller(scope, repo)
        c.startPlanning()
        c.addWaypoint(49.75, 6.64)
        c.addWaypoint(49.76, 6.65)
        advanceUntilIdle()

        assertTrue(c.saveRoute("Morning Loop"))
        advanceUntilIdle()

        assertFalse(c.isPlanning.value)
        assertEquals(1, repo.routes.value.size)
        assertEquals("Morning Loop", repo.routes.value.first().name)
    }

    @Test
    fun `saveRoute returns false without a valid preview`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        c.startPlanning()
        c.addWaypoint(49.75, 6.64) // only one stop → no preview
        advanceUntilIdle()
        assertFalse(c.saveRoute("x"))
    }

    @Test
    fun `cancelPlanning clears state`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        c.startPlanning()
        c.addWaypoint(1.0, 1.0)
        c.cancelPlanning()
        assertFalse(c.isPlanning.value)
        assertTrue(c.waypoints.value.isEmpty())
    }

    @Test
    fun `rename and delete route go through the repository`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRoutes()
        val c = controller(scope, repo)
        val planned = plannedRoute("r1")
        repo.saveRoute(planned)
        advanceUntilIdle()

        c.renameRoute("r1", "  Renamed ")
        advanceUntilIdle()
        assertEquals("Renamed", repo.routes.value.first().name)

        c.renameRoute("r1", "   ") // blank → ignored
        advanceUntilIdle()
        assertEquals("Renamed", repo.routes.value.first().name)

        c.openLeaderboard(planned)
        c.deleteRoute("r1")
        advanceUntilIdle()
        assertTrue(repo.routes.value.isEmpty())
        assertNull(c.leaderboardRoute.value) // open board of deleted route was closed
    }

    @Test
    fun `leaderboard open observes attempts and close resets`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRoutes()
        val c = controller(scope, repo)
        val planned = plannedRoute("r1")

        c.openLeaderboard(planned)
        repo.addAttempt(attempt("a1", "r1"))
        advanceUntilIdle()
        assertEquals(planned, c.leaderboardRoute.value)
        assertEquals(1, c.attempts.value.size)

        c.deleteAttempt("a1")
        advanceUntilIdle()
        assertTrue(c.attempts.value.isEmpty())

        c.closeLeaderboard()
        assertNull(c.leaderboardRoute.value)
    }

    @Test
    fun `preview on map summarises the route then closes`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRoutes()
        val c = controller(scope, repo)
        val planned = plannedRoute("r1")

        c.previewRouteOnMap(planned)
        advanceUntilIdle()
        assertEquals(planned, c.previewedRoute.value)

        c.closeRoutePreview()
        assertNull(c.previewedRoute.value)
    }

    @Test
    fun `saveRideAsRoute seeds the leaderboard and opens it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRoutes()
        val c = controller(scope, repo)

        assertTrue(c.saveRideAsRoute(sampleRide(), "Loop"))
        advanceUntilIdle()

        assertEquals(1, repo.routes.value.size)
        assertTrue(c.leaderboardRoute.value != null)
    }

    @Test
    fun `beginRide arms a pending attempt and onRideFinished records it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRoutes()
        val c = controller(scope, repo)
        val planned = plannedRoute("r1")

        // Fewer than two geometry points → null.
        assertNull(c.beginRide(planned.copy(geometry = planned.geometry.take(1)), RideDirection.FORWARD))

        // The ridable route follows the stored geometry, reversed for a backwards ride.
        val ridable = c.beginRide(planned, RideDirection.REVERSE)
        assertEquals(planned.geometry.reversed(), ridable!!.points)
        assertEquals(planned.distanceMeters, ridable.distanceMeters, 0.0)
        assertNull(ridable.cumulativeTimesSeconds)

        val routeId = c.onRideFinished(sampleRide())
        advanceUntilIdle()
        assertEquals("r1", routeId)
        assertEquals(1, repo.attempts.value.size)
        assertTrue(repo.attempts.value.first().reversed)
    }

    @Test
    fun `beginRide forward follows the stored geometry in order`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        val planned = plannedRoute("r1")

        val ridable = c.beginRide(planned, RideDirection.FORWARD)
        assertEquals(planned.geometry, ridable!!.points)
    }

    @Test
    fun `onRideFinished without an armed ride returns null`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        assertNull(c.onRideFinished(sampleRide()))
    }

    @Test
    fun `cancelPendingRide disarms the attempt`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        c.beginRide(plannedRoute("r1"), RideDirection.FORWARD)
        c.cancelPendingRide()
        assertNull(c.onRideFinished(sampleRide()))
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun plannedRoute(id: String) = PlannedRoute(
        id = id,
        name = "Route $id",
        waypoints = listOf(
            de.velospot.domain.model.RouteWaypoint(49.75, 6.64),
            de.velospot.domain.model.RouteWaypoint(49.76, 6.65)
        ),
        geometry = listOf(RoutePoint(49.75, 6.64), RoutePoint(49.76, 6.65)),
        distanceMeters = 1_000.0,
        elevationGainMeters = 0.0,
        elevationLossMeters = 0.0,
        createdAt = 1L
    )

    private fun attempt(id: String, routeId: String) = RouteAttempt(
        id = id,
        routeId = routeId,
        reversed = false,
        recordedAt = 1L,
        elapsedSeconds = 600L,
        movingSeconds = 560L,
        distanceMeters = 1_000.0,
        avgSpeedMps = 3.0,
        maxSpeedMps = 8.0,
        elevationGainMeters = 10.0
    )

    private fun sampleRide() = RecordedRide(
        id = "ride-1",
        startedAt = 0L,
        endedAt = 600_000L,
        distanceMeters = 2_000.0,
        elapsedSeconds = 600L,
        movingSeconds = 560L,
        avgSpeedMps = 3.5,
        maxSpeedMps = 8.0,
        elevationGainMeters = 40.0,
        elevationLossMeters = 35.0,
        points = listOf(
            TrackPoint(49.75, 6.64, 0L),
            TrackPoint(49.752, 6.64, 60_000L),
            TrackPoint(49.75, 6.64, 120_000L)
        ),
        name = "Evening loop"
    )
}

private class FakeRoutes : PlannedRoutesRepository {
    val routes = MutableStateFlow<List<PlannedRoute>>(emptyList())
    val attempts = MutableStateFlow<List<RouteAttempt>>(emptyList())

    override fun getRoutesFlow(): Flow<List<PlannedRoute>> = routes
    override fun getAttemptsFlow(routeId: String): Flow<List<RouteAttempt>> = attempts

    override suspend fun saveRoute(route: PlannedRoute) {
        routes.value = routes.value.filterNot { it.id == route.id } + route
    }

    override suspend fun renameRoute(id: String, name: String) {
        routes.value = routes.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun deleteRoute(id: String) {
        routes.value = routes.value.filterNot { it.id == id }
        attempts.value = attempts.value.filterNot { it.routeId == id }
    }

    override suspend fun addAttempt(attempt: RouteAttempt) {
        attempts.value = attempts.value + attempt
    }

    override suspend fun deleteAttempt(id: String) {
        attempts.value = attempts.value.filterNot { it.id == id }
    }
}

private class FakeRouting(private val route: BikeRoute) : RoutingRepository {
    override suspend fun getBikeRoute(from: GeoCoordinate, to: GeoCoordinate): BikeRoute = route
    override suspend fun getBikeRouteVia(waypoints: List<GeoCoordinate>): BikeRoute = route
    override suspend fun getRoundTrip(from: GeoCoordinate, targetDistanceMeters: Double): BikeRoute = route
}

