package de.velospot.feature.map.presentation.ride

import de.velospot.core.tracking.RideRecordingEvent
import de.velospot.core.tracking.RideRecordingManager
import de.velospot.core.tracking.RideTrackingUiState
import de.velospot.core.map.splitIntoSegments
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.model.RideTrackGeometry
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.feature.map.presentation.MapCameraTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the **UI-facing** side of the "record my ride" concern: the persisted ride
 * timeline, the selected-ride detail sheet and the polyline drawn on the map.
 *
 * The *active* recording lifecycle (the tracker, the GPS feed and the live stats)
 * now lives in the process-level [RideRecordingManager], so it survives this
 * ViewModel and keeps running in the background via the foreground service. This
 * controller delegates the recording controls to the manager and reflects its
 * state, while keeping the timeline/selection logic that is genuinely a UI concern
 * bound to the ViewModel scope.
 *
 * The timeline is track-free ([recordedRideSummaries]); a ride's full GPS track is
 * only loaded when it is opened ([selectRide]) or when a map overlay needs every
 * track ([recordedRideTracks], gated by [overlayTracksNeeded]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideTrackingController(
    private val scope: CoroutineScope,
    private val repository: RecordedRidesRepository,
    private val manager: RideRecordingManager,
    private val currentLocation: () -> GeoCoordinate?,
    private val onUserMessage: (Int) -> Unit,
    private val clearOtherSelections: () -> Unit,
    private val moveCamera: (MapCameraTarget) -> Unit,
    /**
     * Emits `true` while a map overlay (heatmap or ridden-tracks) is visible and
     * therefore needs every ride's full geometry. While `false` no tracks are
     * loaded or held in memory at all.
     */
    overlayTracksNeeded: Flow<Boolean>,
    /** Invoked with every freshly-saved ride (used e.g. to record a route attempt). */
    private val onRideSaved: (RecordedRide) -> Unit = {},
) {
    val trackingState: StateFlow<RideTrackingUiState> = manager.trackingState

    /** All persisted rides as track-free summaries, newest first (the timeline). */
    private val _recordedRideSummaries = MutableStateFlow<List<RecordedRideSummary>>(emptyList())
    val recordedRideSummaries: StateFlow<List<RecordedRideSummary>> = _recordedRideSummaries.asStateFlow()

    /**
     * Every recorded ride's **geometry** (lat/lon only) — but only while an overlay
     * actually needs it. Empty (and nothing deserialised) whenever the heatmap and
     * ridden-tracks layers are both off, which is the common case. Speeds/altitudes
     * are never parsed, and a rename while a layer is visible does not re-deserialise
     * the history (the source gates on the track set, not all metadata).
     */
    val recordedRideTracks: StateFlow<List<RideTrackGeometry>> =
        overlayTracksNeeded
            .distinctUntilChanged()
            .flatMapLatest { needed ->
                if (needed) repository.getRideTrackGeometriesFlow() else flowOf(emptyList())
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The ride whose detail sheet is currently open (null when none). */
    private val _selectedRide = MutableStateFlow<RecordedRide?>(null)
    val selectedRide: StateFlow<RecordedRide?> = _selectedRide.asStateFlow()

    /**
     * Whether the open [selectedRide] is a **transient GPX preview** — a ride parsed
     * from an opened `.gpx` file that is NOT yet in the database. While `true` the
     * detail sheet shows an "Import" button and closing the sheet discards the ride
     * (nothing is persisted). Cleared the moment the preview is imported or dismissed.
     */
    private val _isPreviewRide = MutableStateFlow(false)
    val isPreviewRide: StateFlow<Boolean> = _isPreviewRide.asStateFlow()

    /**
     * The full set of rides parsed from the previewed GPX (a multi-`<trk>` file
     * yields several). The first is shown in the sheet; importing the preview
     * persists them all. Empty when no preview is open.
     */
    private var previewRides: List<RecordedRide> = emptyList()

    /**
     * Polyline drawn on the map: the live track while recording, or the selected
     * ride's track while its detail sheet is open. Empty otherwise.
     */
    val trackPoints: StateFlow<List<RoutePoint>> = combine(
        manager.liveTrackPoints,
        manager.trackingState,
        _selectedRide
    ) { live, state, selected ->
        if (state is RideTrackingUiState.Recording) live
        else selected?.points?.map { RoutePoint(it.latitude, it.longitude) } ?: emptyList()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The map polyline **split into segments** at every pause, so a paused leg (a
     * train/ferry stretch on a commute) is drawn as a gap rather than a straight
     * line. While recording it mirrors the live segments; while inspecting a saved
     * ride it splits that ride's track at every [de.velospot.domain.model.TrackPoint.segmentStart].
     */
    val trackSegments: StateFlow<List<List<RoutePoint>>> = combine(
        manager.liveTrackSegments,
        manager.trackingState,
        _selectedRide
    ) { liveSegments, state, selected ->
        if (state is RideTrackingUiState.Recording) liveSegments
        else selected?.points?.splitIntoSegments() ?: emptyList()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val isRecording: Boolean get() = manager.isRecording

    /** Whether the current recording was auto-started by navigation (vs. the FAB). */
    val isAutoStartedByNavigation: Boolean get() = manager.isAutoStartedByNavigation

    init {
        scope.launch {
            repository.getRideSummariesFlow().collect { _recordedRideSummaries.value = it }
        }
        // Surface recording outcomes (saved / too short) and open the detail sheet
        // for a freshly-saved ride when the app is in the foreground.
        scope.launch {
            manager.events.collect { event ->
                when (event) {
                    is RideRecordingEvent.Saved -> {
                        onUserMessage(de.velospot.R.string.ride_saved)
                        onRideSaved(event.ride)
                        showRide(event.ride)
                    }
                    RideRecordingEvent.TooShort ->
                        onUserMessage(de.velospot.R.string.ride_too_short)
                    RideRecordingEvent.Discarded -> Unit
                }
            }
        }
    }

    /** Starts recording a ride (no-op when one is already running). */
    fun start(autoStarted: Boolean = false) {
        if (manager.isRecording) return
        _selectedRide.value = null
        manager.start(autoStarted = autoStarted, seedLocation = currentLocation())
    }

    /** Stops the active recording, persisting it when long enough. */
    fun stop() = manager.stop()

    /** Toggles pause/resume of the active recording (train/ferry leg / break). */
    fun togglePause() = manager.togglePause()

    /** Discards the active recording without saving anything. */
    fun discard() = manager.discard()

    /** Feeds a (simulated) GPS fix into the active recording. */
    fun feed(location: GeoCoordinate) = manager.feedExternal(location)

    /** Flags the active recording as a mock (route-simulator) ride. */
    fun markMock() = manager.markMockRecording()

    /** Resets the elevation-match cursor when the active route changes. */
    fun onRouteChanged() = manager.onRouteChanged()

    /**
     * Sets the name that the **active** recording will be saved with on [stop] (the
     * navigation destination / "Round trip – place", or the name typed when finishing
     * a manual recording). `null` leaves the ride unnamed.
     */
    fun setPendingName(name: String?) {
        manager.pendingRideName = name
    }

    /** Renames a persisted ride (and the open detail sheet, if it's the same one). */
    fun renameRide(id: String, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotBlank() }
        _selectedRide.update { if (it?.id == id) it.copy(name = trimmed) else it }
        scope.launch { repository.updateRideName(id, trimmed) }
    }

    /** Persists a ride built from an imported GPX track. */
    fun importRide(ride: RecordedRide) {
        scope.launch { repository.saveRide(ride) }
    }

    /**
     * Persists all [rides] parsed from an opened GPX file and opens the first one's
     * detail sheet (the same view a recorded ride uses). No-op on an empty list.
     */
    fun importRidesAndShowFirst(rides: List<RecordedRide>) {
        val first = rides.firstOrNull() ?: return
        scope.launch { rides.forEach { repository.saveRide(it) } }
        showRide(first)
    }

    /**
     * Opens the detail sheet for a GPX file's parsed [rides] in **preview** mode —
     * shown but NOT persisted. The first ride is displayed (and its track drawn on
     * the map); [importPreview] later saves them all, while [dismissSelectedRide]
     * simply discards them. No-op on an empty list.
     */
    fun showPreview(rides: List<RecordedRide>) {
        val first = rides.firstOrNull() ?: return
        previewRides = rides
        _isPreviewRide.value = true
        showRide(first)
    }

    /**
     * Persists the currently-previewed GPX rides and turns the preview into a normal,
     * saved ride (the sheet stays open on the now-persisted first ride). No-op when
     * no preview is open.
     */
    fun importPreview() {
        val rides = previewRides
        val first = rides.firstOrNull() ?: return
        previewRides = emptyList()
        _isPreviewRide.value = false
        scope.launch { rides.forEach { repository.saveRide(it) } }
        _selectedRide.value = first
    }

    /** Opens the detail sheet for a recorded ride and draws its track on the map. */
    fun selectRide(summary: RecordedRideSummary) {
        // Optimistically clear other selections; load the full track (off-main in
        // the repository) before drawing, since the timeline summary has none.
        scope.launch {
            val ride = repository.getRide(summary.id) ?: return@launch
            showRide(ride)
        }
    }

    /** Shows an already-loaded full ride (e.g. the one just saved). */
    private fun showRide(ride: RecordedRide) {
        clearOtherSelections()
        _selectedRide.value = ride
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
        // Discard any transient GPX preview — nothing was ever persisted.
        _isPreviewRide.value = false
        previewRides = emptyList()
    }

    fun deleteRide(id: String) {
        if (_selectedRide.value?.id == id) dismissSelectedRide()
        scope.launch { repository.removeRide(id) }
    }

    /**
     * Archives a ride (hides it from the active timeline) or restores it. Closes the
     * detail sheet of the affected ride when archiving so the map returns to the list.
     */
    fun setRideArchived(id: String, archived: Boolean) {
        if (archived && _selectedRide.value?.id == id) dismissSelectedRide()
        _selectedRide.update { if (it?.id == id) it.copy(archivedAt = if (archived) System.currentTimeMillis() else null) else it }
        scope.launch { repository.setRideArchived(id, archived) }
    }
}
