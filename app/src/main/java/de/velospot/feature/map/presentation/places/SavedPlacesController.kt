package de.velospot.feature.map.presentation.places

import de.velospot.domain.model.SavedPlace
import de.velospot.domain.repository.SavedPlacesRepository
import de.velospot.feature.map.presentation.MapCameraTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns the "saved places" concern: the persisted list of named pins and the
 * place whose detail sheet is currently open. Extracted from `MapViewModel` so
 * persistence and selection live behind a small, focused API.
 *
 * The map-level side effects of opening a place are passed in as callbacks
 * ([clearOtherSelections], [moveCamera]) so this controller stays unaware of the
 * other map selections and the camera.
 */
class SavedPlacesController(
    private val scope: CoroutineScope,
    private val repository: SavedPlacesRepository,
    private val clearOtherSelections: () -> Unit,
    private val moveCamera: (MapCameraTarget) -> Unit,
) {
    /** All user-saved custom places (manually placed pins saved as named favourites). */
    private val _savedPlaces = MutableStateFlow<List<SavedPlace>>(emptyList())
    val savedPlaces: StateFlow<List<SavedPlace>> = _savedPlaces.asStateFlow()

    /** The saved place whose detail sheet is currently open (null when none). */
    private val _selectedPlace = MutableStateFlow<SavedPlace?>(null)
    val selectedPlace: StateFlow<SavedPlace?> = _selectedPlace.asStateFlow()

    init {
        scope.launch {
            repository.getSavedPlacesFlow().collect { _savedPlaces.value = it }
        }
    }

    /** Persists a new named [SavedPlace] with a fresh id and timestamp. */
    fun persist(name: String, latitude: Double, longitude: Double, address: String?) {
        scope.launch {
            repository.savePlace(
                SavedPlace(
                    id        = UUID.randomUUID().toString(),
                    name      = name,
                    latitude  = latitude,
                    longitude = longitude,
                    address   = address,
                    addedAt   = System.currentTimeMillis()
                )
            )
        }
    }

    /** Opens the detail sheet for a saved place and centres the camera on it. */
    fun select(place: SavedPlace) {
        clearOtherSelections()
        _selectedPlace.value = place
        moveCamera(
            MapCameraTarget(
                latitude               = place.latitude,
                longitude              = place.longitude,
                zoom                   = 16.0,
                verticalOffsetFraction = 1.0 / 6.0
            )
        )
    }

    /** Closes the open detail sheet (no-op when none is open). */
    fun clearSelection() {
        _selectedPlace.value = null
    }

    fun remove(id: String) {
        if (_selectedPlace.value?.id == id) _selectedPlace.value = null
        scope.launch { repository.removePlace(id) }
    }
}

