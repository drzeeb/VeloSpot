package de.velospot.core.tracking

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.feature.map.presentation.RideTrackingUiState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level owner of an **active ride recording**.
 *
 * Unlike the previous `viewModelScope`-bound tracking, this is a Hilt
 * [Singleton] so the running recording survives the destruction of the map
 * `ViewModel`/Activity (e.g. when the user backgrounds or swipes the app away).
 * Paired with [RideRecordingService] — a `location`-typed foreground service that
 * keeps the process alive and shows a stop/save notification — the recording now
 * keeps accumulating GPS fixes while the app is closed.
 *
 * The pure accumulation maths still live in the unit-tested [RideTracker]; this
 * class wires it to the GPS source, the persistence repository, the live UI state
 * flows and the foreground service lifecycle.
 */
@Singleton
class RideRecordingManager(
    private val context: Context,
    private val locationRepository: LocationRepository,
    private val recordedRidesRepository: RecordedRidesRepository,
    /** Long-lived scope, independent of any ViewModel, so feeding/persistence
     *  continue while the app is backgrounded or closed. Injectable so unit tests
     *  can supply a controlled, cancellable scope instead of the real-thread default. */
    private val scope: CoroutineScope,
) {
    /**
     * Production constructor (Hilt-provided): owns a long-lived [SupervisorJob] on
     * [Dispatchers.Default] so a running recording survives the map ViewModel.
     */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        locationRepository: LocationRepository,
        recordedRidesRepository: RecordedRidesRepository,
    ) : this(
        context,
        locationRepository,
        recordedRidesRepository,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val tracker = RideTracker()

    private val _trackingState = MutableStateFlow<RideTrackingUiState>(RideTrackingUiState.Idle)
    val trackingState: StateFlow<RideTrackingUiState> = _trackingState.asStateFlow()

    /** Accepted points of the live track, mirrored for the map polyline. */
    private val _liveTrackPoints = MutableStateFlow<List<RoutePoint>>(emptyList())
    val liveTrackPoints: StateFlow<List<RoutePoint>> = _liveTrackPoints.asStateFlow()

    /** One-shot lifecycle events (ride saved / discarded as too short). */
    private val _events = MutableSharedFlow<RideRecordingEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<RideRecordingEvent> = _events.asSharedFlow()

    val isRecording: Boolean get() = tracker.isRecording

    /** Whether the current recording was auto-started by navigation (vs. the FAB). */
    var isAutoStartedByNavigation = false
        private set

    /**
     * Supplies the active navigation route (for accurate terrain elevation) while a
     * ViewModel with live navigation is around. Cleared when that ViewModel dies, so
     * a background recording simply falls back to GPS altitude.
     */
    var routeElevationProvider: (() -> BikeRoute?)? = null

    /**
     * Invoked whenever the recording starts/stops so a live host (the map ViewModel)
     * can re-evaluate the GPS power mode against navigation. When `null` (no host,
     * e.g. app closed) the manager manages the GPS radio itself.
     */
    var onRecordingStateChanged: (() -> Unit)? = null

    /** When `true`, real GPS fixes are ignored (used by the debug route simulator). */
    @Volatile
    var suppressRealFixes: Boolean = false

    private var lastElevationIndex = 0
    private var tickerJob: Job? = null
    private var locationJob: Job? = null

    // ── Recording lifecycle ───────────────────────────────────────────────────

    /**
     * Begins a recording. No-op when one is already running so the manual FAB and
     * the automatic navigation hook never fight over the tracker.
     */
    fun start(autoStarted: Boolean = false, seedLocation: GeoCoordinate? = null) {
        if (tracker.isRecording) return
        isAutoStartedByNavigation = autoStarted
        lastElevationIndex = 0
        tracker.start(System.currentTimeMillis())
        _liveTrackPoints.value = emptyList()
        _trackingState.value = RideTrackingUiState.Recording(tracker.currentStats())
        seedLocation?.let { feed(it) }
        startTicker()
        observeLocation()
        // Guarantee precise, frequent fixes even when no ViewModel is around.
        runCatching { locationRepository.startLocationUpdates(highAccuracy = true) }
        onRecordingStateChanged?.invoke()
        startService()
        refreshExternalControls()
    }

    /**
     * Stops the recording, persisting it when long enough. Emits [RideRecordingEvent].
     * Safe to call from the UI (FAB) or the notification action.
     */
    fun stop() {
        if (!tracker.isRecording) return
        locationJob?.cancel(); locationJob = null
        stopTicker()
        val ride = tracker.stop(System.currentTimeMillis())
        isAutoStartedByNavigation = false
        _trackingState.value = RideTrackingUiState.Idle
        _liveTrackPoints.value = emptyList()
        if (ride != null) {
            scope.launch { recordedRidesRepository.saveRide(ride) }
            _events.tryEmit(RideRecordingEvent.Saved(ride))
        } else {
            _events.tryEmit(RideRecordingEvent.TooShort)
        }
        finishGps()
        stopService()
        refreshExternalControls()
    }

    /** Aborts the recording without saving anything. */
    fun discard() {
        if (!tracker.isRecording) return
        locationJob?.cancel(); locationJob = null
        stopTicker()
        tracker.discard()
        isAutoStartedByNavigation = false
        _trackingState.value = RideTrackingUiState.Idle
        _liveTrackPoints.value = emptyList()
        _events.tryEmit(RideRecordingEvent.Discarded)
        finishGps()
        stopService()
        refreshExternalControls()
    }

    /** Resets the elevation cursor — call whenever the active route changes. */
    fun onRouteChanged() { lastElevationIndex = 0 }

    /**
     * Toggles the recording: stops a running one (persisting it), otherwise starts a
     * fresh one. Used by the Quick Settings tile and the home-screen widget, which
     * have a single start/stop control.
     */
    fun toggle() {
        if (tracker.isRecording) stop() else start()
    }

    /**
     * Feeds an externally-sourced fix (the debug route simulator) into the tracker,
     * bypassing the real-GPS suppression gate.
     */
    fun feedExternal(location: GeoCoordinate) {
        if (tracker.isRecording) feed(location)
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun observeLocation() {
        locationJob?.cancel()
        locationJob = scope.launch {
            locationRepository.getCurrentLocationFlow().collect { location ->
                if (location == null || suppressRealFixes) return@collect
                if (tracker.isRecording) feed(location)
            }
        }
    }

    private fun feed(location: GeoCoordinate) {
        val altitude = activeRouteElevationAt(location) ?: location.altitudeMeters
        val pointsBefore = tracker.trackPoints.size
        val stats = tracker.addPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = System.currentTimeMillis(),
            speedMps = location.speedMetersPerSecond,
            altitudeMeters = altitude,
            accuracyMeters = location.accuracyMeters
        )
        _trackingState.value = RideTrackingUiState.Recording(stats)
        if (tracker.trackPoints.size > pointsBefore) {
            val accepted = tracker.trackPoints.last()
            _liveTrackPoints.update { it + RoutePoint(accepted.latitude, accepted.longitude) }
        }
    }

    /**
     * Terrain elevation (m) of the active route nearest to [location], or `null`
     * when not navigating / too far from the route (then GPS altitude is used).
     */
    private fun activeRouteElevationAt(location: GeoCoordinate): Double? {
        val route = routeElevationProvider?.invoke() ?: return null
        val points = route.points
        if (lastElevationIndex >= points.size) lastElevationIndex = 0
        var best: Double? = null
        var bestDist = Double.MAX_VALUE
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

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && tracker.isRecording) {
                delay(1_000)
                if (!tracker.isRecording) break
                _trackingState.value =
                    RideTrackingUiState.Recording(tracker.currentStats(System.currentTimeMillis()))
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * Releases the GPS after a recording ends. A live host re-evaluates the power
     * mode (it may still need GPS for navigation); without a host the manager stops
     * the radio itself — but only while backgrounded, so a foregrounded map keeps
     * its position updates.
     */
    private fun finishGps() {
        val host = onRecordingStateChanged
        if (host != null) {
            host.invoke()
        } else {
            // No live host (app closed, started from tile/widget): nothing observes
            // the GPS anymore, so release the radio.
            runCatching { locationRepository.stopLocationUpdates() }
        }
    }

    private fun startService() {
        runCatching {
            val intent = Intent(context, RideRecordingService::class.java)
                .setAction(RideRecordingService.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun stopService() {
        runCatching {
            context.stopService(Intent(context, RideRecordingService::class.java))
        }
    }

    /**
     * Pushes the latest recording state to the out-of-app controls — the home-screen
     * widget and the Quick Settings tile — so their start/stop label stays in sync
     * regardless of whether the app's UI is open.
     */
    private fun refreshExternalControls() {
        runCatching {
            context.sendBroadcast(
                Intent(context, RideRecordingWidget::class.java)
                    .setAction(RideRecordingWidget.ACTION_REFRESH)
                    .setPackage(context.packageName)
            )
        }
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, RideRecordingTileService::class.java)
            )
        }
    }

    companion object {
        /** Max distance (m) from the active route at which its terrain elevation is trusted. */
        private const val ROUTE_ELEVATION_MATCH_METERS = 50.0
    }
}

/** One-shot outcome of a recording, surfaced to the UI when it is alive. */
sealed interface RideRecordingEvent {
    data class Saved(val ride: RecordedRide) : RideRecordingEvent
    data object TooShort : RideRecordingEvent
    data object Discarded : RideRecordingEvent
}

