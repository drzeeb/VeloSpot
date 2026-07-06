package de.velospot.domain.repository

import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.RouteAttempt
import kotlinx.coroutines.flow.Flow

/**
 * Persists user-planned multi-waypoint routes and their leaderboard attempts.
 * Backed by a dedicated, isolated Room store so it never collides with the
 * bundled parking data or the other user stores.
 */
interface PlannedRoutesRepository {

    /** All saved planned routes, newest first. Updates reactively. */
    fun getRoutesFlow(): Flow<List<PlannedRoute>>

    /** Every attempt of [routeId] (both directions), fastest first. Reactive. */
    fun getAttemptsFlow(routeId: String): Flow<List<RouteAttempt>>

    suspend fun saveRoute(route: PlannedRoute)

    suspend fun renameRoute(id: String, name: String)

    /** Deletes a route **and** all of its recorded attempts. */
    suspend fun deleteRoute(id: String)

    suspend fun addAttempt(attempt: RouteAttempt)

    suspend fun deleteAttempt(id: String)
}

