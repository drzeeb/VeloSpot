package de.velospot.feature.map.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BoundingBox
import de.velospot.domain.model.EmptyRouteGeometryException
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoRouteFoundException
import de.velospot.domain.model.RoutingFailedException
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.RoutingRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// Minimum bounding-box span (degrees) below which we still load.
// At roughly 1° ≈ 111 km this keeps queries fast even at low zoom.
private const val MAX_VIEWPORT_SPAN_DEG = 1.5

// Debounce delay in milliseconds – avoids a DB query on every scroll frame.
private const val VIEWPORT_DEBOUNCE_MS = 300L

data class MapCameraTarget(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val verticalOffsetFraction: Double = 0.0
)

sealed class NavigationUiState {
    data object Idle : NavigationUiState()
    data object Loading : NavigationUiState()
    data class Active(
        val destination: BikeParkingSpace,
        val route: BikeRoute
    ) : NavigationUiState()
    data class Error(val error: MapError) : NavigationUiState()
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val bikeParkingRepository: BikeParkingRepository,
    private val favoritesRepository: FavoritesRepository,
    private val locationRepository: LocationRepository,
    private val routingRepository: RoutingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _selectedSpace = MutableStateFlow<BikeParkingSpace?>(null)
    val selectedSpace: StateFlow<BikeParkingSpace?> = _selectedSpace.asStateFlow()

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    /** Full BikeParkingSpace objects for the current favorites (may lie outside the viewport). */
    private val _favoriteSpaces = MutableStateFlow<List<BikeParkingSpace>>(emptyList())
    val favoriteSpaces: StateFlow<List<BikeParkingSpace>> = _favoriteSpaces.asStateFlow()

    private val _userLocation = MutableStateFlow<GeoCoordinate?>(null)
    val userLocation: StateFlow<GeoCoordinate?> = _userLocation.asStateFlow()

    private val _mapCameraTarget = MutableStateFlow<MapCameraTarget?>(null)
    val mapCameraTarget: StateFlow<MapCameraTarget?> = _mapCameraTarget.asStateFlow()

    private val _navigationUiState = MutableStateFlow<NavigationUiState>(NavigationUiState.Idle)
    val navigationUiState: StateFlow<NavigationUiState> = _navigationUiState.asStateFlow()

    private var viewportJob: Job? = null

    init {
        // Kick off with the default viewport (Trier area) so the map isn't empty
        // until the user moves the camera for the first time.
        loadSpacesForViewport(BoundingBox.DEFAULT)
        observeFavorites()
        observeUserLocation()
    }

    /**
     * Called by the map UI whenever the visible viewport changes (scroll or zoom).
     * Debounces rapid camera movements to avoid a DB query on every frame.
     */
    fun onViewportChanged(bbox: BoundingBox) {
        // Skip tiny viewports (extreme zoom-out) – the query result would be huge.
        val latSpan = bbox.maxLat - bbox.minLat
        val lonSpan = bbox.maxLon - bbox.minLon
        if (latSpan > MAX_VIEWPORT_SPAN_DEG || lonSpan > MAX_VIEWPORT_SPAN_DEG) return

        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(VIEWPORT_DEBOUNCE_MS)
            loadSpacesForViewport(bbox)
        }
    }

    private fun loadSpacesForViewport(bbox: BoundingBox) {
        viewModelScope.launch {
            // Keep the loading state only if we have no data yet.
            if (_uiState.value !is MapUiState.Success) {
                _uiState.value = MapUiState.Loading
            }
            runCatching {
                bikeParkingRepository.getSpacesInBoundingBox(bbox)
            }.onSuccess { spaces ->
                _uiState.value = MapUiState.Success(spaces)
            }.onFailure { throwable ->
                // Don't overwrite valid visible data with an error.
                if (_uiState.value !is MapUiState.Success) {
                    _uiState.value = MapUiState.Error(MapError.Unknown(throwable.message))
                }
            }
        }
    }

    fun selectSpace(space: BikeParkingSpace?) {
        _selectedSpace.update { space }
        if (space != null) {
            _mapCameraTarget.value = MapCameraTarget(
                latitude = space.latitude,
                longitude = space.longitude,
                zoom = 16.5,
                verticalOffsetFraction = 1.0 / 6.0
            )
            // If no address is known yet, resolve it lazily via Nominatim.
            // The coroutine updates _selectedSpace once the address arrives so the
            // bottom sheet refreshes without requiring any user interaction.
            if (space.address == null) {
                resolveAddressForSelectedSpace(space)
            }
        }
    }

    /**
     * Calls Nominatim in the background. If the address is found and the user
     * still has the same space selected, the state is updated so the UI reacts.
     */
    private fun resolveAddressForSelectedSpace(space: BikeParkingSpace) {
        viewModelScope.launch {
            val resolved = runCatching {
                bikeParkingRepository.resolveAddress(space)
            }.getOrElse { space }

            // Only update if the user hasn't already navigated to another space.
            if (_selectedSpace.value?.id == space.id && resolved.address != null) {
                _selectedSpace.value = resolved
            }
        }
    }

    fun toggleFavorite(parkingSpaceId: String) {
        viewModelScope.launch {
            if (favoritesRepository.isFavorite(parkingSpaceId)) {
                favoritesRepository.removeFavorite(parkingSpaceId)
            } else {
                favoritesRepository.addFavorite(parkingSpaceId)
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoritesRepository.getFavoritesFlow().collect { favoriteIds ->
                _favorites.value = favoriteIds
                // Resolve full space objects so the FavoritesSheet has all details
                // even for spaces that are currently outside the map viewport.
                // Swallow errors silently — a failed lookup just means the sheet
                // shows no details for that favorite until the next successful query.
                val spaces = runCatching {
                    bikeParkingRepository.getSpacesByIds(favoriteIds)
                }.getOrDefault(emptyList())
                _favoriteSpaces.value = spaces
            }
        }
    }

    private fun observeUserLocation() {
        locationRepository.startLocationUpdates()
        viewModelScope.launch {
            locationRepository.getCurrentLocationFlow().collect { location ->
                _userLocation.value = location
            }
        }
    }

    fun onLocationPermissionGranted() {
        locationRepository.startLocationUpdates()
    }

    fun centerMapOnUserLocation() {
        val location = _userLocation.value
        if (location != null) {
            _mapCameraTarget.value = MapCameraTarget(
                latitude = location.latitude,
                longitude = location.longitude,
                zoom = 16.0
            )
        }
    }

    fun onMapCameraTargetHandled() {
        _mapCameraTarget.value = null
    }

    fun startInAppNavigation(space: BikeParkingSpace) {
        val location = _userLocation.value
        if (location == null) {
            _navigationUiState.value = NavigationUiState.Error(MapError.LocationUnavailable)
            return
        }

        _navigationUiState.value = NavigationUiState.Loading

        viewModelScope.launch {
            runCatching {
                routingRepository.getBikeRoute(
                    from = location,
                    to = GeoCoordinate(space.latitude, space.longitude)
                )
            }.onSuccess { route ->
                _navigationUiState.value = NavigationUiState.Active(
                    destination = space,
                    route = route
                )
            }.onFailure { throwable ->
                val error = when (throwable) {
                    is RoutingFailedException -> MapError.RoutingFailed(throwable.code)
                    is NoRouteFoundException -> MapError.NoRouteFound
                    is EmptyRouteGeometryException -> MapError.EmptyRouteGeometry
                    else -> MapError.Unknown(throwable.message)
                }
                _navigationUiState.value = NavigationUiState.Error(error)
            }
        }
    }

    fun stopInAppNavigation() {
        _navigationUiState.value = NavigationUiState.Idle
    }

    fun clearNavigationError() {
        if (_navigationUiState.value is NavigationUiState.Error) {
            _navigationUiState.value = NavigationUiState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationRepository.stopLocationUpdates()
    }
}
