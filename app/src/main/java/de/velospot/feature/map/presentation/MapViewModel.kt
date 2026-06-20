package de.velospot.feature.map.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.core.map.LayerVisibility
import de.velospot.core.map.LayerVisibilityPreferences
import de.velospot.core.map.MapLayerCategory
import de.velospot.core.navigation.NavigationModePreferences
import de.velospot.core.navigation.RouteSimulator
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
import de.velospot.domain.model.LiveRideStats
import de.velospot.domain.model.MapError
import de.velospot.domain.model.NoRouteFoundException
import de.velospot.domain.model.ParkedBike
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RoutingFailedException
import de.velospot.domain.model.SavedPlace
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.ParkedBikeRepository
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.domain.repository.RoutingRepository
import de.velospot.domain.repository.SavedPlacesRepository
import de.velospot.feature.map.presentation.offline.OfflineRoutingController
import de.velospot.feature.map.presentation.places.ParkedBikeController
import de.velospot.feature.map.presentation.places.SavedPlacesController
import de.velospot.feature.map.presentation.ride.RideTrackingController
import de.velospot.feature.map.presentation.search.AddressSearchController
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

/** Minimum gap between automatic off-route reroutes. */
private const val REROUTE_COOLDOWN_MS   = 8_000L

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

/** State of the live ride-tracking ("record my ride") feature. */
sealed class RideTrackingUiState {
    data object Idle : RideTrackingUiState()
    data class Recording(val stats: LiveRideStats) : RideTrackingUiState()
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val bikeParkingRepository: BikeParkingRepository,
    private val favoritesRepository: FavoritesRepository,
    private val locationRepository: LocationRepository,
    private val routingRepository: RoutingRepository,
    private val segmentManager: BRouterSegmentManager,
    private val nominatimGeocoder: NominatimGeocoder,
    savedPlacesRepository: SavedPlacesRepository,
    parkedBikeRepository: ParkedBikeRepository,
    recordedRidesRepository: RecordedRidesRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        /** ID used for the synthetic BikeParkingSpace created when navigating to a custom map pin. */
        const val ID_CUSTOM_MAP_PIN = "custom_map_pin"
        /** ID used for the synthetic BikeParkingSpace created when navigating to an address search result. */
        const val ID_ADDRESS_SEARCH_PIN = "address_search_pin"
        /** ID used for the synthetic BikeParkingSpace created when navigating to a saved place. */
        const val ID_SAVED_PLACE = "saved_place"
        /** ID used for the synthetic BikeParkingSpace created when navigating to the parked bike. */
        const val ID_PARKED_BIKE = "parked_bike"

        /**
         * Remaining route distance (m) below which the rider counts as "arrived".
         * When navigating to a real bike parking spot, reaching this radius auto-parks
         * the bike at the destination.
         */
        private const val ARRIVAL_THRESHOLD_METERS = 25.0


        /**
         * Synthetic destination IDs that must NOT trigger auto-parking on arrival —
         * only navigation to a genuine bike parking spot from the map data should.
         */
        private val SYNTHETIC_DESTINATION_IDS = setOf(
            ID_CUSTOM_MAP_PIN, ID_ADDRESS_SEARCH_PIN, ID_SAVED_PLACE, ID_PARKED_BIKE
        )
    }

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

    /**
     * Live route progress (remaining distance + ETA) emitted by the
     * `NavigationManager` on each GPS fix; `null` when not navigating. Drives the
     * dynamic distance/ETA readout in the navigation overlay.
     */
    private val _navigationProgress = MutableStateFlow<de.velospot.core.navigation.NavigationProgress?>(null)
    val navigationProgress: StateFlow<de.velospot.core.navigation.NavigationProgress?> = _navigationProgress.asStateFlow()

    /** Pushed from the UI layer's `NavigationManager.onProgress` callback. */
    fun updateNavigationProgress(progress: de.velospot.core.navigation.NavigationProgress) {
        _navigationProgress.value = progress
        maybeAutoParkOnArrival(progress)
    }

    /**
     * Guards against repeatedly auto-parking once the rider has reached a parking
     * spot. Reset whenever a fresh navigation starts.
     */
    private var hasAutoParkedForCurrentRoute = false

    /**
     * Auto-parks the bike the moment the rider arrives at a real bike parking spot.
     * The route tracking already reports the remaining distance on every GPS fix;
     * once it drops below [ARRIVAL_THRESHOLD_METERS] for a genuine parking-spot
     * destination, the bike is parked at the spot and navigation ends — so the
     * persistent marker is dropped without any extra tap.
     */
    private fun maybeAutoParkOnArrival(progress: de.velospot.core.navigation.NavigationProgress) {
        if (hasAutoParkedForCurrentRoute) return
        val destination = (_navigationUiState.value as? NavigationUiState.Active)?.destination ?: return
        // Only navigation to a genuine bike parking spot auto-parks; synthetic
        // destinations (custom pin, address search, saved place, parked bike) don't.
        if (destination.id in SYNTHETIC_DESTINATION_IDS) return
        if (progress.isOffRoute) return
        if (progress.remainingMeters > ARRIVAL_THRESHOLD_METERS) return

        hasAutoParkedForCurrentRoute = true
        parkBikeAt(destination.latitude, destination.longitude)
        // Override the generic "saved" toast with a clearer arrival confirmation.
        _userMessageRes.value = de.velospot.R.string.parked_bike_arrived
        stopInAppNavigation()
    }

    // ── GPS route simulator (debug couch-testing) ─────────────────────────────

    private val routeSimulator = RouteSimulator()

    /** True while the GPS mock simulator is driving along the active route. */
    private val _isSimulatingRoute = MutableStateFlow(false)
    val isSimulatingRoute: StateFlow<Boolean> = _isSimulatingRoute.asStateFlow()

    /**
     * Starts/stops the GPS mock simulator. When running it walks along the active
     * navigation route at ~18 km/h, feeding synthetic fixes (with bearing + speed)
     * into [_userLocation] exactly like real GPS — so the whole live-navigation
     * pipeline (matching, camera, progress, off-route) can be tested on the couch.
     * Real GPS updates are ignored while simulating.
     */
    fun toggleRouteSimulation() {
        if (_isSimulatingRoute.value) {
            stopRouteSimulation()
            return
        }
        val route = (_navigationUiState.value as? NavigationUiState.Active)?.route ?: return
        if (route.points.size < 2) return

        _isSimulatingRoute.value = true
        routeSimulator.start(
            scope = viewModelScope,
            route = route.points,
            // Brisk couch-test pace (~50 km/h), emitting twice a second so the
            // motion stays smooth despite the higher speed.
            speedMps = 13.9,
            intervalMs = 500L,
            jitterMeters = 0.0,
            onFix = { fix ->
                _userLocation.value = fix
                if (rideTracking.isRecording) rideTracking.feed(fix)
            },
            onFinished = { _isSimulatingRoute.value = false }
        )
    }

    fun stopRouteSimulation() {
        routeSimulator.stop()
        _isSimulatingRoute.value = false
    }

    /**
     * One-shot error event for failed viewport reloads (e.g. DB error while panning the map).
     * Set to a non-null message when a reload fails after the initial load succeeded.
     * The UI should clear it after displaying.
     */
    private val _viewportLoadError = MutableStateFlow<String?>(null)
    val viewportLoadError: StateFlow<String?> = _viewportLoadError.asStateFlow()

    fun clearViewportLoadError() { _viewportLoadError.value = null }

    // ── Address search ────────────────────────────────────────────────────────

    /** Owns the debounced address-search concern (query, results, in-flight flag). */
    private val addressSearch = AddressSearchController(
        scope = viewModelScope,
        geocoder = nominatimGeocoder,
        currentLocation = { _userLocation.value }
    )
    val searchQuery: StateFlow<String> = addressSearch.query
    val searchResults: StateFlow<List<AddressSearchResult>> = addressSearch.results
    val isSearching: StateFlow<Boolean> = addressSearch.isSearching

    /** The address pin currently shown on the map (set when user taps a search result). */
    private val _selectedSearchPin = MutableStateFlow<AddressSearchResult?>(null)
    val selectedSearchPin: StateFlow<AddressSearchResult?> = _selectedSearchPin.asStateFlow()

    /** A freely placed pin set by tapping on an empty map location. */
    private val _customMapPin = MutableStateFlow<GeoCoordinate?>(null)
    val customMapPin: StateFlow<GeoCoordinate?> = _customMapPin.asStateFlow()

    /** Resolved address for the current custom pin (null while loading or unavailable). */
    private val _customMapPinAddress = MutableStateFlow<String?>(null)
    val customMapPinAddress: StateFlow<String?> = _customMapPinAddress.asStateFlow()

    /** Owns the saved-places concern (persisted named pins + open detail sheet). */
    private val savedPlacesController = SavedPlacesController(
        scope = viewModelScope,
        repository = savedPlacesRepository,
        clearOtherSelections = { clearPlaceSelections() },
        moveCamera = { target -> _mapCameraTarget.value = target }
    )
    val savedPlaces: StateFlow<List<SavedPlace>> = savedPlacesController.savedPlaces
    val selectedSavedPlace: StateFlow<SavedPlace?> = savedPlacesController.selectedPlace

    /** Owns the parked-bike concern (stored location + detail sheet). */
    private val parkedBikeController = ParkedBikeController(
        scope = viewModelScope,
        repository = parkedBikeRepository,
        currentLocation = { _userLocation.value },
        reverseGeocode = { lat, lon -> nominatimGeocoder.reverseGeocode(lat, lon) },
        onUserMessage = { res -> _userMessageRes.value = res },
        onParked = { dismissCustomMapPin() },
        clearOtherSelections = { clearPlaceSelections() },
        moveCamera = { target -> _mapCameraTarget.value = target }
    )
    val parkedBike: StateFlow<ParkedBike?> = parkedBikeController.parkedBike
    val isParkedBikeSheetVisible: StateFlow<Boolean> = parkedBikeController.isSheetVisible

    /**
     * One-shot, string-resource user message (e.g. "bike location saved").
     * The UI shows it as a Toast and then calls [consumeUserMessage] to clear it.
     */
    private val _userMessageRes = MutableStateFlow<Int?>(null)
    val userMessageRes: StateFlow<Int?> = _userMessageRes.asStateFlow()

    fun consumeUserMessage() { _userMessageRes.value = null }

    /** Which pin categories ("layers") are currently shown on the map. */
    private val _layerVisibility = MutableStateFlow(LayerVisibilityPreferences.get(context))
    val layerVisibility: StateFlow<LayerVisibility> = _layerVisibility.asStateFlow()

    /** Toggles a pin layer's visibility and persists the choice. */
    fun setLayerVisible(category: MapLayerCategory, visible: Boolean) {
        LayerVisibilityPreferences.setVisible(context, category, visible)
        _layerVisibility.update { it.withVisibility(category, visible) }
    }

    /**
     * Whether navigation uses the tilted 3D camera (`true`) or the flat 2D
     * heading-up view (`false`). Persisted across sessions and applied live to
     * the `NavigationManager`, so toggling it mid-navigation re-tilts the camera.
     */
    private val _is3DNavigation = MutableStateFlow(NavigationModePreferences.is3DEnabled(context))
    val is3DNavigation: StateFlow<Boolean> = _is3DNavigation.asStateFlow()

    /** Switches the navigation perspective between 3D and 2D and persists it. */
    fun setNavigation3DEnabled(enabled: Boolean) {
        NavigationModePreferences.set3DEnabled(context, enabled)
        _is3DNavigation.value = enabled
    }

    fun onSearchQueryChanged(query: String) = addressSearch.onQueryChanged(query)

    fun onSearchCleared() {
        addressSearch.clear()
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

    // ── Saved places (custom pins saved as named favourites) ───────────────────

    /**
     * Saves the current custom pin as a named favourite place and dismisses the
     * transient custom pin (it reappears as a persistent saved-place marker).
     */
    fun saveCustomPinAsFavorite(name: String) {
        val pin     = _customMapPin.value ?: return
        val address = _customMapPinAddress.value
        val resolvedName = name.trim().ifBlank {
            address?.substringBefore(",")?.trim()
                ?: context.getString(de.velospot.R.string.saved_place_title)
        }
        savedPlacesController.persist(resolvedName, pin.latitude, pin.longitude, address)
        dismissCustomMapPin()
    }

    fun selectSavedPlace(place: SavedPlace) = savedPlacesController.select(place)

    fun dismissSelectedSavedPlace() = savedPlacesController.clearSelection()

    fun removeSavedPlace(id: String) = savedPlacesController.remove(id)

    /**
     * Clears every transient map selection (parking space, search pin, custom pin,
     * saved place) so opening one detail sheet always dismisses the others. Callers
     * set their own selection immediately afterwards.
     */
    private fun clearPlaceSelections() {
        _selectedSpace.value      = null
        _selectedSearchPin.value  = null
        _customMapPin.value       = null
        savedPlacesController.clearSelection()
    }

    // ── Parked bike (where the user left their bike) ───────────────────────────

    fun parkBikeAtCurrentLocation() = parkedBikeController.parkAtCurrentLocation()

    fun parkBikeAt(latitude: Double, longitude: Double) =
        parkedBikeController.parkAt(latitude, longitude)

    fun showParkedBike() = parkedBikeController.showDetail()

    fun dismissParkedBikeSheet() = parkedBikeController.hideSheet()

    fun pickUpBike() = parkedBikeController.pickUp()

    /**
     * Builds a synthetic [BikeParkingSpace] used to route to a non-parking target
     * (custom pin, address search result, saved place, parked bike). All such
     * targets share the same "unknown facility" fields, so only the identifying
     * data varies — centralising construction here keeps the four navigation
     * entry points DRY and consistent with [SYNTHETIC_DESTINATION_IDS].
     */
    private fun syntheticSpace(
        id: String,
        latitude: Double,
        longitude: Double,
        name: String,
        address: String?,
        sourceLayer: String
    ): BikeParkingSpace = BikeParkingSpace(
        id          = id,
        latitude    = latitude,
        longitude   = longitude,
        type        = BikeParkingType.UNKNOWN,
        capacity    = null,
        name        = name,
        address     = address,
        isCovered   = null,
        imageUrl    = null,
        operator    = null,
        sourceLayer = sourceLayer
    )

    /** Starts in-app navigation back to the parked bike. */
    fun navigateToParkedBike() {
        val bike = parkedBike.value ?: return
        val syntheticSpace = syntheticSpace(
            id          = ID_PARKED_BIKE,
            latitude    = bike.latitude,
            longitude   = bike.longitude,
            name        = context.getString(de.velospot.R.string.parked_bike_title),
            address     = bike.address,
            sourceLayer = "parked_bike"
        )
        parkedBikeController.hideSheet()
        startInAppNavigation(syntheticSpace)
    }

    /** Starts in-app navigation to a saved place. */
    fun navigateToSavedPlace(place: SavedPlace) {
        val syntheticSpace = syntheticSpace(
            id          = ID_SAVED_PLACE,
            latitude    = place.latitude,
            longitude   = place.longitude,
            name        = place.name,
            address     = place.address,
            sourceLayer = "saved"
        )
        savedPlacesController.clearSelection()
        startInAppNavigation(syntheticSpace)
    }

    /** Starts in-app navigation to the freely placed custom pin. */
    fun startNavigationToCustomPin() {
        val pin     = _customMapPin.value ?: return
        val address = _customMapPinAddress.value
        // Pin stays on the map so the route end-point remains visible during navigation.
        val syntheticSpace = syntheticSpace(
            id          = ID_CUSTOM_MAP_PIN,
            latitude    = pin.latitude,
            longitude   = pin.longitude,
            name        = address?.substringBefore(",")?.trim()
                            ?: context.getString(de.velospot.R.string.custom_pin_title),
            address     = address,
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
        addressSearch.collapseResults()   // collapse dropdown
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

    /**
     * Saves the currently selected address-search pin as a named favourite place
     * and dismisses the transient search pin (it reappears as a persistent
     * saved-place marker). Mirrors [saveCustomPinAsFavorite] for the search flow.
     */
    fun saveSearchPinAsFavorite(name: String) {
        val pin = _selectedSearchPin.value ?: return
        val address = pin.displayName
        val resolvedName = name.trim().ifBlank {
            address.substringBefore(",").trim()
                .ifBlank { context.getString(de.velospot.R.string.saved_place_title) }
        }
        savedPlacesController.persist(resolvedName, pin.latitude, pin.longitude, address)
        dismissSearchPin()
    }

    /** Starts in-app navigation to a free-form address [result] (not a parking spot). */
    fun startNavigationToAddress(result: AddressSearchResult) {
        // Wrap the address as a synthetic BikeParkingSpace so the existing routing
        // and navigation overlay work without modification.
        val syntheticSpace = syntheticSpace(
            id          = ID_ADDRESS_SEARCH_PIN,
            latitude    = result.latitude,
            longitude   = result.longitude,
            name        = result.displayName.substringBefore(",").trim(),
            address     = result.displayName,
            sourceLayer = "search"
        )
        _selectedSearchPin.value = null
        startInAppNavigation(syntheticSpace)
    }

    // ── Ride tracking ("record my ride") ──────────────────────────────────────

    /** Owns the "record my ride" concern (recording lifecycle, stats, track, timeline). */
    private val rideTracking = RideTrackingController(
        scope = viewModelScope,
        repository = recordedRidesRepository,
        activeRoute = { (_navigationUiState.value as? NavigationUiState.Active)?.route },
        currentLocation = { _userLocation.value },
        onAccuracyChanged = { refreshLocationAccuracy() },
        onUserMessage = { res -> _userMessageRes.value = res },
        clearOtherSelections = { clearPlaceSelections() },
        moveCamera = { target -> _mapCameraTarget.value = target }
    )
    val rideTrackingState: StateFlow<RideTrackingUiState> = rideTracking.trackingState
    val recordedRides: StateFlow<List<RecordedRide>> = rideTracking.recordedRides
    val selectedRide: StateFlow<RecordedRide?> = rideTracking.selectedRide
    val rideTrackPoints: StateFlow<List<RoutePoint>> = rideTracking.trackPoints

    val isRecordingRide: Boolean get() = rideTracking.isRecording

    fun startRideTracking(autoStarted: Boolean = false) = rideTracking.start(autoStarted)
    fun stopRideTracking() = rideTracking.stop()
    fun discardRideTracking() = rideTracking.discard()
    fun selectRecordedRide(ride: RecordedRide) = rideTracking.selectRide(ride)
    fun dismissSelectedRide() = rideTracking.dismissSelectedRide()
    fun deleteRecordedRide(id: String) = rideTracking.deleteRide(id)

    // ── Offline routing ───────────────────────────────────────────────────────

    /** Owns the offline-routing concern (download lifecycle, sheets, active profile). */
    private val offlineRouting = OfflineRoutingController(
        scope = viewModelScope,
        context = context,
        segmentManager = segmentManager,
        currentLocation = { _userLocation.value },
        onDownloadError = { error -> _navigationUiState.value = NavigationUiState.Error(error) }
    )
    val offlineRoutingUiState: StateFlow<OfflineRoutingUiState> = offlineRouting.uiState
    val showOfflineSetupSheet: StateFlow<Boolean> = offlineRouting.showSetupSheet
    val showProfileSheet: StateFlow<Boolean> = offlineRouting.showProfileSheet
    val showWifiWarning: StateFlow<Boolean> = offlineRouting.showWifiWarning

    // ── Init ──────────────────────────────────────────────────────────────────

    private var viewportJob: Job? = null

    /** Active route calculation job – cancelled immediately when a new navigation starts. */
    private var navigationJob: Job? = null

    /** Timestamp (ms) of the last off-route reroute, used to throttle reroute spam. */
    private var lastRerouteAtMs = 0L

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
                .onFailure { throwable ->
                    if (_uiState.value !is MapUiState.Success) {
                        _uiState.value = MapUiState.Error(MapError.Unknown(throwable.message))
                    } else {
                        // Already showing data – don't replace the map; surface a transient error.
                        _viewportLoadError.value = throwable.message
                    }
                }
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

    /**
     * Whether the map has already been auto-centred on the user's position once
     * after launch. Ensures the one-shot startup centering only fires a single time.
     */
    private var hasCenteredOnStartup = false

    /**
     * Whether the app is currently in the foreground. Location updates are only
     * registered while foregrounded; going to the background fully stops the GPS
     * radio to save battery (re-armed in [onAppForegrounded]).
     */
    private var isForeground = true

    /**
     * Whether high-accuracy (frequent GPS) location updates are currently
     * requested. Enabled only during active turn-by-turn navigation; idle map
     * browsing uses a battery-friendly balanced-power mode.
     */
    private var highAccuracyLocation = false

    /**
     * (Re-)applies the current location-update strategy based on [isForeground]
     * and [highAccuracyLocation]. Single source of truth so accuracy changes and
     * lifecycle changes can never leave the GPS in the wrong power state.
     */
    private fun applyLocationStrategy() {
        if (isForeground) {
            locationRepository.startLocationUpdates(highAccuracy = highAccuracyLocation)
        } else {
            locationRepository.stopLocationUpdates()
        }
    }

    private fun setHighAccuracyLocation(enabled: Boolean) {
        if (highAccuracyLocation == enabled) return
        highAccuracyLocation = enabled
        applyLocationStrategy()
    }

    /**
     * High-accuracy GPS is needed whenever navigation is active OR a ride is being
     * recorded. Centralising the decision keeps the two features from clobbering
     * each other's accuracy request.
     */
    private fun refreshLocationAccuracy() {
        val needsHighAccuracy =
            _navigationUiState.value is NavigationUiState.Active || rideTracking.isRecording
        setHighAccuracyLocation(needsHighAccuracy)
    }

    /** Called when the map screen returns to the foreground — re-arm location updates. */
    fun onAppForegrounded() {
        isForeground = true
        applyLocationStrategy()
    }

    /** Called when the map screen leaves the foreground — stop the GPS to save battery. */
    fun onAppBackgrounded() {
        isForeground = false
        locationRepository.stopLocationUpdates()
    }

    private fun observeUserLocation() {
        applyLocationStrategy()
        viewModelScope.launch {
            locationRepository.getCurrentLocationFlow().collect { location ->
                // While the GPS mock simulator is running, ignore real fixes so the
                // synthetic route drive isn't overwritten.
                if (_isSimulatingRoute.value) return@collect
                _userLocation.value = location
                // Feed the ride tracker while a recording is running.
                if (location != null && rideTracking.isRecording) rideTracking.feed(location)
                // One-time startup centering: as soon as the first GPS fix arrives,
                // move the camera to the user's position. Fires only once per ViewModel.
                if (!hasCenteredOnStartup && location != null) {
                    hasCenteredOnStartup = true
                    _mapCameraTarget.value = MapCameraTarget(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        zoom = 16.0
                    )
                }
            }
        }
    }

    fun onLocationPermissionGranted() = applyLocationStrategy()

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
        // Cancel any in-progress calculation – e.g. when the user taps a different
        // parking spot while a route is still being computed.
        navigationJob?.cancel()
        _navigationUiState.value = NavigationUiState.Loading
        _navigationProgress.value = null
        hasAutoParkedForCurrentRoute = false
        navigationJob = viewModelScope.launch {
            runCatching {
                routingRepository.getBikeRoute(
                    from = location,
                    to   = GeoCoordinate(space.latitude, space.longitude)
                )
            }.onSuccess {
                _navigationUiState.value = NavigationUiState.Active(destination = space, route = it)
                rideTracking.onRouteChanged()
                // Active navigation needs precise, frequent fixes.
                setHighAccuracyLocation(true)
                // Auto-record the ride for the whole navigation (unless the user is
                // already recording manually).
                startRideTracking(autoStarted = true)
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
        navigationJob?.cancel()
        navigationJob = null
        stopRouteSimulation()
        val wasCustomPin = (_navigationUiState.value as? NavigationUiState.Active)
            ?.destination?.id == ID_CUSTOM_MAP_PIN
        _navigationUiState.value = NavigationUiState.Idle
        _navigationProgress.value = null
        if (wasCustomPin) {
            _customMapPin.value        = null
            _customMapPinAddress.value = null
        }
        // If this ride was auto-recorded by navigation, finish + save it now. A
        // manually-started recording keeps running so the user controls it.
        if (rideTracking.isAutoStartedByNavigation) {
            rideTracking.stop()
        } else {
            // Navigation ended → drop back to the battery-friendly balanced-power
            // mode (unless a manual recording still needs precise fixes).
            refreshLocationAccuracy()
        }
    }

    /**
     * Called by the `NavigationManager` when the rider has strayed off-route.
     * Recomputes a fresh BRouter route from the current GPS position to the same
     * destination and swaps it into the active navigation **without** flashing the
     * loading state, so the 3D view keeps running. Throttled so a brief detour does
     * not spawn a flurry of recalculations.
     */
    fun onUserWentOffRoute() {
        val active = _navigationUiState.value as? NavigationUiState.Active ?: return
        val location = _userLocation.value ?: return
        val now = System.currentTimeMillis()
        if (now - lastRerouteAtMs < REROUTE_COOLDOWN_MS) return
        lastRerouteAtMs = now

        navigationJob?.cancel()
        navigationJob = viewModelScope.launch {
            runCatching {
                routingRepository.getBikeRoute(
                    from = location,
                    to   = GeoCoordinate(active.destination.latitude, active.destination.longitude)
                )
            }.onSuccess { newRoute ->
                // Only apply if we're still navigating to the same destination.
                val current = _navigationUiState.value as? NavigationUiState.Active
                if (current?.destination?.id == active.destination.id) {
                    _navigationUiState.value = NavigationUiState.Active(active.destination, newRoute)
                    _navigationProgress.value = null
                    rideTracking.onRouteChanged()
                }
            }
            // On failure we silently keep the existing route; the next off-route
            // window (after the cooldown) will try again.
        }
    }

    fun clearNavigationError() { if (_navigationUiState.value is NavigationUiState.Error) _navigationUiState.value = NavigationUiState.Idle }

    // ── Offline routing ───────────────────────────────────────────────────────

    fun requestOfflineRoutingSetup() = offlineRouting.requestSetup()
    fun dismissOfflineSetupSheet()   = offlineRouting.dismissSetupSheet()

    fun openProfileSheet()    = offlineRouting.openProfileSheet()
    fun dismissProfileSheet() = offlineRouting.dismissProfileSheet()

    fun dismissWifiWarning()          = offlineRouting.dismissWifiWarning()
    fun confirmDownloadOnMobileData() = offlineRouting.confirmDownloadOnMobileData()
    fun confirmOfflineRoutingSetup()  = offlineRouting.confirmSetup()

    /**
     * Switches the active routing profile. Persisting + state live in the offline
     * controller; if navigation is active the route is immediately recalculated
     * with the new profile.
     */
    fun selectRoutingProfile(profile: de.velospot.data.brouter.BRouterProfile) {
        offlineRouting.selectProfile(profile)
        val currentDestination = (_navigationUiState.value as? NavigationUiState.Active)?.destination
        if (currentDestination != null) {
            startInAppNavigation(currentDestination)
        }
    }

    fun disableOfflineRouting() = offlineRouting.disable()

    override fun onCleared() {
        super.onCleared()
        routeSimulator.stop()
        locationRepository.stopLocationUpdates()
    }
}
