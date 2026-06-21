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
import de.velospot.core.navigation.VoiceGuidancePreferences
import de.velospot.core.routing.OfflineRoutingPreferences
import de.velospot.data.brouter.ElevationPreference
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.geocoding.NominatimGeocoder
import de.velospot.core.tracking.RideRecordingManager
import de.velospot.domain.model.AddressSearchResult
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.BoundingBox
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.LiveRideStats
import de.velospot.domain.model.MapError
import de.velospot.domain.model.ParkedBike
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.SavedPlace
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.ParkedBikeRepository
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.domain.repository.RoutingRepository
import de.velospot.domain.repository.SavedPlacesRepository
import de.velospot.feature.map.presentation.navigation.NavigationController
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
    private val recordingManager: RideRecordingManager,
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
        /** ID used for the synthetic BikeParkingSpace anchoring a generated round-trip loop. */
        const val ID_ROUND_TRIP = "round_trip"

        /**
         * Synthetic destination IDs that must NOT trigger auto-parking on arrival —
         * only navigation to a genuine bike parking spot from the map data should.
         */
        private val SYNTHETIC_DESTINATION_IDS = setOf(
            ID_CUSTOM_MAP_PIN, ID_ADDRESS_SEARCH_PIN, ID_SAVED_PLACE, ID_PARKED_BIKE, ID_ROUND_TRIP
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

    /**
     * Whether the camera is currently **locked to** (following) the live user
     * position. Turned on automatically when a follow session — active navigation
     * **or** a running ride recording — starts, and turned off the moment the user
     * pans the map by hand ([onMapPannedByUser]) so they can freely explore. The
     * re-centre button ([recenterOnUserLocation]) locks it back on, and it is reset
     * once neither navigation nor a recording is active ([updateFollowSession]).
     */
    private val _isFollowingLocation = MutableStateFlow(false)
    val isFollowingLocation: StateFlow<Boolean> = _isFollowingLocation.asStateFlow()

    /** Whether a follow-capable session (navigation or recording) is currently running. */
    val isFollowSessionActive: Boolean
        get() = navigationController.isActive || rideTracking.isRecording

    /**
     * Re-evaluates the follow session: clears the follow lock once neither
     * navigation nor a recording is running, so the next session starts fresh and
     * the re-centre button disappears on the idle map.
     */
    private fun updateFollowSession() {
        if (!isFollowSessionActive) _isFollowingLocation.value = false
    }

    /**
     * Called when the user pans/zooms the map by hand (a touch gesture). During a
     * follow session this unlocks the camera so it stops chasing the position and
     * the re-centre button appears. Ignored when no session is running (the idle
     * map never follows in the first place).
     */
    fun onMapPannedByUser() {
        if (isFollowSessionActive && _isFollowingLocation.value) {
            _isFollowingLocation.value = false
        }
    }

    /**
     * Re-centres on the live position and, during a follow session, re-locks the
     * camera so it keeps following until the user pans again or the session ends.
     */
    fun recenterOnUserLocation() {
        if (isFollowSessionActive) _isFollowingLocation.value = true
        centerMapOnUserLocation()
    }

    /** Owns the in-app navigation concern (route calc, progress, reroute, auto-park, GPS simulator). */
    private val navigationController = NavigationController(
        scope = viewModelScope,
        routingRepository = routingRepository,
        currentLocation = { _userLocation.value },
        customPinDestinationId = ID_CUSTOM_MAP_PIN,
        syntheticDestinationIds = SYNTHETIC_DESTINATION_IDS,
        // Method references (not inline lambdas) so the mutual reference between
        // this controller and `rideTracking` doesn't trip Kotlin's property-init
        // type-inference into a recursion (the referenced members carry explicit
        // signatures, breaking the cycle).
        onSimulatedFix = ::onSimulatedNavigationFix,
        onArrivedAtParkingSpot = ::onArrivedAtParkingSpot,
        onArrivedAtDestination = ::onArrivedAtDestination,
        onNavigationStarted = ::onNavigationStarted,
        onNavigationStopped = ::onNavigationStopped,
        onRerouted = ::onNavigationRerouted,
        onCustomPinNavigationEnded = ::dismissCustomMapPin
    )
    val navigationUiState: StateFlow<NavigationUiState> = navigationController.uiState

    /**
     * Live route progress (remaining distance + ETA) emitted by the
     * `NavigationManager` on each GPS fix; `null` when not navigating. Drives the
     * dynamic distance/ETA readout in the navigation overlay.
     */
    val navigationProgress: StateFlow<de.velospot.core.navigation.NavigationProgress?> = navigationController.progress

    /** Pushed from the UI layer's `NavigationManager.onProgress` callback. */
    fun updateNavigationProgress(progress: de.velospot.core.navigation.NavigationProgress) =
        navigationController.updateProgress(progress)

    // ── Navigation cross-feature hooks (wired into NavigationController) ────────

    /** Feeds a simulated GPS fix into the location pipeline + active recording. */
    private fun onSimulatedNavigationFix(fix: GeoCoordinate) {
        _userLocation.value = fix
        if (rideTracking.isRecording) rideTracking.feed(fix)
    }

    /** Parks the bike at a reached parking spot and shows an arrival confirmation. */
    private fun onArrivedAtParkingSpot(latitude: Double, longitude: Double) {
        parkBikeAt(latitude, longitude)
        // Override the generic "saved" toast with a clearer arrival confirmation.
        _userMessageRes.value = de.velospot.R.string.parked_bike_arrived
    }

    /**
     * Shows a generic "you've arrived" confirmation when navigation to a
     * non-parking destination (address search, saved place, custom pin, parked
     * bike) finishes on arrival.
     */
    private fun onArrivedAtDestination() {
        _userMessageRes.value = de.velospot.R.string.navigation_arrived
    }

    /** On navigation start: reset elevation cursor, raise GPS accuracy, auto-record. */
    private fun onNavigationStarted() {
        rideTracking.onRouteChanged()
        // Active navigation needs precise, frequent fixes.
        setHighAccuracyLocation(true)
        // Auto-record the ride for the whole navigation (unless already recording).
        startRideTracking(autoStarted = true)
    }

    /** On navigation end: finish an auto-recorded ride, else relax GPS accuracy. */
    private fun onNavigationStopped() {
        // If this ride was auto-recorded by navigation, finish + save it now. A
        // manually-started recording keeps running so the user controls it.
        if (rideTracking.isAutoStartedByNavigation) rideTracking.stop()
        else refreshLocationAccuracy()
        // Drop the follow lock once nothing keeps it alive (no nav, no recording).
        updateFollowSession()
    }

    /** On reroute: reset the elevation-match cursor for the fresh route. */
    private fun onNavigationRerouted() {
        rideTracking.onRouteChanged()
    }

    // ── GPS route simulator (debug couch-testing) ─────────────────────────────

    /** True while the GPS mock simulator is driving along the active route. */
    val isSimulatingRoute: StateFlow<Boolean> = navigationController.isSimulating

    /**
     * Starts/stops the GPS mock simulator along the active navigation route, so the
     * whole live-navigation pipeline (matching, camera, progress, off-route) can be
     * tested on the couch. Real GPS updates are ignored while simulating.
     */
    fun toggleRouteSimulation() = navigationController.toggleSimulation()


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

    /**
     * Whether spoken turn-by-turn voice guidance (Text-to-Speech) is enabled.
     * Persisted across sessions; defaults to disabled (opt-in).
     */
    private val _voiceGuidanceEnabled =
        MutableStateFlow(VoiceGuidancePreferences.isVoiceGuidanceEnabled(context))
    val voiceGuidanceEnabled: StateFlow<Boolean> = _voiceGuidanceEnabled.asStateFlow()

    /** Toggles spoken voice guidance on/off and persists the choice. */
    fun setVoiceGuidanceEnabled(enabled: Boolean) {
        VoiceGuidancePreferences.setVoiceGuidanceEnabled(context, enabled)
        _voiceGuidanceEnabled.value = enabled
    }

    fun onSearchQueryChanged(query: String) = addressSearch.onQueryChanged(query)

    fun onSearchCleared() {
        addressSearch.clear()
        _selectedSearchPin.value = null
    }

    /**
     * Called when the user taps on an empty map location.
     * Places a custom pin there and dismisses any open space/search-pin sheets.
     *
     * Ignored while a follow session (navigation or ride recording) is active, so
     * the rider can't accidentally drop pins — and trigger reverse-geocoding /
     * camera jumps — mid-trip.
     */
    fun onMapTapped(lat: Double, lon: Double) {
        if (isFollowSessionActive) return
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
        manager = recordingManager,
        currentLocation = { _userLocation.value },
        onUserMessage = { res -> _userMessageRes.value = res },
        clearOtherSelections = { clearPlaceSelections() },
        moveCamera = { target -> _mapCameraTarget.value = target }
    )
    val rideTrackingState: StateFlow<RideTrackingUiState> = rideTracking.trackingState
    val recordedRides: StateFlow<List<RecordedRide>> = rideTracking.recordedRides
    val selectedRide: StateFlow<RecordedRide?> = rideTracking.selectedRide
    val rideTrackPoints: StateFlow<List<RoutePoint>> = rideTracking.trackPoints

    val isRecordingRide: Boolean get() = rideTracking.isRecording

    fun startRideTracking(autoStarted: Boolean = false) {
        rideTracking.start(autoStarted)
        // Lock the camera onto the rider for the whole recording (until they pan).
        if (rideTracking.isRecording) _isFollowingLocation.value = true
    }
    fun stopRideTracking() {
        rideTracking.stop()
        updateFollowSession()
    }
    fun discardRideTracking() {
        rideTracking.discard()
        updateFollowSession()
    }
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
        onDownloadError = { error -> navigationController.showError(error) }
    )
    val offlineRoutingUiState: StateFlow<OfflineRoutingUiState> = offlineRouting.uiState
    val showOfflineSetupSheet: StateFlow<Boolean> = offlineRouting.showSetupSheet
    val showProfileSheet: StateFlow<Boolean> = offlineRouting.showProfileSheet
    val showWifiWarning: StateFlow<Boolean> = offlineRouting.showWifiWarning

    // ── Init ──────────────────────────────────────────────────────────────────

    private var viewportJob: Job? = null

    init {
        // Wire the process-level recording manager to this (live) host: supply the
        // navigation route for accurate elevation and let it re-evaluate the GPS
        // power mode (against navigation) when a recording starts/stops.
        recordingManager.routeElevationProvider = { navigationController.activeRoute }
        recordingManager.onRecordingStateChanged = { refreshLocationAccuracy() }
        loadSpacesForViewport(BoundingBox.DEFAULT)
        observeFavorites()
        observeUserLocation()
        observeRouteSimulation()
    }

    /**
     * Keeps the recording manager's real-GPS suppression in sync with the debug
     * route simulator so synthetic fixes (not real ones) drive an auto-recorded
     * ride while simulating.
     */
    private fun observeRouteSimulation() {
        viewModelScope.launch {
            isSimulatingRoute.collect { recordingManager.suppressRealFixes = it }
        }
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
        } else if (!recordingManager.isRecording) {
            locationRepository.stopLocationUpdates()
        }
        // else: a recording is running in the background — the RideRecordingManager
        // (kept alive by the foreground service) owns the GPS radio, so we must not
        // stop it here or the background track would freeze.
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
            navigationController.isActive || rideTracking.isRecording
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
        // Keep the GPS running when a recording is active: the foreground service +
        // manager continue the background track. Otherwise stop it to save battery.
        if (!recordingManager.isRecording) locationRepository.stopLocationUpdates()
    }

    private fun observeUserLocation() {
        applyLocationStrategy()
        viewModelScope.launch {
            locationRepository.getCurrentLocationFlow().collect { location ->
                // While the GPS mock simulator is running, ignore real fixes so the
                // synthetic route drive isn't overwritten.
                if (navigationController.isSimulating.value) return@collect
                _userLocation.value = location
                // Note: the running recording is fed by the RideRecordingManager,
                // which observes the location flow itself so it keeps accumulating
                // fixes even when this ViewModel is gone (app backgrounded/closed).
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

    fun startInAppNavigation(space: BikeParkingSpace) = navigationController.start(space)

    /**
     * Generates and starts a round-trip loop of roughly [distanceMeters] from the
     * current position back to it. Offline-only; surfaces a navigation error when
     * offline routing is off or the start segment tile is unavailable.
     */
    fun startRoundTrip(distanceMeters: Double) {
        val location = _userLocation.value ?: return
        val destination = syntheticSpace(
            id          = ID_ROUND_TRIP,
            latitude    = location.latitude,
            longitude   = location.longitude,
            name        = context.getString(de.velospot.R.string.round_trip_title),
            address     = null,
            sourceLayer = "round_trip"
        )
        navigationController.startRoundTrip(destination, distanceMeters)
    }

    fun stopInAppNavigation() = navigationController.stop()

    /** Cancels an in-progress route calculation (loading card "Cancel" button). */
    fun cancelRouteCalculation() = navigationController.cancelRouteCalculation()

    /** Called by the `NavigationManager` when the rider has strayed off-route. */
    fun onUserWentOffRoute() = navigationController.onUserWentOffRoute()

    fun clearNavigationError() = navigationController.clearError()

    // ── Offline routing ───────────────────────────────────────────────────────

    fun requestOfflineRoutingSetup() = offlineRouting.requestSetup()
    fun dismissOfflineSetupSheet()   = offlineRouting.dismissSetupSheet()

    fun openProfileSheet()    = offlineRouting.openProfileSheet()
    fun dismissProfileSheet() = offlineRouting.dismissProfileSheet()

    fun dismissWifiWarning()          = offlineRouting.dismissWifiWarning()
    fun confirmDownloadOnMobileData() = offlineRouting.confirmDownloadOnMobileData()
    fun confirmOfflineRoutingSetup()  = offlineRouting.confirmSetup(full = false)
    fun confirmOfflineRoutingFullSetup() = offlineRouting.confirmSetup(full = true)

    /**
     * Switches the active routing profile. Persisting + state live in the offline
     * controller; if navigation is active the route is immediately recalculated
     * with the new profile.
     */
    fun selectRoutingProfile(profile: de.velospot.data.brouter.BRouterProfile) {
        offlineRouting.selectProfile(profile)
        val currentDestination = navigationController.activeDestination
        if (currentDestination != null) {
            startInAppNavigation(currentDestination)
        }
    }

    /**
     * How strongly offline routes should avoid climbing (the "route hilliness"
     * slider). Persisted via [OfflineRoutingPreferences]; changing it re-runs an
     * active navigation so the new preference takes effect immediately.
     */
    private val _elevationPreference =
        MutableStateFlow(OfflineRoutingPreferences.getElevationPreference(context))
    val elevationPreference: StateFlow<ElevationPreference> = _elevationPreference.asStateFlow()

    fun selectElevationPreference(preference: ElevationPreference) {
        OfflineRoutingPreferences.setElevationPreference(context, preference)
        _elevationPreference.value = preference
        val currentDestination = navigationController.activeDestination
        if (currentDestination != null) {
            startInAppNavigation(currentDestination)
        }
    }

    fun disableOfflineRouting() = offlineRouting.disable()

    override fun onCleared() {
        super.onCleared()
        navigationController.dispose()
        // Detach this (dying) host so the manager stops calling back into it and
        // takes over GPS management itself for any still-running background record.
        recordingManager.onRecordingStateChanged = null
        recordingManager.routeElevationProvider = null
        // Only release the GPS when nothing is recording — a background recording
        // must keep its fixes flowing via the foreground service.
        if (!recordingManager.isRecording) locationRepository.stopLocationUpdates()
    }
}
