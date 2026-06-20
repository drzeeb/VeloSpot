package de.velospot.feature.map.presentation.places

import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.ParkedBike
import de.velospot.domain.repository.ParkedBikeRepository
import de.velospot.feature.map.presentation.MapCameraTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the "parked bike" concern: where the user left their bike and the state
 * of its detail sheet. Extracted from `MapViewModel` so the park/pick-up flow
 * (including the background reverse-geocode that enriches the record) lives in
 * one focused place.
 *
 * Cross-cutting effects are injected as callbacks so the controller never
 * reaches for the location pipeline, the geocoder, the toast surface, the custom
 * pin, the other selections or the camera directly.
 */
class ParkedBikeController(
    private val scope: CoroutineScope,
    private val repository: ParkedBikeRepository,
    private val currentLocation: () -> GeoCoordinate?,
    private val reverseGeocode: suspend (Double, Double) -> String?,
    private val onUserMessage: (Int) -> Unit,
    private val onParked: () -> Unit,
    private val clearOtherSelections: () -> Unit,
    private val moveCamera: (MapCameraTarget) -> Unit,
) {
    /** Where the user parked their bike (null when no bike is currently parked). */
    private val _parkedBike = MutableStateFlow<ParkedBike?>(null)
    val parkedBike: StateFlow<ParkedBike?> = _parkedBike.asStateFlow()

    /** True while the parked-bike detail sheet is open. */
    private val _isSheetVisible = MutableStateFlow(false)
    val isSheetVisible: StateFlow<Boolean> = _isSheetVisible.asStateFlow()

    init {
        scope.launch {
            repository.getParkedBikeFlow().collect { _parkedBike.value = it }
        }
    }

    /**
     * Parks the bike at the user's current GPS position. Emits a one-shot user
     * message indicating success — or that the location is unavailable when there
     * is no GPS fix yet.
     */
    fun parkAtCurrentLocation() {
        val location = currentLocation() ?: run {
            onUserMessage(de.velospot.R.string.error_location_unavailable)
            return
        }
        parkAt(location.latitude, location.longitude)
    }

    /**
     * Parks the bike at an explicit coordinate (e.g. a tapped custom pin). Any
     * transient custom pin is dismissed; the persistent parked-bike marker takes
     * its place. The street address is reverse-geocoded in the background.
     */
    fun parkAt(latitude: Double, longitude: Double) {
        val bike = ParkedBike(
            latitude  = latitude,
            longitude = longitude,
            parkedAt  = System.currentTimeMillis(),
            address   = null
        )
        scope.launch { repository.park(bike) }
        onParked()
        onUserMessage(de.velospot.R.string.parked_bike_saved)
        // Resolve the street address in the background and persist the enriched record.
        scope.launch {
            val address = runCatching { reverseGeocode(latitude, longitude) }.getOrNull()
            if (address != null && _parkedBike.value?.parkedAt == bike.parkedAt) {
                repository.park(bike.copy(address = address))
            }
        }
    }

    /** Opens the parked-bike detail sheet and centres the camera on the marker. */
    fun showDetail() {
        val bike = _parkedBike.value ?: return
        clearOtherSelections()
        _isSheetVisible.value = true
        moveCamera(
            MapCameraTarget(
                latitude               = bike.latitude,
                longitude              = bike.longitude,
                zoom                   = 17.0,
                verticalOffsetFraction = 1.0 / 6.0
            )
        )
    }

    fun hideSheet() {
        _isSheetVisible.value = false
    }

    /** The user collected their bike — clears the stored location and marker. */
    fun pickUp() {
        _isSheetVisible.value = false
        scope.launch { repository.clear() }
    }
}

