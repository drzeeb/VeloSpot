package de.velospot.feature.map.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.core.routing.OfflineRoutingPreferences
import de.velospot.core.routing.isWifiConnected
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.geocoding.NominatimGeocoder
import de.velospot.domain.model.AddressSearchResult
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.BoundingBox
import de.velospot.domain.model.BRouterProfilesMissingException
import de.velospot.domain.model.EmptyRouteGeometryException
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoInternetConnectionException
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

private const val MAX_VIEWPORT_SPAN_DEG = 1.5
private const val VIEWPORT_DEBOUNCE_MS  = 300L
private const val SEARCH_DEBOUNCE_MS    = 400L
private const val SEARCH_MIN_CHARS      = 3

// Default map center used when no GPS fix is available yet.
private const val DEFAULT_LAT = 49.7596
private const val DEFAULT_LON = 6.6441

data class MapCameraTarget(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val verticalOffsetFraction: Double = 0.0
)

sealed class NavigationUiState {
    data object Idle : NavigationUiState()
    data object Loading : NavigationUiState()
    data class DownloadingSegments(val progress: Float) : NavigationUiState()
    data class Active(val destination: BikeParkingSpace, val route: BikeRoute) : NavigationUiState()
    data class Error(val error: MapError) : NavigationUiState()
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val bikeParkingRepository: BikeParkingRepository,
    private val favoritesRepository: FavoritesRepository,
    private val locationRepository: LocationRepository,
    private val routingRepository: RoutingRepository,
    private val segmentManager: BRouterSegmentManager,
    private val nominatimGeocoder: NominatimGeocoder,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _selectedSpace = MutableStateFlow<BikeParkingSpace?>(null)
    val selectedSpace: StateFlow<BikeParkingSpace?> = _selectedSpace.asStateFlow()

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private val _favoriteSpaces = MutableStateFlow<List<BikeParkingSpace>>(emptyList())
    val favoriteSpaces: StateFlow<List<BikeParkingSpace>> = _favoriteSpaces.asStateFlow()

    private val _userLocation = MutableStateFlow<GeoCoordinate?>(null)
    val userLocation: StateFlow<GeoCoordinate?> = _userLocation.asStateFlow()

    private val _mapCameraTarget = MutableStateFlow<MapCameraTarget?>(null)
    val mapCameraTarget: StateFlow<MapCameraTarget?> = _mapCameraTarget.asStateFlow()

    private val _navigationUiState = MutableStateFlow<NavigationUiState>(NavigationUiState.Idle)
    val navigationUiState: StateFlow<NavigationUiState> = _navigationUiState.asStateFlow()

    // ── Address search ────────────────────────────────────────────────────────

    private val _searchQuery   = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<AddressSearchResult>>(emptyList())
    val searchResults: StateFlow<List<AddressSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching   = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    /** The address pin currently shown on the map (set when user taps a search result). */
    private val _selectedSearchPin = MutableStateFlow<AddressSearchResult?>(null)
    val selectedSearchPin: StateFlow<AddressSearchResult?> = _selectedSearchPin.asStateFlow()

    /** A freely placed pin set by tapping on an empty map location. */
    private val _customMapPin = MutableStateFlow<GeoCoordinate?>(null)
    val customMapPin: StateFlow<GeoCoordinate?> = _customMapPin.asStateFlow()

    /** Resolved address for the current custom pin (null while loading or unavailable). */
    private val _customMapPinAddress = MutableStateFlow<String?>(null)
    val customMapPinAddress: StateFlow<String?> = _customMapPinAddress.asStateFlow()

    fun onSearchQueryChanged(query: String) {        _searchQuery.value = query
        searchJob?.cancel()
        if (query.length < SEARCH_MIN_CHARS) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _isSearching.value = true
            _searchResults.value = nominatimGeocoder.searchAddress(query)
            _isSearching.value = false
        }
    }

    fun onSearchCleared() {
        searchJob?.cancel()
        _searchQuery.value    = ""
        _searchResults.value  = emptyList()
        _isSearching.value    = false
        _selectedSearchPin.value = null
    }

    /**
     * Called when the user taps on an empty map location.
     * Places a custom pin there and dismisses any open space/search-pin sheets.
     */
    fun onMapTapped(lat: Double, lon: Double) {
        _selectedSpace.value     = null
        _selectedSearchPin.value = null
        _customMapPin.value      = GeoCoordinate(lat, lon)
        _customMapPinAddress.value = null   // reset while new address loads
        _mapCameraTarget.value   = MapCameraTarget(
            latitude               = lat,
            longitude              = lon,
            zoom                   = 15.0,
            verticalOffsetFraction = 1.0 / 6.0
        )
        viewModelScope.launch {
            _customMapPinAddress.value = nominatimGeocoder.reverseGeocode(lat, lon)
        }
    }

    fun dismissCustomMapPin() {
        _customMapPin.value        = null
        _customMapPinAddress.value = null
    }

    /** Starts in-app navigation to the freely placed custom pin. */
    fun startNavigationToCustomPin() {
        val pin     = _customMapPin.value ?: return
        val address = _customMapPinAddress.value
        // Pin stays on the map so the route end-point remains visible during navigation.
        val syntheticSpace = BikeParkingSpace(
            id          = "custom_map_pin",
            latitude    = pin.latitude,
            longitude   = pin.longitude,
            type        = BikeParkingType.UNKNOWN,
            capacity    = null,
            name        = address?.substringBefore(",")?.trim()
                            ?: context.getString(de.velospot.R.string.custom_pin_title),
            address     = address,
            isCovered   = null,
            imageUrl    = null,
            operator    = null,
            sourceLayer = "custom"
        )
        startInAppNavigation(syntheticSpace)
    }

    /**
     * Drops a pin at [result], animates the camera there, and opens the detail sheet.
     * Collapses the search dropdown but keeps the pin visible until dismissed.
     */
    fun onSearchResultSelected(result: AddressSearchResult) {
        _customMapPin.value      = null
        _selectedSearchPin.value = result
        _searchResults.value     = emptyList()   // collapse dropdown
        _mapCameraTarget.value   = MapCameraTarget(
            latitude             = result.latitude,
            longitude            = result.longitude,
            zoom                 = 15.0,
            verticalOffsetFraction = 1.0 / 6.0  // shift up so sheet doesn't cover pin
        )
    }

    fun dismissSearchPin() {
        _selectedSearchPin.value = null
    }

    /** Starts in-app navigation to a free-form address [result] (not a parking spot). */
    fun startNavigationToAddress(result: AddressSearchResult) {
        // Wrap the address as a synthetic BikeParkingSpace so the existing routing
        // and navigation overlay work without modification.
        val syntheticSpace = BikeParkingSpace(
            id          = "address_search_pin",
            latitude    = result.latitude,
            longitude   = result.longitude,
            type        = BikeParkingType.UNKNOWN,
            capacity    = null,
            name        = result.displayName.substringBefore(",").trim(),
            address     = result.displayName,
            isCovered   = null,
            imageUrl    = null,
            operator    = null,
            sourceLayer = "search"
        )
        _selectedSearchPin.value = null
        startInAppNavigation(syntheticSpace)
    }

    // ── Offline routing ───────────────────────────────────────────────────────

    private val _offlineRoutingUiState = MutableStateFlow(initialOfflineState())
    val offlineRoutingUiState: StateFlow<OfflineRoutingUiState> = _offlineRoutingUiState.asStateFlow()

    /** True while the setup bottom sheet should be visible. */
    private val _showOfflineSetupSheet = MutableStateFlow(false)
    val showOfflineSetupSheet: StateFlow<Boolean> = _showOfflineSetupSheet.asStateFlow()

    /** True while the profile selection sheet should be visible. */
    private val _showProfileSheet = MutableStateFlow(false)
    val showProfileSheet: StateFlow<Boolean> = _showProfileSheet.asStateFlow()

    /** True when download was requested but device is not on Wi-Fi. */
    private val _showWifiWarning = MutableStateFlow(false)
    val showWifiWarning: StateFlow<Boolean> = _showWifiWarning.asStateFlow()

    // ── Init ──────────────────────────────────────────────────────────────────

    private var viewportJob: Job? = null

    init {
        loadSpacesForViewport(BoundingBox.DEFAULT)
        observeFavorites()
        observeUserLocation()
    }

    // ── Viewport / parking ────────────────────────────────────────────────────

    fun onViewportChanged(bbox: BoundingBox) {
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
            if (_uiState.value !is MapUiState.Success) _uiState.value = MapUiState.Loading
            runCatching { bikeParkingRepository.getSpacesInBoundingBox(bbox) }
                .onSuccess { _uiState.value = MapUiState.Success(it) }
                .onFailure { if (_uiState.value !is MapUiState.Success) _uiState.value = MapUiState.Error(MapError.Unknown(it.message)) }
        }
    }

    // ── Space selection ───────────────────────────────────────────────────────

    fun selectSpace(space: BikeParkingSpace?) {
        _selectedSpace.update { space }
        if (space != null) {
            _customMapPin.value    = null
            _selectedSearchPin.value = null
            _mapCameraTarget.value = MapCameraTarget(
                latitude = space.latitude, longitude = space.longitude,
                zoom = 16.5, verticalOffsetFraction = 1.0 / 6.0
            )
            if (space.address == null) resolveAddressForSelectedSpace(space)
        }
    }

    private fun resolveAddressForSelectedSpace(space: BikeParkingSpace) {
        viewModelScope.launch {
            val resolved = runCatching { bikeParkingRepository.resolveAddress(space) }.getOrElse { space }
            if (_selectedSpace.value?.id == space.id && resolved.address != null) _selectedSpace.value = resolved
        }
    }

    // ── Favorites ─────────────────────────────────────────────────────────────

    fun toggleFavorite(parkingSpaceId: String) {
        viewModelScope.launch {
            if (favoritesRepository.isFavorite(parkingSpaceId)) favoritesRepository.removeFavorite(parkingSpaceId)
            else favoritesRepository.addFavorite(parkingSpaceId)
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoritesRepository.getFavoritesFlow().collect { favoriteIds ->
                _favorites.value = favoriteIds
                val spaces = runCatching { bikeParkingRepository.getSpacesByIds(favoriteIds) }.getOrDefault(emptyList())
                _favoriteSpaces.value = spaces
            }
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private fun observeUserLocation() {
        locationRepository.startLocationUpdates()
        viewModelScope.launch {
            locationRepository.getCurrentLocationFlow().collect { _userLocation.value = it }
        }
    }

    fun onLocationPermissionGranted() = locationRepository.startLocationUpdates()

    fun centerMapOnUserLocation() {
        _userLocation.value?.let {
            _mapCameraTarget.value = MapCameraTarget(it.latitude, it.longitude, zoom = 16.0)
        }
    }

    fun onMapCameraTargetHandled() { _mapCameraTarget.value = null }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun startInAppNavigation(space: BikeParkingSpace) {
        val location = _userLocation.value ?: run {
            _navigationUiState.value = NavigationUiState.Error(MapError.LocationUnavailable)
            return
        }
        _navigationUiState.value = NavigationUiState.Loading
        viewModelScope.launch {
            runCatching {
                routingRepository.getBikeRoute(
                    from = location,
                    to   = GeoCoordinate(space.latitude, space.longitude)
                )
            }.onSuccess {
                _navigationUiState.value = NavigationUiState.Active(destination = space, route = it)
            }.onFailure { throwable ->
                _navigationUiState.value = NavigationUiState.Error(when (throwable) {
                    is BRouterProfilesMissingException -> MapError.BRouterProfilesMissing
                    is RoutingFailedException          -> MapError.RoutingFailed(throwable.code)
                    is NoRouteFoundException           -> MapError.NoRouteFound
                    is EmptyRouteGeometryException     -> MapError.EmptyRouteGeometry
                    else                               -> MapError.Unknown(throwable.message)
                })
            }
        }
    }

    fun stopInAppNavigation() {
        val wasCustomPin = (_navigationUiState.value as? NavigationUiState.Active)
            ?.destination?.id == "custom_map_pin"
        _navigationUiState.value = NavigationUiState.Idle
        if (wasCustomPin) {
            _customMapPin.value        = null
            _customMapPinAddress.value = null
        }
    }

    fun clearNavigationError() { if (_navigationUiState.value is NavigationUiState.Error) _navigationUiState.value = NavigationUiState.Idle }

    // ── Offline routing ───────────────────────────────────────────────────────

    /** Opens the setup information sheet. */
    fun requestOfflineRoutingSetup() { _showOfflineSetupSheet.value = true }
    fun dismissOfflineSetupSheet()   { _showOfflineSetupSheet.value = false }

    fun openProfileSheet()    { _showProfileSheet.value = true }
    fun dismissProfileSheet() { _showProfileSheet.value = false }

    fun dismissWifiWarning()          { _showWifiWarning.value = false }
    fun confirmDownloadOnMobileData() { _showWifiWarning.value = false; startSegmentDownload() }

    /**
     * Called after the user taps "Jetzt herunterladen" in the setup sheet.
     * Shows a Wi-Fi warning when the device is not on Wi-Fi.
     */
    fun confirmOfflineRoutingSetup() {
        _showOfflineSetupSheet.value = false
        if (!isWifiConnected(context)) {
            _showWifiWarning.value = true
            return
        }
        startSegmentDownload()
    }

    private fun startSegmentDownload() {
        val loc = _userLocation.value ?: GeoCoordinate(DEFAULT_LAT, DEFAULT_LON)
        _offlineRoutingUiState.value = OfflineRoutingUiState.Downloading()
        viewModelScope.launch {
            runCatching {
                segmentManager.downloadSegmentsForLocation(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    onProgress = { downloaded, total, fileIndex, totalFiles, fileName ->
                        val progress = if (total > 0L) downloaded / total.toFloat() else -1f
                        _offlineRoutingUiState.value = OfflineRoutingUiState.Downloading(
                            fileProgress      = progress,
                            currentFileIndex  = fileIndex,
                            totalFiles        = totalFiles,
                            downloadedBytes   = downloaded,
                            totalBytes        = total,
                            currentFile       = fileName
                        )
                    }
                )
            }.onSuccess {
                val profile = OfflineRoutingPreferences.getSelectedProfile(context)
                OfflineRoutingPreferences.setOfflineRoutingEnabled(context, true)
                // Show success state briefly, then switch to Enabled
                _offlineRoutingUiState.value = OfflineRoutingUiState.DownloadComplete(profile)
                delay(2_500L)
                _offlineRoutingUiState.value = OfflineRoutingUiState.Enabled(profile)
            }.onFailure { throwable ->
                _offlineRoutingUiState.value = OfflineRoutingUiState.Disabled
                val error = when (throwable) {
                    is NoInternetConnectionException -> MapError.NoInternetConnection
                    else                             -> MapError.Unknown(throwable.message)
                }
                _navigationUiState.value = NavigationUiState.Error(error)
            }
        }
    }

    /** Switches the active routing profile without re-downloading. */
    fun selectRoutingProfile(profile: de.velospot.data.brouter.BRouterProfile) {
        OfflineRoutingPreferences.setSelectedProfile(context, profile)
        _offlineRoutingUiState.value = OfflineRoutingUiState.Enabled(profile)
    }

    /** Disables offline routing and wipes all downloaded segment files. */
    fun disableOfflineRouting() {
        OfflineRoutingPreferences.setOfflineRoutingEnabled(context, false)
        _offlineRoutingUiState.value = OfflineRoutingUiState.Disabled
        viewModelScope.launch { segmentManager.deleteAllSegments() }
    }

    private fun initialOfflineState(): OfflineRoutingUiState =
        if (OfflineRoutingPreferences.isOfflineRoutingEnabled(context))
            OfflineRoutingUiState.Enabled(OfflineRoutingPreferences.getSelectedProfile(context))
        else
            OfflineRoutingUiState.Disabled

    override fun onCleared() {
        super.onCleared()
        locationRepository.stopLocationUpdates()
    }
}
