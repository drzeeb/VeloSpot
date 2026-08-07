package de.velospot.core.tracking

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import de.velospot.core.location.LocationController
import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.domain.repository.BikeProfilesRepository
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
import kotlinx.coroutines.flow.first
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
    /** Resolves the bike a finished ride is tagged with. Optional so unit tests can
     *  construct the manager without the garage (then rides stay untagged). */
    private val bikeProfilesRepository: BikeProfilesRepository? = null,
    /**
     * Best-effort reverse geocoder used to auto-name an unnamed recording after its
     * start place at save time (see [stop]). Supplied by Hilt in production; `null`
     * in unit tests, which then never geocode (rides stay unnamed unless a pending
     * name was set). A network lookup that fails/returns `null` must never block or
     * fail the ride save.
     */
    private val reverseGeocodePlace: (suspend (Double, Double) -> String?)? = null,
    /**
     * Best-effort hook fired with every freshly-saved ride so it can be
     * auto-exported to Health Connect when the opt-in setting is on. Supplied by
     * Hilt in production; `null` in unit tests (which then never auto-export). Must
     * never block or fail the ride save — the manager always calls it fire-and-forget
     * inside a `runCatching` on its own scope.
     */
    private val onRideSavedForExport: (suspend (RecordedRide) -> Unit)? = null,
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
        bikeProfilesRepository: BikeProfilesRepository,
        geocoder: de.velospot.data.geocoding.PhotonGeocoder,
        healthConnectExporter: de.velospot.core.health.HealthConnectExporter,
        mapSettings: de.velospot.domain.repository.MapSettingsRepository,
    ) : this(
        context,
        locationController,
        recordedRidesRepository,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        bikeProfilesRepository,
        geocoder::reverseGeocodePlace,
        onRideSavedForExport = { ride ->
            // Best-effort auto-export: silently no-ops when disabled, unavailable or
            // not permitted. Reads the current opt-in flag at save time.
            val enabled = mapSettings.healthConnectAutoExportEnabled.first()
            healthConnectExporter.autoExport(ride, enabled)
        },
    )

    private val tracker = RideTracker()

    /**
     * Debounced movement classifier. While recording, its stationary/moving verdict
     * is folded into [LocationController] so the GPS radio idles during sustained
     * stops (traffic light / café / ferry-train legs / paused) and snaps back to full
     * fidelity the moment the rider moves — the battery win for long tours.
     */
    private val standstillDetector = StandstillDetector()
    /** Last stationary verdict pushed to the controller (avoids redundant re-applies). */
    private var lastStationaryApplied = false

    private val _trackingState = MutableStateFlow<RideTrackingUiState>(RideTrackingUiState.Idle)
    val trackingState: StateFlow<RideTrackingUiState> = _trackingState.asStateFlow()

    /** Accepted points of the live track, mirrored for the map polyline. */
    private val _liveTrackPoints = MutableStateFlow<List<RoutePoint>>(emptyList())
    val liveTrackPoints: StateFlow<List<RoutePoint>> = _liveTrackPoints.asStateFlow()

    /**
     * The live track split into **segments** at every pause: each inner list is one
     * continuous stretch, and consecutive stretches are separated by a gap (a paused
     * train/ferry leg). Drawn as a broken polyline so the pause reads as a gap rather
     * than a straight line. The first (and usually only) segment is the whole ride.
     */
    private val _liveTrackSegments = MutableStateFlow<List<List<RoutePoint>>>(emptyList())
    val liveTrackSegments: StateFlow<List<List<RoutePoint>>> = _liveTrackSegments.asStateFlow()

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
                        runCatching { onRideSavedForExport?.invoke(recovered) }
                    }
                    persistence.clear()
                }
            }
            // A recreated process always comes up Idle (in-memory state was reset by
            // construction). Re-paint the out-of-app controls so a widget/tile still
            // showing a stale "Recording / Stop" (left behind by the killed process)
            // self-corrects to the true idle state. Without this the stale "Stop"
            // widget would, on tap, START a brand-new recording, because toggle()
            // keys off isRecording — which is now false.
            refreshExternalControls()
            // Then drain the live write queue for the rest of the process lifetime.
            for (op in persistOps) runCatching { op() }
        }
    }

    /** Queues a persistence write on the single ordered IO worker (never blocks). */
    private fun persist(op: () -> Unit) {
        persistOps.trySend(op)
    }

    val isRecording: Boolean get() = tracker.isRecording

    /** Whether the active recording is currently paused (train/ferry leg / break). */
    val isPaused: Boolean get() = tracker.isPaused

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
     * Set when the active recording is driven by the debug route simulator / "Mock
     * tool" (via [markMockRecording], called the moment the simulator actually
     * starts). Carried onto the saved ride as [RecordedRide.isMock]. Reset on every
     * [start].
     *
     * Deliberately **not** raised by every [feedExternal] call: the navigation puck
     * is braked with a synthetic speed-0 fix through the same external path when a
     * *normal* navigation ends, which must never flag the real ride as a mock.
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
        standstillDetector.reset()
        lastStationaryApplied = false
        val startedAt = System.currentTimeMillis()
        recordingStartedAt = startedAt
        tracker.start(startedAt)
        _liveTrackPoints.value = emptyList()
        _liveTrackSegments.value = emptyList()
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
        if (!startService()) {
            // The foreground service refused to start — e.g. a background
            // startForegroundService throwing ForegroundServiceStartNotAllowedException
            // on Android 12+. Roll back the in-memory start so no surface can claim
            // "recording" while there is no live foreground service keeping the
            // process (and GPS) alive, then re-paint the controls to the true idle
            // state. Swallowing the failure here is what previously left the tracker
            // "recording" in memory with nothing actually running.
            rollBackFailedStart()
            return
        }
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
        _liveTrackSegments.value = emptyList()
        if (ride != null) {
            scope.launch {
                // Auto-name an unnamed recording after its reverse-geocoded start
                // place (e.g. "Trier"), exactly like navigation-ride naming, so a
                // manual/background recording lands in "My rides" with a city label
                // instead of just its distance. Best-effort: a null/offline result
                // (or a missing geocoder in tests) leaves the ride unnamed but still
                // saved — this must never block or fail the save.
                val autoName = if (ride.name.isNullOrBlank()) {
                    ride.points.firstOrNull()?.let { start ->
                        runCatching { reverseGeocodePlace?.invoke(start.latitude, start.longitude) }
                            .getOrNull()
                    }
                } else null
                val named = ride.copy(name = resolveRideName(ride.name, autoName))
                // Tag the ride with the rider's active bike (or the default), resolved
                // once at save time. Untagged when no garage / no bikes exist yet.
                val bikeId = runCatching { bikeProfilesRepository?.resolveActiveProfileId() }.getOrNull()
                val tagged = if (bikeId != null) named.copy(bikeProfileId = bikeId) else named
                recordedRidesRepository.saveRide(tagged)
                _events.tryEmit(RideRecordingEvent.Saved(tagged))
                // Best-effort auto-export to Health Connect (opt-in). Fire-and-forget
                // so it can never block or fail the ride save.
                runCatching { onRideSavedForExport?.invoke(tagged) }
                // Real rides only: check whether this ride pushed the bike past a new
                // shop-service milestone and, if so, notify once (best-effort).
                if (bikeId != null && !tagged.isMock) {
                    runCatching {
                        bikeProfilesRepository?.evaluateServiceDue(bikeId)
                    }.getOrNull()?.let { reminder ->
                        BikeServiceNotifier(context).notifyServiceDue(reminder)
                    }
                }
            }
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
        _liveTrackSegments.value = emptyList()
        _events.tryEmit(RideRecordingEvent.Discarded)
        persist { persistence.clear() }
        locationController.setRecording(false)
        stopService()
        refreshExternalControls()
    }

    /** Resets the elevation cursor — call whenever the active route changes. */
    fun onRouteChanged() { lastElevationIndex = 0 }

    /**
     * Pauses the active recording (e.g. the rider boards a train/ferry on a commute).
     * Distance, moving time and the track freeze until [resume]; the elapsed timer
     * stops. GPS keeps running so resuming is instant. No-op when idle/already paused.
     */
    fun pause() {
        if (!tracker.isRecording || tracker.isPaused) return
        tracker.pause(System.currentTimeMillis())
        // A paused leg (train/ferry) idles the GPS: fixes are discarded anyway.
        applyStationary(standstillDetector.setPaused(true))
        val stats = tracker.currentStats(System.currentTimeMillis())
        _trackingState.value = RideTrackingUiState.Recording(stats)
        persistMeta(stats)
        refreshExternalControls()
    }

    /**
     * Resumes a paused recording. The paused span stays out of the elapsed time and
     * the next accepted fix starts a fresh track segment, so the paused stretch is
     * stored, drawn and exported as a **gap**. No-op when idle / not paused.
     */
    fun resume() {
        if (!tracker.isRecording || !tracker.isPaused) return
        tracker.resume(System.currentTimeMillis())
        // Resuming restores full-accuracy fixes at once; the dwell re-arms on the
        // next low-speed fix if the rider is still stopped.
        applyStationary(standstillDetector.setPaused(false))
        val stats = tracker.currentStats(System.currentTimeMillis())
        _trackingState.value = RideTrackingUiState.Recording(stats)
        persistMeta(stats)
        refreshExternalControls()
    }

    /** Toggles pause/resume for the single Pause control (FAB, notification). */
    fun togglePause() {
        if (tracker.isPaused) resume() else pause()
    }

    /** Persists the running aggregates (incl. paused time) for crash recovery. */
    private fun persistMeta(stats: de.velospot.domain.model.LiveRideStats) {
        val name = pendingRideName
        persist {
            persistence.writeMeta(
                startedAt = recordingStartedAt,
                distanceMeters = stats.distanceMeters,
                movingSeconds = stats.movingSeconds,
                maxSpeedMps = stats.maxSpeedMps,
                elevationGain = stats.elevationGainMeters,
                elevationLoss = stats.elevationLossMeters,
                name = name,
                pausedMillis = tracker.elapsedPausedMillis
            )
        }
    }

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
     * bypassing the real-GPS suppression gate. Does **not** by itself mark the ride
     * as a mock — that is done explicitly via [markMockRecording] when the simulator
     * actually starts — so the speed-0 "brake" fix fed when a normal navigation ends
     * never flags a real ride.
     */
    fun feedExternal(location: GeoCoordinate) {
        if (tracker.isRecording) {
            feed(location)
        }
    }

    /**
     * Flags the active recording as a mock (route-simulator) ride, so it is saved
     * with [RecordedRide.isMock] = `true`. Called the moment the debug simulator
     * genuinely starts driving the active navigation route.
     */
    fun markMockRecording() {
        if (tracker.isRecording) sawSimulatedFix = true
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    /**
     * Pushes a movement verdict to the [LocationController] only on a real
     * transition, so the GPS request is recomputed once per moving↔stationary
     * change rather than on every fix.
     */
    private fun applyStationary(stationary: Boolean) {
        if (stationary == lastStationaryApplied) return
        lastStationaryApplied = stationary
        locationController.setRecordingStationary(stationary)
    }

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
        // Feed the debounced movement classifier so the GPS radio idles during
        // sustained stops and restores full accuracy the moment the rider moves.
        // Skipped while paused — the pause path already forced the idle profile.
        if (!tracker.isPaused) {
            applyStationary(standstillDetector.onFix(stats.currentSpeedMps, System.currentTimeMillis()))
        }
        if (tracker.trackPoints.size > pointsBefore) {
            val accepted = tracker.trackPoints.last()
            val routePoint = RoutePoint(accepted.latitude, accepted.longitude)
            _liveTrackPoints.update { it + routePoint }
            // Maintain the segmented mirror: a point flagged as a segment start (the
            // first fix after a resume) opens a new stretch, so the paused leg draws
            // as a gap; otherwise extend the current stretch.
            _liveTrackSegments.update { segments ->
                if (accepted.segmentStart || segments.isEmpty()) {
                    segments + listOf(listOf(routePoint))
                } else {
                    segments.dropLast(1) + listOf(segments.last() + routePoint)
                }
            }
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
                        name = name,
                        pausedMillis = tracker.elapsedPausedMillis
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


    private fun startService(): Boolean {
        val result = runCatching {
            val intent = Intent(context, RideRecordingService::class.java)
                .setAction(RideRecordingService.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
        val error = result.exceptionOrNull()
        // Treat the start as *failed* only when the platform explicitly refused it
        // (Android 12+ background foreground-service restriction). That is the one
        // case where no foreground service actually came up, so the in-memory
        // recording must be rolled back rather than silently claiming "recording".
        // Any other / ambiguous outcome (including a stubbed Context in unit tests)
        // keeps the recording, preserving the previous best-effort behaviour.
        return !(error != null && isForegroundServiceStartNotAllowed(error))
    }

    /**
     * True when [error] is the Android 12+ `ForegroundServiceStartNotAllowedException`
     * thrown when a foreground service is started from the background. Guarded by the
     * SDK check so the API-31 type is never referenced on older platforms.
     */
    private fun isForegroundServiceStartNotAllowed(error: Throwable): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error is android.app.ForegroundServiceStartNotAllowedException


    /**
     * Unwinds a [start] that could not bring its foreground service up, returning
     * the manager to a clean Idle state. Mirrors [discard] (abort without saving)
     * but is used specifically when the FGS start was refused, so the in-memory
     * "recording" claim can never outlive the missing service. Re-paints the
     * external controls so the widget/tile reflect the true (not recording) state.
     */
    private fun rollBackFailedStart() {
        locationJob?.cancel(); locationJob = null
        stopTicker()
        tracker.discard()
        isAutoStartedByNavigation = false
        pendingRideName = null
        _trackingState.value = RideTrackingUiState.Idle
        _liveTrackPoints.value = emptyList()
        _liveTrackSegments.value = emptyList()
        persist { persistence.clear() }
        locationController.setRecording(false)
        refreshExternalControls()
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

        /**
         * Pure decision for a saved ride's final name:
         *  - an explicit [existingName] (typed by the rider or set for a navigation /
         *    round-trip ride) always wins and is never overridden;
         *  - otherwise fall back to the reverse-[geocodedPlace] of the start point;
         *  - `null` only when neither is usable (offline/unknown place) — the ride is
         *    then saved unnamed and the UI falls back to its date.
         * Blank strings are treated as absent so an empty prompt never wins.
         */
        internal fun resolveRideName(existingName: String?, geocodedPlace: String?): String? {
            existingName?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            return geocodedPlace?.trim()?.takeIf { it.isNotBlank() }
        }
    }
}

/** One-shot outcome of a recording, surfaced to the UI when it is alive. */
sealed interface RideRecordingEvent {
    data class Saved(val ride: RecordedRide) : RideRecordingEvent
    data object TooShort : RideRecordingEvent
    data object Discarded : RideRecordingEvent
}

