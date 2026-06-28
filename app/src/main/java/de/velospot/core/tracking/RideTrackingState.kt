package de.velospot.core.tracking

import de.velospot.domain.model.LiveRideStats

/**
 * State of the live ride-tracking ("record my ride") feature.
 *
 * Produced by the process-level [RideRecordingManager] and consumed by the map
 * UI, the foreground service, the Quick Settings tile and the home-screen widget.
 * It lives in `core.tracking` (next to its producer) rather than in the
 * presentation layer so the recording stack never depends "upwards" on the UI.
 */
sealed class RideTrackingUiState {
    data object Idle : RideTrackingUiState()
    data class Recording(val stats: LiveRideStats) : RideTrackingUiState()
}

