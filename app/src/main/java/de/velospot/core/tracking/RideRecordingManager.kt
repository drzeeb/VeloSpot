package de.velospot.core.tracking

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import de.velospot.core.location.LocationController
import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.repository.RecordedRidesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
    private val locationController: LocationController,
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
        locationController: LocationController,
        recordedRidesRepository: RecordedRidesRepository,
    ) : this(
        context,
        locationController,
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

    /**
     * Crash-safe stream of the in-progress recording. Each accepted fix is appended
     * to disk so a track survives the process being killed mid-ride (see
     * [RideRecordingPersistence]).
     */
    private val persistence = RideRecordingPersistence(context)

    /**
     * Serialises persistence writes onto a single IO worker so `begin → append …
     * → clear` always run in submission order regardless of which thread fed the
     * fix. Unbounded so a feed never blocks on disk.
     */
    private val persistOps = Channel<() -> Unit>(Channel.UNLIMITED)

    /** Wall-clock start of the active recording (written into the persisted meta). */
    private var recordingStartedAt = 0L

    init {
        scope.launch(Dispatchers.IO) {
            // First, recover an orphaned recording left behind by a previous process
            // that was killed mid-ride: save the partial track so it isn't lost, then
            // clear the session. Runs before any new session's writes are processed.
            runCatching {
                if (persistence.hasActiveSession()) {
                    persistence.recover()?.let { recovered ->
                        recordedRidesRepository.saveRide(recovered)
                        _events.tryEmit(RideRecordingEvent.Saved(recovered))
                    }
                    persistence.clear()
                }
            }
            // Then drain the live write queue for the rest of the process lifetime.
            for (op in persistOps) runCatching { op() }
        }
    }

    /** Queues a persistence write on the single ordered IO worker (never blocks). */
    private fun persist(op: () -> Unit) {
        persistOps.trySend(op)
    }

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


    /** When `true`, real GPS fixes are ignored (used by the debug route simulator). */
    @Volatile
    var suppressRealFixes: Boolean = false

    /**
     * Set the moment the active recording receives its first simulated fix (from
     * the debug route simulator / "Mock tool"). Carried onto the saved ride as
     * [RecordedRide.isMock]. Reset on every [start].
     */
    @Volatile
    private var sawSimulatedFix: Boolean = false

    /**
     * Name to attach to the ride when it is saved on [stop]. Set by the host while
     * recording (the navigation destination / "Round trip – place", or the name the
     * rider typed when finishing a manual recording). Cleared on every [start].
     */
    @Volatile
    var pendingRideName: String? = null

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
        pendingRideName = null
        sawSimulatedFix = false
        lastElevationIndex = 0
        val startedAt = System.currentTimeMillis()
        recordingStartedAt = startedAt
        tracker.start(startedAt)
        _liveTrackPoints.value = emptyList()
        _trackingState.value = RideTrackingUiState.Recording(tracker.currentStats())
        // Open the crash-recovery session before the first (seed) fix is appended.
        persist { persistence.begin(startedAt) }
        seedLocation?.let { feed(it) }
        startTicker()
        observeLocation()
        // Declare the recording's location need to the single GPS owner: it keeps the
        // radio at high accuracy and (with the foreground service) alive in the
        // background, regardless of whether a map ViewModel is around.
        locationController.setRecording(true)
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
            ?.copy(
                name = pendingRideName?.trim()?.takeIf { it.isNotBlank() },
                isMock = sawSimulatedFix
            )
        isAutoStartedByNavigation = false
        pendingRideName = null
        _trackingState.value = RideTrackingUiState.Idle
        _liveTrackPoints.value = emptyList()
        if (ride != null) {
            scope.launch { recordedRidesRepository.saveRide(ride) }
            _events.tryEmit(RideRecordingEvent.Saved(ride))
        } else {
            _events.tryEmit(RideRecordingEvent.TooShort)
        }
        // The ride is persisted to the DB now (or was too short) — drop the
        // crash-recovery session so it isn't replayed on the next launch.
        persist { persistence.clear() }
        locationController.setRecording(false)
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
        persist { persistence.clear() }
        locationController.setRecording(false)
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
        if (tracker.isRecording) {
            sawSimulatedFix = true
            feed(location)
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun observeLocation() {
        locationJob?.cancel()
        locationJob = scope.launch {
            locationController.locationFlow().collect { location ->
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
            // Stream the accepted fix to disk for crash recovery. Skipped for
            // simulated (mock) fixes — those rides are debug-only and not recovered.
            if (!suppressRealFixes) {
                val name = pendingRideName
                persist {
                    persistence.appendPoint(accepted)
                    persistence.writeMeta(
                        startedAt = recordingStartedAt,
                        distanceMeters = stats.distanceMeters,
                        movingSeconds = stats.movingSeconds,
                        maxSpeedMps = stats.maxSpeedMps,
                        elevationGain = stats.elevationGainMeters,
                        elevationLoss = stats.elevationLossMeters,
                        name = name
                    )
                }
            }
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

