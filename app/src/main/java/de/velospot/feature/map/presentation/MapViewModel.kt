package de.velospot.feature.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.LocationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapCameraTarget(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val verticalOffsetFraction: Double = 0.0
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val bikeParkingRepository: BikeParkingRepository,
    private val favoritesRepository: FavoritesRepository,
    private val locationRepository: LocationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _selectedSpace = MutableStateFlow<BikeParkingSpace?>(null)
    val selectedSpace: StateFlow<BikeParkingSpace?> = _selectedSpace.asStateFlow()

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private val _userLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val userLocation: StateFlow<Pair<Double, Double>?> = _userLocation.asStateFlow()

    private val _mapCameraTarget = MutableStateFlow<MapCameraTarget?>(null)
    val mapCameraTarget: StateFlow<MapCameraTarget?> = _mapCameraTarget.asStateFlow()

    init {
        loadParkingSpaces()
        observeFavorites()
        observeUserLocation()
    }

    fun loadParkingSpaces() {
        _uiState.value = MapUiState.Loading
        viewModelScope.launch {
            runCatching {
                bikeParkingRepository.getBikeParkingSpaces()
            }.onSuccess { spaces ->
                _uiState.value = MapUiState.Success(spaces)
            }.onFailure { throwable ->
                _uiState.value = MapUiState.Error(
                    throwable.message ?: "Fahrradstellplaetze konnten nicht geladen werden."
                )
            }
        }
    }

    fun selectSpace(space: BikeParkingSpace?) {
        _selectedSpace.update { space }
        // Keep the selected marker visible above the bottom sheet and zoom in for details.
        if (space != null) {
            _mapCameraTarget.value = MapCameraTarget(
                latitude = space.latitude,
                longitude = space.longitude,
                zoom = 16.5,
                // Place marker at 1/3 screen height: center (1/2) minus 1/6 offset.
                verticalOffsetFraction = 1.0 / 6.0
            )
        }
    }

    /**
     * Toggle favorite status of a parking space.
     */
    fun toggleFavorite(parkingSpaceId: String) {
        viewModelScope.launch {
            if (favoritesRepository.isFavorite(parkingSpaceId)) {
                favoritesRepository.removeFavorite(parkingSpaceId)
            } else {
                favoritesRepository.addFavorite(parkingSpaceId)
            }
        }
    }


    /**
     * Start listening to favorite parking spaces.
     */
    private fun observeFavorites() {
        viewModelScope.launch {
            favoritesRepository.getFavoritesFlow().collect { favorites ->
                _favorites.value = favorites
            }
        }
    }

    /**
     * Start listening to user location updates.
     */
    private fun observeUserLocation() {
        locationRepository.startLocationUpdates()
        viewModelScope.launch {
            locationRepository.getCurrentLocationFlow().collect { location ->
                _userLocation.value = location
            }
        }
    }

    /**
     * Restart location updates after the user granted runtime permissions.
     */
    fun onLocationPermissionGranted() {
        locationRepository.startLocationUpdates()
    }

    /**
     * Emit a camera target that the UI can apply to the map.
     */
    fun centerMapOnUserLocation() {
        val location = _userLocation.value
        if (location != null) {
            _mapCameraTarget.value = MapCameraTarget(
                latitude = location.first,
                longitude = location.second,
                zoom = 16.0
            )
        }
    }

    fun onMapCameraTargetHandled() {
        _mapCameraTarget.value = null
    }

    override fun onCleared() {
        super.onCleared()
        locationRepository.stopLocationUpdates()
    }
}

