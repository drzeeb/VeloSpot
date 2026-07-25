package de.velospot.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.velospot.data.local.dao.PlannedRouteDao
import de.velospot.data.local.dao.RouteAttemptDao
import de.velospot.data.local.entity.PlannedRouteEntity
import de.velospot.data.local.entity.RouteAttemptEntity
import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.RouteAttempt
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RouteWaypoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlannedRoutesRepositoryImpl] with in-memory fake DAOs and the
 * production Moshi setup (waypoint/geometry JSON round-trips end to end).
 */
class PlannedRoutesRepositoryImplTest {

    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private class FakePlannedRouteDao : PlannedRouteDao {
        val store = MutableStateFlow<List<PlannedRouteEntity>>(emptyList())
        override fun getAllFlow(): Flow<List<PlannedRouteEntity>> =
            store.map { it.sortedByDescending { e -> e.createdAt } }
        override suspend fun upsert(route: PlannedRouteEntity) {
            store.value = store.value.filterNot { it.id == route.id } + route
        }
        override suspend fun updateName(id: String, name: String) {
            store.value = store.value.map { if (it.id == id) it.copy(name = name) else it }
        }
        override suspend fun delete(id: String) { store.value = store.value.filterNot { it.id == id } }
    }

    private class FakeRouteAttemptDao : RouteAttemptDao {
        val store = MutableStateFlow<List<RouteAttemptEntity>>(emptyList())
        override fun getAttemptsFlow(routeId: String): Flow<List<RouteAttemptEntity>> =
            store.map { list -> list.filter { it.routeId == routeId }.sortedBy { it.elapsedSeconds } }
        override suspend fun upsert(attempt: RouteAttemptEntity) {
            store.value = store.value.filterNot { it.id == attempt.id } + attempt
        }
        override suspend fun delete(id: String) { store.value = store.value.filterNot { it.id == id } }
        override suspend fun deleteForRoute(routeId: String) {
            store.value = store.value.filterNot { it.routeId == routeId }
        }
    }

    private fun route(id: String, name: String = "Tour $id", createdAt: Long = 1_000L) = PlannedRoute(
        id = id,
        name = name,
        waypoints = listOf(
            RouteWaypoint(49.75, 6.64, "Start"),
            RouteWaypoint(49.80, 6.70, null),
        ),
        geometry = listOf(RoutePoint(49.75, 6.64), RoutePoint(49.78, 6.67), RoutePoint(49.80, 6.70)),
        distanceMeters = 5_000.0,
        elevationGainMeters = 80.0,
        elevationLossMeters = 60.0,
        energyJoules = 120_000.0,
        createdAt = createdAt,
    )

    private fun attempt(id: String, routeId: String, elapsed: Long) = RouteAttempt(
        id = id,
        routeId = routeId,
        reversed = false,
        recordedAt = 10L,
        elapsedSeconds = elapsed,
        movingSeconds = elapsed,
        distanceMeters = 5_000.0,
        avgSpeedMps = 5.0,
        maxSpeedMps = 9.0,
        elevationGainMeters = 80.0,
        rideId = "ride-$id",
    )

    private fun repo(
        routeDao: PlannedRouteDao = FakePlannedRouteDao(),
        attemptDao: RouteAttemptDao = FakeRouteAttemptDao(),
    ) = PlannedRoutesRepositoryImpl(routeDao, attemptDao, moshi)

    @Test
    fun `saveRoute then flow round-trips waypoints and geometry`() = runTest {
        val repo = repo()
        repo.saveRoute(route("r1"))

        val loaded = repo.getRoutesFlow().first().single()
        assertEquals("r1", loaded.id)
        assertEquals(2, loaded.waypoints.size)
        assertEquals("Start", loaded.waypoints.first().label)
        assertEquals(3, loaded.geometry.size)
        assertEquals(49.80, loaded.geometry.last().latitude, 0.0)
        assertEquals(120_000.0, loaded.energyJoules!!, 0.0)
    }

    @Test
    fun `routes flow is newest first`() = runTest {
        val repo = repo()
        repo.saveRoute(route("old", createdAt = 1_000L))
        repo.saveRoute(route("new", createdAt = 5_000L))
        assertEquals(listOf("new", "old"), repo.getRoutesFlow().first().map { it.id })
    }

    @Test
    fun `renameRoute updates the name`() = runTest {
        val repo = repo()
        repo.saveRoute(route("r1", name = "Old"))
        repo.renameRoute("r1", "New name")
        assertEquals("New name", repo.getRoutesFlow().first().single().name)
    }

    @Test
    fun `deleteRoute removes the route and all its attempts`() = runTest {
        val attemptDao = FakeRouteAttemptDao()
        val repo = repo(attemptDao = attemptDao)
        repo.saveRoute(route("r1"))
        repo.addAttempt(attempt("a1", "r1", elapsed = 100))
        repo.addAttempt(attempt("a2", "r1", elapsed = 90))

        repo.deleteRoute("r1")

        assertTrue(repo.getRoutesFlow().first().isEmpty())
        assertTrue(repo.getAttemptsFlow("r1").first().isEmpty())
    }

    @Test
    fun `attempts flow is fastest first and scoped to the route`() = runTest {
        val repo = repo()
        repo.saveRoute(route("r1"))
        repo.addAttempt(attempt("slow", "r1", elapsed = 300))
        repo.addAttempt(attempt("fast", "r1", elapsed = 100))
        repo.addAttempt(attempt("other", "r2", elapsed = 50))

        val attempts = repo.getAttemptsFlow("r1").first()
        assertEquals(listOf("fast", "slow"), attempts.map { it.id })
    }

    @Test
    fun `deleteAttempt removes a single attempt`() = runTest {
        val repo = repo()
        repo.saveRoute(route("r1"))
        repo.addAttempt(attempt("a1", "r1", elapsed = 100))
        repo.addAttempt(attempt("a2", "r1", elapsed = 200))

        repo.deleteAttempt("a1")

        assertEquals(listOf("a2"), repo.getAttemptsFlow("r1").first().map { it.id })
    }
}

