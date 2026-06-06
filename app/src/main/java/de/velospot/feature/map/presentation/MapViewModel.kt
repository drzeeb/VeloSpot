package de.velospot.feature.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velospot.data.location.LocationRepositoryImpl
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.LocationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import javax.inject.Inject

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
     * Check if a specific parking space is marked as favorite.
     */
    suspend fun isFavorite(parkingSpaceId: String): Boolean {
        return favoritesRepository.isFavorite(parkingSpaceId)
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
        if (locationRepository is LocationRepositoryImpl) {
            locationRepository.startLocationUpdates()
        }
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
        if (locationRepository is LocationRepositoryImpl) {
            locationRepository.startLocationUpdates()
        }
    }

    /**
     * Center the map on the user's current location.
     */
    fun centerMapOnUserLocation(mapView: MapView) {
        val location = _userLocation.value
        if (location != null) {
            mapView.controller.apply {
                setCenter(GeoPoint(location.first, location.second))
                setZoom(16.0)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (locationRepository is LocationRepositoryImpl) {
            locationRepository.stopLocationUpdates()
        }
    }
}

