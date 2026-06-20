package de.velospot.feature.map.presentation.ride

import de.velospot.core.navigation.GeoMath
import de.velospot.core.tracking.RideTracker
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.feature.map.presentation.MapCameraTarget
import de.velospot.feature.map.presentation.RideTrackingUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the "record my ride" concern: the live recording lifecycle, the derived
 * statistics, the persisted ride timeline and the polyline drawn on the map.
 * Extracted from `MapViewModel` so the tracking logic is isolated and testable.
 *
 * The few genuinely cross-feature effects are passed in as callbacks rather than
 * reached for directly, keeping this controller ignorant of navigation, the
 * camera and the map selection:
 *  - [activeRoute]          supplies the navigation route for accurate elevation,
 *  - [currentLocation]      seeds the track with the latest fix on start,
 *  - [onAccuracyChanged]    lets the host re-evaluate the GPS power mode,
 *  - [onUserMessage]        surfaces a one-shot string-resource toast,
 *  - [clearOtherSelections] dismisses competing map sheets when a ride is opened,
 *  - [moveCamera]           centres the map on a selected ride.
 */
class RideTrackingController(
    private val scope: CoroutineScope,
    private val repository: RecordedRidesRepository,
    private val activeRoute: () -> BikeRoute?,
    private val currentLocation: () -> GeoCoordinate?,
    private val onAccuracyChanged: () -> Unit,
    private val onUserMessage: (Int) -> Unit,
    private val clearOtherSelections: () -> Unit,
    private val moveCamera: (MapCameraTarget) -> Unit,
) {
    private val tracker = RideTracker()

    private val _trackingState = MutableStateFlow<RideTrackingUiState>(RideTrackingUiState.Idle)
    val trackingState: StateFlow<RideTrackingUiState> = _trackingState.asStateFlow()

    /** All persisted rides, newest first (the "My rides" timeline). */
    private val _recordedRides = MutableStateFlow<List<RecordedRide>>(emptyList())
    val recordedRides: StateFlow<List<RecordedRide>> = _recordedRides.asStateFlow()

    /** The ride whose detail sheet is currently open (null when none). */
    private val _selectedRide = MutableStateFlow<RecordedRide?>(null)
    val selectedRide: StateFlow<RecordedRide?> = _selectedRide.asStateFlow()

    /**
     * Polyline drawn on the map: the live track while recording, or the selected
     * ride's track while its detail sheet is open. Empty otherwise.
     */
    private val _trackPoints = MutableStateFlow<List<RoutePoint>>(emptyList())
    val trackPoints: StateFlow<List<RoutePoint>> = _trackPoints.asStateFlow()

    /** Whether the current recording was auto-started by navigation (vs. the FAB). */
    var isAutoStartedByNavigation = false
        private set

    val isRecording: Boolean get() = tracker.isRecording

    /**
     * Cursor into the active route's point list marking the last elevation match.
     * A rider advances monotonically along the route, so the next nearest point is
     * almost always at or ahead of this index — letting [activeRouteElevationAt]
     * resume the search here instead of rescanning from the start every GPS fix.
     */
    private var lastElevationIndex = 0

    init {
        scope.launch {
            repository.getRidesFlow().collect { _recordedRides.value = it }
        }
    }

    /**
     * Starts recording a ride. No-op when a recording is already running so the
     * manual FAB and the automatic navigation hook never fight over the tracker.
     */
    fun start(autoStarted: Boolean = false) {
        if (tracker.isRecording) return
        isAutoStartedByNavigation = autoStarted
        tracker.start(System.currentTimeMillis())
        _selectedRide.value = null
        _trackPoints.value = emptyList()
        _trackingState.value = RideTrackingUiState.Recording(tracker.currentStats())
        // Seed with the current fix so the track starts immediately.
        currentLocation()?.let { feed(it) }
        onAccuracyChanged()
    }

    /**
     * Stops the active recording, persists it (when long enough) and opens its
     * detail sheet. Short rides are discarded with a hint.
     */
    fun stop() {
        if (!tracker.isRecording) return
        val ride = tracker.stop(System.currentTimeMillis())
        isAutoStartedByNavigation = false
        _trackingState.value = RideTrackingUiState.Idle
        _trackPoints.value = emptyList()
        if (ride != null) {
            scope.launch { repository.saveRide(ride) }
            onUserMessage(de.velospot.R.string.ride_saved)
            selectRide(ride)
        } else {
            onUserMessage(de.velospot.R.string.ride_too_short)
        }
        onAccuracyChanged()
    }

    /** Discards the active recording without saving anything. */
    fun discard() {
        if (!tracker.isRecording) return
        tracker.discard()
        isAutoStartedByNavigation = false
        _trackingState.value = RideTrackingUiState.Idle
        _trackPoints.value = emptyList()
        onAccuracyChanged()
    }

    /** Feeds a GPS fix into the tracker and republishes the live stats + track. */
    fun feed(location: GeoCoordinate) {
        // Prefer BRouter's accurate, smooth terrain elevation (from its SRTM-backed
        // segment files) while navigating; raw GPS altitude is far too noisy. Falls
        // back to GPS altitude for manual rides or sources without elevation.
        val altitude = activeRouteElevationAt(location) ?: location.altitudeMeters
        val stats = tracker.addPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = System.currentTimeMillis(),
            speedMps = location.speedMetersPerSecond,
            altitudeMeters = altitude
        )
        _trackingState.value = RideTrackingUiState.Recording(stats)
        // `addPoint` always appends exactly one TrackPoint, so we mirror only that
        // single new point instead of re-mapping the entire (ever-growing) track on
        // every GPS fix. This turns an O(n) full rebuild per fix — i.e. O(n²) and
        // N fresh RoutePoint allocations over a whole ride — into a single append,
        // sparing the GC and avoiding redundant Compose recompositions.
        _trackPoints.update { it + RoutePoint(location.latitude, location.longitude) }
    }

    /**
     * Resets the elevation cursor — call whenever the active route changes (a new
     * navigation starts or an off-route reroute swaps in a fresh route).
     */
    fun onRouteChanged() {
        lastElevationIndex = 0
    }

    /**
     * Terrain elevation (m) of the active route nearest to [location], or `null`
     * when not navigating, the route carries no elevation, or the rider is too far
     * from the route to trust the match (then GPS altitude is used instead).
     */
    private fun activeRouteElevationAt(location: GeoCoordinate): Double? {
        val route = activeRoute() ?: return null
        val points = route.points
        if (lastElevationIndex >= points.size) lastElevationIndex = 0

        var best: Double? = null
        var bestDist = Double.MAX_VALUE
        // Resume from the last match: amortised ~O(1) per fix instead of O(route)
        // because the nearest point rarely lies behind the previous one.
        for (i in lastElevationIndex until points.size) {
            val elevation = points[i].elevationMeters ?: continue
            val dist = GeoMath.distanceMeters(
                location.latitude, location.longitude, points[i].latitude, points[i].longitude
            )
            if (dist < bestDist) {
                bestDist = dist
                best = elevation
                lastElevationIndex = i
            }
        }
        return if (bestDist <= ROUTE_ELEVATION_MATCH_METERS) best else null
    }

    /** Opens the detail sheet for a recorded ride and draws its track on the map. */
    fun selectRide(ride: RecordedRide) {
        clearOtherSelections()
        _selectedRide.value = ride
        if (!tracker.isRecording) {
            _trackPoints.value = ride.points.map { RoutePoint(it.latitude, it.longitude) }
        }
        ride.points.firstOrNull()?.let { start ->
            moveCamera(
                MapCameraTarget(
                    latitude = start.latitude,
                    longitude = start.longitude,
                    zoom = 14.0,
                    verticalOffsetFraction = 1.0 / 6.0
                )
            )
        }
    }

    fun dismissSelectedRide() {
        _selectedRide.value = null
        if (!tracker.isRecording) _trackPoints.value = emptyList()
    }

    fun deleteRide(id: String) {
        if (_selectedRide.value?.id == id) dismissSelectedRide()
        scope.launch { repository.removeRide(id) }
    }

    companion object {
        /**
         * Maximum distance (m) from the active route at which its terrain elevation
         * is trusted for the recorded ride; beyond this GPS altitude is used.
         */
        private const val ROUTE_ELEVATION_MATCH_METERS = 50.0
    }
}

