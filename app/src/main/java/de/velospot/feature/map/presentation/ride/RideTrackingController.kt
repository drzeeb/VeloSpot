package de.velospot.feature.map.presentation.ride

import de.velospot.core.tracking.RideRecordingEvent
import de.velospot.core.tracking.RideRecordingManager
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.feature.map.presentation.MapCameraTarget
import de.velospot.feature.map.presentation.RideTrackingUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 */
class RideTrackingController(
    private val scope: CoroutineScope,
    private val repository: RecordedRidesRepository,
    private val manager: RideRecordingManager,
    private val currentLocation: () -> GeoCoordinate?,
    private val onUserMessage: (Int) -> Unit,
    private val clearOtherSelections: () -> Unit,
    private val moveCamera: (MapCameraTarget) -> Unit,
) {
    val trackingState: StateFlow<RideTrackingUiState> = manager.trackingState

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
    val trackPoints: StateFlow<List<RoutePoint>> = combine(
        manager.liveTrackPoints,
        manager.trackingState,
        _selectedRide
    ) { live, state, selected ->
        if (state is RideTrackingUiState.Recording) live
        else selected?.points?.map { RoutePoint(it.latitude, it.longitude) } ?: emptyList()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val isRecording: Boolean get() = manager.isRecording

    /** Whether the current recording was auto-started by navigation (vs. the FAB). */
    val isAutoStartedByNavigation: Boolean get() = manager.isAutoStartedByNavigation

    init {
        scope.launch {
            repository.getRidesFlow().collect { _recordedRides.value = it }
        }
        // Surface recording outcomes (saved / too short) and open the detail sheet
        // for a freshly-saved ride when the app is in the foreground.
        scope.launch {
            manager.events.collect { event ->
                when (event) {
                    is RideRecordingEvent.Saved -> {
                        onUserMessage(de.velospot.R.string.ride_saved)
                        selectRide(event.ride)
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

    /** Discards the active recording without saving anything. */
    fun discard() = manager.discard()

    /** Feeds a (simulated) GPS fix into the active recording. */
    fun feed(location: GeoCoordinate) = manager.feedExternal(location)

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

    /** Opens the detail sheet for a recorded ride and draws its track on the map. */
    fun selectRide(ride: RecordedRide) {
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
    }

    fun deleteRide(id: String) {
        if (_selectedRide.value?.id == id) dismissSelectedRide()
        scope.launch { repository.removeRide(id) }
    }
}
