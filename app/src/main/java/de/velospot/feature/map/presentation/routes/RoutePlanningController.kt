package de.velospot.feature.map.presentation.routes

import de.velospot.core.analysis.RouteGeometryStats
import de.velospot.core.analysis.RouteLeaderboard
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RouteAttempt
import de.velospot.domain.model.RouteWaypoint
import de.velospot.domain.repository.PlannedRoutesRepository
import de.velospot.domain.repository.RoutingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** The direction a planned route is being ridden in (drives the leaderboard split). */
enum class RideDirection { FORWARD, REVERSE }

/**
 * The context of an in-progress "ride this planned route" session. Held while the
 * rider navigates a saved route so the auto-recorded ride can be turned into a
 * leaderboard attempt the moment it finishes.
 */
private data class PendingAttempt(val routeId: String, val reversed: Boolean)

/**
 * Owns the **route planning + leaderboard** concern:
 *  - building a multi-waypoint route by dropping stops (planning mode),
 *  - the live preview polyline / stats computed for the current waypoints,
 *  - persisting a planned route and listing saved routes,
 *  - the forward / reverse leaderboards of a route,
 *  - turning a finished ride of a route into a leaderboard attempt.
 *
 * Kept unaware of navigation, the camera and BikeParkingSpace: those are wired via
 * the host [MapViewModel], which starts the actual navigation and forwards finished
 * rides through [onRideFinished].
 */
class RoutePlanningController(
    private val scope: CoroutineScope,
    private val repository: PlannedRoutesRepository,
    private val routingRepository: RoutingRepository,
) {
    // ── Planning mode ──────────────────────────────────────────────────────────

    private val _isPlanning = MutableStateFlow(false)
    val isPlanning: StateFlow<Boolean> = _isPlanning.asStateFlow()

    private val _waypoints = MutableStateFlow<List<RouteWaypoint>>(emptyList())
    val waypoints: StateFlow<List<RouteWaypoint>> = _waypoints.asStateFlow()

    /** The routed preview for the current waypoints (≥ 2), or `null` while < 2 / failed. */
    private val _previewRoute = MutableStateFlow<BikeRoute?>(null)
    val previewRoute: StateFlow<BikeRoute?> = _previewRoute.asStateFlow()

    private val _isComputingPreview = MutableStateFlow(false)
    val isComputingPreview: StateFlow<Boolean> = _isComputingPreview.asStateFlow()

    private var previewJob: Job? = null

    // ── Saved routes + leaderboard ──────────────────────────────────────────────

    private val _plannedRoutes = MutableStateFlow<List<PlannedRoute>>(emptyList())
    val plannedRoutes: StateFlow<List<PlannedRoute>> = _plannedRoutes.asStateFlow()

    /** The route whose leaderboard sheet is currently open (null when none). */
    private val _leaderboardRoute = MutableStateFlow<PlannedRoute?>(null)
    val leaderboardRoute: StateFlow<PlannedRoute?> = _leaderboardRoute.asStateFlow()

    /** Attempts of the open leaderboard route (both directions), fastest first. */
    private val _attempts = MutableStateFlow<List<RouteAttempt>>(emptyList())
    val attempts: StateFlow<List<RouteAttempt>> = _attempts.asStateFlow()

    private var attemptsJob: Job? = null

    private var pending: PendingAttempt? = null

    init {
        scope.launch {
            repository.getRoutesFlow().collect { _plannedRoutes.value = it }
        }
    }

    // ── Planning ────────────────────────────────────────────────────────────────

    /** Enters planning mode with an empty waypoint list. */
    fun startPlanning() {
        _isPlanning.value = true
        _waypoints.value = emptyList()
        _previewRoute.value = null
    }

    /** Leaves planning mode, discarding the current (unsaved) waypoints. */
    fun cancelPlanning() {
        previewJob?.cancel()
        _isPlanning.value = false
        _waypoints.value = emptyList()
        _previewRoute.value = null
        _isComputingPreview.value = false
    }

    /** Appends a tapped stop and recomputes the preview. Ignored outside planning. */
    fun addWaypoint(latitude: Double, longitude: Double, label: String? = null) {
        if (!_isPlanning.value) return
        _waypoints.update { it + RouteWaypoint(latitude, longitude, label) }
        recomputePreview()
    }

    /**
     * Sets a human-readable [label] on the most recently added stop (once its
     * reverse-geocoded name resolves). No-op when there is no waypoint or the label
     * is blank; does not recompute the preview (the geometry is unchanged).
     */
    fun labelLastWaypoint(label: String?) {
        val clean = label?.trim()?.takeIf { it.isNotBlank() } ?: return
        val current = _waypoints.value
        if (current.isEmpty()) return
        _waypoints.value = current.toMutableList().also { list ->
            val last = list.removeAt(list.lastIndex)
            list.add(last.copy(label = clean))
        }
    }

    /** Removes the last dropped stop and recomputes the preview. */
    fun undoLastWaypoint() {
        if (_waypoints.value.isEmpty()) return
        _waypoints.update { it.dropLast(1) }
        recomputePreview()
    }

    /** Removes the stop at [index] and recomputes the preview. */
    fun removeWaypointAt(index: Int) {
        val current = _waypoints.value
        if (index !in current.indices) return
        _waypoints.value = current.toMutableList().also { it.removeAt(index) }
        recomputePreview()
    }

    private fun recomputePreview() {
        previewJob?.cancel()
        val stops = _waypoints.value
        if (stops.size < 2) {
            _previewRoute.value = null
            _isComputingPreview.value = false
            return
        }
        _isComputingPreview.value = true
        previewJob = scope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val coords = stops.map { GeoCoordinate(it.latitude, it.longitude) }
            val route = runCatching { routingRepository.getBikeRouteVia(coords) }.getOrNull()
            _previewRoute.value = route
            _isComputingPreview.value = false
        }
    }

    /**
     * Persists the current planning session as a named [PlannedRoute] and leaves
     * planning mode. Returns `false` (and keeps planning) when there is no valid
     * preview yet (fewer than two stops or the routing failed).
     */
    fun saveRoute(name: String): Boolean {
        val route = _previewRoute.value ?: return false
        val stops = _waypoints.value
        if (stops.size < 2 || route.points.isEmpty()) return false
        val (gain, loss) = RouteGeometryStats.elevationGainLoss(route.points)
        val planned = PlannedRoute(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { DEFAULT_NAME },
            waypoints = stops,
            geometry = route.points,
            distanceMeters = route.distanceMeters,
            elevationGainMeters = gain,
            elevationLossMeters = loss,
            energyJoules = route.energyJoules,
            createdAt = System.currentTimeMillis()
        )
        scope.launch { repository.saveRoute(planned) }
        cancelPlanning()
        return true
    }

    // ── Saved-route management ──────────────────────────────────────────────────

    fun renameRoute(id: String, name: String) {
        val trimmed = name.trim().ifBlank { return }
        scope.launch { repository.renameRoute(id, trimmed) }
    }

    fun deleteRoute(id: String) {
        if (_leaderboardRoute.value?.id == id) closeLeaderboard()
        scope.launch { repository.deleteRoute(id) }
    }

    // ── Leaderboard ─────────────────────────────────────────────────────────────

    /** Opens a route's leaderboard and starts observing its attempts. */
    fun openLeaderboard(route: PlannedRoute) {
        _leaderboardRoute.value = route
        attemptsJob?.cancel()
        _attempts.value = emptyList()
        attemptsJob = scope.launch {
            repository.getAttemptsFlow(route.id).collect { _attempts.value = it }
        }
    }

    fun closeLeaderboard() {
        attemptsJob?.cancel()
        attemptsJob = null
        _leaderboardRoute.value = null
        _attempts.value = emptyList()
    }

    fun deleteAttempt(id: String) {
        scope.launch { repository.deleteAttempt(id) }
    }

    // ── Riding a planned route ──────────────────────────────────────────────────

    /**
     * Prepares to ride [route] in [direction]: arms the pending-attempt context so
     * the auto-recorded ride becomes a leaderboard entry on finish, and returns the
     * ordered stop coordinates the host should navigate through (reversed for a
     * backwards ride). Returns `null` if the route has fewer than two stops.
     */
    fun beginRide(route: PlannedRoute, direction: RideDirection): List<GeoCoordinate>? {
        if (route.waypoints.size < 2) return null
        val ordered = if (direction == RideDirection.REVERSE) route.waypoints.reversed() else route.waypoints
        pending = PendingAttempt(routeId = route.id, reversed = direction == RideDirection.REVERSE)
        return ordered.map { GeoCoordinate(it.latitude, it.longitude) }
    }

    /** Clears an armed pending-ride context without recording an attempt. */
    fun cancelPendingRide() {
        pending = null
    }

    /**
     * Called by the host when *any* ride finishes and is saved. When a planned-route
     * ride was armed via [beginRide], the ride is converted into a leaderboard
     * attempt (forward or reverse) and persisted. Mock rides are ignored.
     */
    fun onRideFinished(ride: RecordedRide) {
        val context = pending ?: return
        pending = null
        val attempt = RouteLeaderboard.attemptFromRide(
            routeId = context.routeId,
            reversed = context.reversed,
            ride = ride
        ) ?: return
        scope.launch { repository.addAttempt(attempt) }
    }

    companion object {
        private const val PREVIEW_DEBOUNCE_MS = 250L
        private const val DEFAULT_NAME = "Route"
    }
}



