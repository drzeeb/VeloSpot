package de.velospot.feature.map.presentation.navigation

import de.velospot.core.navigation.GeoMath
import de.velospot.core.navigation.NavigationProgress
import de.velospot.core.navigation.RouteSimulator
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.BRouterProfilesMissingException
import de.velospot.domain.model.EmptyRouteGeometryException
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoRouteFoundException
import de.velospot.domain.model.RoutePoint
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
 *  - [onArrivedAtDestination] signals arrival at any (synthetic) destination so
 *    the host can show an "you've arrived" confirmation,
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
    /** Invoked the moment the debug route simulator actually starts driving. */
    private val onSimulationStarted: () -> Unit = {},
    private val onArrivedAtParkingSpot: (Double, Double) -> Unit,
    private val onArrivedAtDestination: () -> Unit,
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

    /**
     * The route the simulator's paused [RouteSimulator.travelledMeters] belongs to.
     * Resume only continues from the saved position while this is still the active
     * route instance; a new/rerouted route restarts the simulation from the start.
     */
    private var simulationRouteRef: List<RoutePoint>? = null

    /** Active route calculation job — cancelled immediately when a new navigation starts. */
    private var navigationJob: Job? = null

    /** Timestamp (ms) of the last off-route reroute, used to throttle reroute spam. */
    private var lastRerouteAtMs = 0L

    /**
     * Guards against repeatedly handling arrival once the rider has reached the
     * destination. Reset whenever a fresh navigation starts.
     */
    private var hasArrivedForCurrentRoute = false

    /**
     * Consecutive fixes seen within the arrival radius. Requiring a couple in a
     * row debounces a single noisy GPS sample that briefly snaps onto the
     * destination from far away.
     */
    private var consecutiveArrivalFixes = 0

    val isActive: Boolean get() = _uiState.value is NavigationUiState.Active
    val activeRoute: BikeRoute? get() = (_uiState.value as? NavigationUiState.Active)?.route
    val activeDestination: BikeParkingSpace? get() = (_uiState.value as? NavigationUiState.Active)?.destination

    /** Pushed from the UI layer's `NavigationManager.onProgress` callback. */
    fun updateProgress(progress: NavigationProgress) {
        _progress.value = progress
        maybeHandleArrival(progress)
    }

    /**
     * Ends navigation the moment the rider reaches the destination — for **every**
     * destination, not just bike parking spots. Arrival is detected robustly by
     * combining two independent signals, so the navigation reliably finishes even
     * when GPS is noisy or the BRouter route stops a few metres short of the door:
     *
     *  1. the along-route remaining distance dropping below [ARRIVAL_THRESHOLD_METERS]
     *     while on-route (precise as long as the rider follows the line), and
     *  2. a straight-line (crow-flies) distance from the raw GPS fix to the actual
     *     destination coordinate below [ARRIVAL_THRESHOLD_METERS] — this works even
     *     when the rider is slightly **off-route** at the destination (e.g. pulled
     *     onto the pavement), which previously suppressed arrival entirely.
     *
     * A short debounce ([ARRIVAL_CONSECUTIVE_FIXES]) rejects a single stray fix.
     * When the destination is a genuine bike parking spot the bike is auto-parked
     * at it; otherwise a generic "you've arrived" confirmation is surfaced.
     */
    private fun maybeHandleArrival(progress: NavigationProgress) {
        if (hasArrivedForCurrentRoute) return
        val destination = activeDestination ?: return

        if (!isWithinArrivalRadius(progress, destination)) {
            consecutiveArrivalFixes = 0
            return
        }

        consecutiveArrivalFixes++
        if (consecutiveArrivalFixes < ARRIVAL_CONSECUTIVE_FIXES) return

        hasArrivedForCurrentRoute = true
        if (destination.id in syntheticDestinationIds) {
            // Address search, saved place, parked bike, custom pin: no auto-park,
            // just confirm arrival and end navigation.
            onArrivedAtDestination()
        } else {
            // Genuine bike parking spot: park the bike at the destination.
            onArrivedAtParkingSpot(destination.latitude, destination.longitude)
        }
        stop()
    }

    /**
     * `true` once the rider is close enough to the [destination] to count as
     * arrived, combining the along-route remaining distance with a crow-flies
     * fallback so a few metres of off-route GPS noise at the destination still
     * registers (see [maybeHandleArrival]).
     */
    private fun isWithinArrivalRadius(
        progress: NavigationProgress,
        destination: BikeParkingSpace
    ): Boolean {
        // On-route: trust the precise along-route remaining distance.
        if (!progress.isOffRoute) {
            return progress.remainingMeters <= ARRIVAL_THRESHOLD_METERS
        }
        // Off-route (e.g. pulled onto the pavement at the door, or the route ends a
        // few metres short): fall back to the direct distance to the real
        // destination coordinate, independent of the route line — this used to
        // suppress arrival entirely.
        val location = currentLocation() ?: return false
        val crowFliesMeters = GeoMath.distanceMeters(
            location.latitude, location.longitude,
            destination.latitude, destination.longitude
        )
        return crowFliesMeters <= ARRIVAL_THRESHOLD_METERS
    }

    fun start(space: BikeParkingSpace) {
        val location = currentLocation() ?: run {
            _uiState.value = NavigationUiState.Error(MapError.LocationUnavailable)
            return
        }
        // Cancel any in-progress calculation — e.g. when the user taps a different
        // parking spot while a route is still being computed.
        beginRouting(space) {
            routingRepository.getBikeRoute(
                from = location,
                to   = GeoCoordinate(space.latitude, space.longitude)
            )
        }
    }

    /**
     * Starts navigation along a **planned multi-waypoint route**. Routes from the
     * rider's current position through every coordinate in [waypoints] in order
     * (reverse the list before calling to ride the route backwards). [destination]
     * is a synthetic space placed at the final waypoint; its id must be in
     * [syntheticDestinationIds] so arrival just confirms (no auto-park).
     */
    fun startVia(destination: BikeParkingSpace, waypoints: List<GeoCoordinate>) {
        val location = currentLocation() ?: run {
            _uiState.value = NavigationUiState.Error(MapError.LocationUnavailable)
            return
        }
        beginRouting(destination) {
            routingRepository.getBikeRouteVia(listOf(location) + waypoints)
        }
    }

    /**
     * Starts a generated **round-trip** loop of roughly [targetDistanceMeters],
     * beginning and ending at the rider's current position. [destination] is a
     * synthetic space placed at the start (its id must be in
     * [syntheticDestinationIds] so arrival neither auto-parks nor mis-fires). The
     * loop is BRouter-only; a failure (offline routing off / segments missing)
     * surfaces as a navigation error.
     */
    fun startRoundTrip(destination: BikeParkingSpace, targetDistanceMeters: Double) {
        val start = currentLocation() ?: run {
            _uiState.value = NavigationUiState.Error(MapError.LocationUnavailable)
            return
        }
        beginRouting(destination) {
            routingRepository.getRoundTrip(
                from = GeoCoordinate(start.latitude, start.longitude),
                targetDistanceMeters = targetDistanceMeters
            )
        }
    }

    /**
     * Shared launch path for [start] and [startRoundTrip]: cancels any pending
     * calculation, flips to the loading state, then runs [compute] off the main
     * thread and swaps in the [NavigationUiState.Active] route (or an error).
     */
    private fun beginRouting(
        destination: BikeParkingSpace,
        compute: suspend () -> BikeRoute
    ) {
        navigationJob?.cancel()
        _uiState.value = NavigationUiState.Loading
        _progress.value = null
        hasArrivedForCurrentRoute = false
        consecutiveArrivalFixes = 0
        navigationJob = scope.launch {
            try {
                val route = compute()
                _uiState.value = NavigationUiState.Active(destination = destination, route = route)
                onNavigationStarted()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Cancelled by the user (or a newer request) — leave the state to the
                // caller (cancelRouteCalculation sets Idle); never show an error.
                throw ce
            } catch (throwable: Throwable) {
                _uiState.value = NavigationUiState.Error(mapRoutingError(throwable))
            }
        }
    }

    /**
     * Cancels an in-progress route calculation (from the loading card's "Cancel"
     * button). Cancelling the job propagates into the BRouter engine, which aborts
     * its search; the UI returns to idle.
     */
    fun cancelRouteCalculation() {
        if (_uiState.value is NavigationUiState.Loading) {
            navigationJob?.cancel()
            navigationJob = null
            _uiState.value = NavigationUiState.Idle
            _progress.value = null
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
     * Play/pause toggle for the GPS mock simulator. Starting it walks along the
     * active route, feeding synthetic fixes (with bearing + speed) back through
     * [onSimulatedFix] exactly like real GPS — so the whole live-navigation pipeline
     * can be tested on the couch. Pressing it again **pauses** (keeping the
     * position); pressing play once more **resumes from where it left off** rather
     * than restarting — unless the route changed in the meantime (e.g. a reroute),
     * in which case it begins from the start again.
     */
    fun toggleSimulation() {
        if (_isSimulating.value) {
            pauseSimulation()
            return
        }
        val route = activeRoute ?: return
        if (route.points.size < 2) return

        // Resume from the paused position only when still on the same route instance;
        // a fresh/rerouted route restarts from the beginning.
        val resumeFrom = if (route.points === simulationRouteRef) routeSimulator.travelledMeters else 0.0
        simulationRouteRef = route.points

        _isSimulating.value = true
        onSimulationStarted()
        routeSimulator.start(
            scope = scope,
            route = route.points,
            // Brisk couch-test pace (~50 km/h), emitting twice a second so the
            // motion stays smooth despite the higher speed.
            speedMps = 13.9,
            intervalMs = 500L,
            jitterMeters = 0.0,
            startOffsetMeters = resumeFrom,
            onFix = { fix -> onSimulatedFix(fix) },
            onFinished = {
                _isSimulating.value = false
                routeSimulator.reset()
                simulationRouteRef = null
            }
        )
    }

    /**
     * Pauses the simulation, **keeping** the travelled distance so the next play
     * press resumes from here. Also brakes the navigation puck (see below).
     */
    private fun pauseSimulation() {
        routeSimulator.stop()
        _isSimulating.value = false
        brakePuck()
    }

    /**
     * Fully stops the simulation and rewinds it to the start. Used when navigation
     * ends or the controller is disposed (not for the play/pause toggle).
     */
    fun stopSimulation() {
        routeSimulator.reset()
        simulationRouteRef = null
        _isSimulating.value = false
        brakePuck()
    }

    /**
     * Feeds one final **stationary** fix (speed 0) at the current position. Without
     * the simulator no further fixes arrive to slow the navigation puck, so its
     * dead-reckoning would keep gliding it forward along the route at the last
     * simulated speed (it never sees a "standing still" fix the way real GPS would);
     * this lets the puck's speed ease to a stop instead of coasting on.
     */
    private fun brakePuck() {
        currentLocation()?.let { last ->
            onSimulatedFix(last.copy(speedMetersPerSecond = 0f))
        }
    }

    /** Stops the simulator; call from the host's `onCleared`. */
    fun dispose() {
        routeSimulator.reset()
        simulationRouteRef = null
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
         * Reaching this radius ends navigation; at a real bike parking spot it also
         * auto-parks the bike.
         */
        private const val ARRIVAL_THRESHOLD_METERS = 25.0

        /**
         * Consecutive fixes that must register inside the arrival radius before
         * navigation ends, debouncing a single noisy GPS sample.
         */
        private const val ARRIVAL_CONSECUTIVE_FIXES = 2

        /** Minimum gap between automatic off-route reroutes. */
        private const val REROUTE_COOLDOWN_MS = 8_000L
    }
}

