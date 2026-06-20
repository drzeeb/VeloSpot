package de.velospot.feature.map.presentation.navigation

import de.velospot.core.navigation.NavigationProgress
import de.velospot.core.navigation.RouteSimulator
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.BRouterProfilesMissingException
import de.velospot.domain.model.EmptyRouteGeometryException
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoRouteFoundException
import de.velospot.domain.model.RoutingFailedException
import de.velospot.domain.repository.RoutingRepository
import de.velospot.feature.map.presentation.NavigationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the in-app turn-by-turn navigation concern: route calculation, the live
 * navigation/progress state, automatic off-route rerouting, auto-park on arrival
 * and the debug GPS route simulator. Extracted from `MapViewModel` so the most
 * entangled feature lives behind a focused, testable API.
 *
 * It deliberately knows nothing about ride tracking, the location-power strategy,
 * the parked bike or the custom pin — those cross-feature effects are delivered
 * through callbacks:
 *  - [onNavigationStarted] / [onNavigationStopped] / [onRerouted] let the host
 *    react to lifecycle transitions (e.g. auto-record a ride, adjust GPS accuracy),
 *  - [onArrivedAtParkingSpot] parks the bike when a genuine spot is reached,
 *  - [onCustomPinNavigationEnded] cleans up a transient custom-pin destination,
 *  - [onSimulatedFix] feeds a synthetic GPS fix back into the location pipeline.
 */
class NavigationController(
    private val scope: CoroutineScope,
    private val routingRepository: RoutingRepository,
    private val currentLocation: () -> GeoCoordinate?,
    private val customPinDestinationId: String,
    private val syntheticDestinationIds: Set<String>,
    private val onSimulatedFix: (GeoCoordinate) -> Unit,
    private val onArrivedAtParkingSpot: (Double, Double) -> Unit,
    private val onNavigationStarted: () -> Unit,
    private val onNavigationStopped: () -> Unit,
    private val onRerouted: () -> Unit,
    private val onCustomPinNavigationEnded: () -> Unit,
) {
    private val _uiState = MutableStateFlow<NavigationUiState>(NavigationUiState.Idle)
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    /**
     * Live route progress (remaining distance + ETA) pushed from the UI layer's
     * `NavigationManager.onProgress` callback; `null` when not navigating.
     */
    private val _progress = MutableStateFlow<NavigationProgress?>(null)
    val progress: StateFlow<NavigationProgress?> = _progress.asStateFlow()

    // ── GPS route simulator (debug couch-testing) ─────────────────────────────
    private val routeSimulator = RouteSimulator()
    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

    /** Active route calculation job — cancelled immediately when a new navigation starts. */
    private var navigationJob: Job? = null

    /** Timestamp (ms) of the last off-route reroute, used to throttle reroute spam. */
    private var lastRerouteAtMs = 0L

    /**
     * Guards against repeatedly auto-parking once the rider has reached a parking
     * spot. Reset whenever a fresh navigation starts.
     */
    private var hasAutoParkedForCurrentRoute = false

    val isActive: Boolean get() = _uiState.value is NavigationUiState.Active
    val activeRoute: BikeRoute? get() = (_uiState.value as? NavigationUiState.Active)?.route
    val activeDestination: BikeParkingSpace? get() = (_uiState.value as? NavigationUiState.Active)?.destination

    /** Pushed from the UI layer's `NavigationManager.onProgress` callback. */
    fun updateProgress(progress: NavigationProgress) {
        _progress.value = progress
        maybeAutoParkOnArrival(progress)
    }

    /**
     * Auto-parks the bike the moment the rider arrives at a real bike parking spot.
     * Once the remaining distance drops below [ARRIVAL_THRESHOLD_METERS] for a
     * genuine parking-spot destination, the bike is parked at the spot and
     * navigation ends — so the persistent marker is dropped without any extra tap.
     * Synthetic destinations (custom pin, address search, saved place, parked bike)
     * never auto-park.
     */
    private fun maybeAutoParkOnArrival(progress: NavigationProgress) {
        if (hasAutoParkedForCurrentRoute) return
        val destination = activeDestination ?: return
        if (destination.id in syntheticDestinationIds) return
        if (progress.isOffRoute) return
        if (progress.remainingMeters > ARRIVAL_THRESHOLD_METERS) return

        hasAutoParkedForCurrentRoute = true
        onArrivedAtParkingSpot(destination.latitude, destination.longitude)
        stop()
    }

    fun start(space: BikeParkingSpace) {
        val location = currentLocation() ?: run {
            _uiState.value = NavigationUiState.Error(MapError.LocationUnavailable)
            return
        }
        // Cancel any in-progress calculation — e.g. when the user taps a different
        // parking spot while a route is still being computed.
        navigationJob?.cancel()
        _uiState.value = NavigationUiState.Loading
        _progress.value = null
        hasAutoParkedForCurrentRoute = false
        navigationJob = scope.launch {
            runCatching {
                routingRepository.getBikeRoute(
                    from = location,
                    to   = GeoCoordinate(space.latitude, space.longitude)
                )
            }.onSuccess { route ->
                _uiState.value = NavigationUiState.Active(destination = space, route = route)
                onNavigationStarted()
            }.onFailure { throwable ->
                _uiState.value = NavigationUiState.Error(mapRoutingError(throwable))
            }
        }
    }

    fun stop() {
        navigationJob?.cancel()
        navigationJob = null
        stopSimulation()
        val wasCustomPin = activeDestination?.id == customPinDestinationId
        _uiState.value = NavigationUiState.Idle
        _progress.value = null
        if (wasCustomPin) onCustomPinNavigationEnded()
        onNavigationStopped()
    }

    /**
     * Called by the `NavigationManager` when the rider has strayed off-route.
     * Recomputes a fresh route from the current GPS position to the same
     * destination and swaps it in **without** flashing the loading state, so the
     * 3D view keeps running. Throttled so a brief detour does not spawn a flurry of
     * recalculations.
     */
    fun onUserWentOffRoute() {
        val active = _uiState.value as? NavigationUiState.Active ?: return
        val location = currentLocation() ?: return
        val now = System.currentTimeMillis()
        if (now - lastRerouteAtMs < REROUTE_COOLDOWN_MS) return
        lastRerouteAtMs = now

        navigationJob?.cancel()
        navigationJob = scope.launch {
            runCatching {
                routingRepository.getBikeRoute(
                    from = location,
                    to   = GeoCoordinate(active.destination.latitude, active.destination.longitude)
                )
            }.onSuccess { newRoute ->
                // Only apply if we're still navigating to the same destination.
                val current = _uiState.value as? NavigationUiState.Active
                if (current?.destination?.id == active.destination.id) {
                    _uiState.value = NavigationUiState.Active(active.destination, newRoute)
                    _progress.value = null
                    onRerouted()
                }
            }
            // On failure we silently keep the existing route; the next off-route
            // window (after the cooldown) will try again.
        }
    }

    fun clearError() {
        if (_uiState.value is NavigationUiState.Error) _uiState.value = NavigationUiState.Idle
    }

    /** Surfaces an externally-produced error (e.g. a failed offline-segment download). */
    fun showError(error: MapError) {
        _uiState.value = NavigationUiState.Error(error)
    }

    /**
     * Starts/stops the GPS mock simulator. When running it walks along the active
     * route, feeding synthetic fixes (with bearing + speed) back through
     * [onSimulatedFix] exactly like real GPS — so the whole live-navigation
     * pipeline can be tested on the couch.
     */
    fun toggleSimulation() {
        if (_isSimulating.value) {
            stopSimulation()
            return
        }
        val route = activeRoute ?: return
        if (route.points.size < 2) return

        _isSimulating.value = true
        routeSimulator.start(
            scope = scope,
            route = route.points,
            // Brisk couch-test pace (~50 km/h), emitting twice a second so the
            // motion stays smooth despite the higher speed.
            speedMps = 13.9,
            intervalMs = 500L,
            jitterMeters = 0.0,
            onFix = { fix -> onSimulatedFix(fix) },
            onFinished = { _isSimulating.value = false }
        )
    }

    fun stopSimulation() {
        routeSimulator.stop()
        _isSimulating.value = false
    }

    /** Stops the simulator; call from the host's `onCleared`. */
    fun dispose() {
        routeSimulator.stop()
    }

    private fun mapRoutingError(throwable: Throwable): MapError = when (throwable) {
        is BRouterProfilesMissingException -> MapError.BRouterProfilesMissing
        is RoutingFailedException          -> MapError.RoutingFailed(throwable.code)
        is NoRouteFoundException           -> MapError.NoRouteFound
        is EmptyRouteGeometryException     -> MapError.EmptyRouteGeometry
        else                               -> MapError.Unknown(throwable.message)
    }

    companion object {
        /**
         * Remaining route distance (m) below which the rider counts as "arrived".
         * Reaching this radius at a real bike parking spot auto-parks the bike.
         */
        private const val ARRIVAL_THRESHOLD_METERS = 25.0

        /** Minimum gap between automatic off-route reroutes. */
        private const val REROUTE_COOLDOWN_MS = 8_000L
    }
}

