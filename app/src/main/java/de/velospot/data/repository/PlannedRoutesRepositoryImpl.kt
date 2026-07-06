package de.velospot.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import de.velospot.data.local.dao.PlannedRouteDao
import de.velospot.data.local.dao.RouteAttemptDao
import de.velospot.data.local.entity.PlannedRouteEntity
import de.velospot.data.local.entity.RouteAttemptEntity
import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RouteAttempt
import de.velospot.domain.model.RouteWaypoint
import de.velospot.domain.repository.PlannedRoutesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [PlannedRoutesRepository].
 *
 * The waypoints and the routed polyline are serialised to compact JSON via the
 * shared [Moshi] so a route is self-contained; aggregate stats live in columns.
 */
@Singleton
class PlannedRoutesRepositoryImpl @Inject constructor(
    private val plannedRouteDao: PlannedRouteDao,
    private val routeAttemptDao: RouteAttemptDao,
    moshi: Moshi
) : PlannedRoutesRepository {

    private val waypointsAdapter = moshi.adapter<List<RouteWaypoint>>(
        Types.newParameterizedType(List::class.java, RouteWaypoint::class.java)
    )
    private val geometryAdapter = moshi.adapter<List<RoutePoint>>(
        Types.newParameterizedType(List::class.java, RoutePoint::class.java)
    )

    override fun getRoutesFlow(): Flow<List<PlannedRoute>> =
        plannedRouteDao.getAllFlow().map { entities -> entities.map { it.toDomain() } }

    override fun getAttemptsFlow(routeId: String): Flow<List<RouteAttempt>> =
        routeAttemptDao.getAttemptsFlow(routeId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun saveRoute(route: PlannedRoute) =
        plannedRouteDao.upsert(route.toEntity())

    override suspend fun renameRoute(id: String, name: String) =
        plannedRouteDao.updateName(id, name)

    override suspend fun deleteRoute(id: String) {
        routeAttemptDao.deleteForRoute(id)
        plannedRouteDao.delete(id)
    }

    override suspend fun addAttempt(attempt: RouteAttempt) =
        routeAttemptDao.upsert(attempt.toEntity())

    override suspend fun deleteAttempt(id: String) =
        routeAttemptDao.delete(id)

    // ── Mapping ────────────────────────────────────────────────────────────────

    private fun PlannedRouteEntity.toDomain() = PlannedRoute(
        id = id,
        name = name,
        waypoints = runCatching { waypointsAdapter.fromJson(waypointsJson) }.getOrNull().orEmpty(),
        geometry = runCatching { geometryAdapter.fromJson(geometryJson) }.getOrNull().orEmpty(),
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        energyJoules = energyJoules,
        createdAt = createdAt
    )

    private fun PlannedRoute.toEntity() = PlannedRouteEntity(
        id = id,
        name = name,
        waypointsJson = waypointsAdapter.toJson(waypoints),
        geometryJson = geometryAdapter.toJson(geometry),
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        energyJoules = energyJoules,
        createdAt = createdAt
    )

    private fun RouteAttemptEntity.toDomain() = RouteAttempt(
        id = id,
        routeId = routeId,
        reversed = reversed,
        recordedAt = recordedAt,
        elapsedSeconds = elapsedSeconds,
        movingSeconds = movingSeconds,
        distanceMeters = distanceMeters,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = elevationGainMeters,
        rideId = rideId
    )

    private fun RouteAttempt.toEntity() = RouteAttemptEntity(
        id = id,
        routeId = routeId,
        reversed = reversed,
        recordedAt = recordedAt,
        elapsedSeconds = elapsedSeconds,
        movingSeconds = movingSeconds,
        distanceMeters = distanceMeters,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = elevationGainMeters,
        rideId = rideId
    )
}

