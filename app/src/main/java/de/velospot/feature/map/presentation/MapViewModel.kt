package de.velospot.feature.map.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.core.location.LocationController
import de.velospot.core.map.LayerVisibility
import de.velospot.core.map.MapLayerCategory
import de.velospot.core.map.RideViewOptions
import de.velospot.core.routing.OfflineRoutingPreferences
import de.velospot.core.share.GpxExporter
import de.velospot.data.brouter.ElevationPreference
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.geocoding.NominatimGeocoder
import de.velospot.data.gpx.GpxFileStore
import de.velospot.core.tracking.RideRecordingManager
import de.velospot.core.tracking.RideTrackingUiState
import de.velospot.domain.model.AddressSearchResult
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.BoundingBox
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.domain.model.ParkedBike
import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.model.RouteAttempt
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.SavedPlace
import de.velospot.domain.repository.BikeParkingRepository
import de.velospot.domain.repository.FavoritesRepository
import de.velospot.domain.repository.MapSettingsRepository
import de.velospot.domain.repository.ParkedBikeRepository
import de.velospot.domain.repository.PlannedRoutesRepository
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.domain.repository.RoutingRepository
import de.velospot.domain.repository.SavedPlacesRepository
import de.velospot.feature.map.presentation.navigation.NavigationController
import de.velospot.feature.map.presentation.offline.OfflineRoutingController
import de.velospot.feature.map.presentation.places.ParkedBikeController
import de.velospot.feature.map.presentation.places.SavedPlacesController
import de.velospot.feature.map.presentation.ride.RideTrackingController
import de.velospot.feature.map.presentation.routes.RideDirection
import de.velospot.feature.map.presentation.routes.RoutePlanningController
import de.velospot.feature.map.presentation.search.AddressSearchController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
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
// RideTrackingUiState now lives in `core.tracking` (next to its producer,
// RideRecordingManager) so the recording stack never depends on the UI layer.

/**
 * Drives the "name this ride" dialog shown when a **manual** recording is being
 * finished. [suggestion] is the reverse-geocoded current place pre-filled into the
 * text field — `null` while it's still being resolved (or unavailable).
 */
data class RideNamePromptUiState(val suggestion: String?)

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MapViewModel @Inject constructor(
    private val bikeParkingRepository: BikeParkingRepository,
    private val favoritesRepository: FavoritesRepository,
    private val locationController: LocationController,
    private val routingRepository: RoutingRepository,
    private val segmentManager: BRouterSegmentManager,
    private val nominatimGeocoder: NominatimGeocoder,
    private val recordingManager: RideRecordingManager,
    private val gpxFileStore: GpxFileStore,
    savedPlacesRepository: SavedPlacesRepository,
    parkedBikeRepository: ParkedBikeRepository,
    private val recordedRidesRepository: RecordedRidesRepository,
    plannedRoutesRepository: PlannedRoutesRepository,
    private val mapSettings: MapSettingsRepository,
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

        /** ID used for the synthetic BikeParkingSpace at the end of a planned multi-waypoint route. */
        const val ID_PLANNED_ROUTE = "planned_route"

        /**
         * Synthetic destination IDs that must NOT trigger auto-parking on arrival —
         * only navigation to a genuine bike parking spot from the map data should.
         */
        private val SYNTHETIC_DESTINATION_IDS = setOf(
            ID_CUSTOM_MAP_PIN, ID_ADDRESS_SEARCH_PIN, ID_SAVED_PLACE, ID_PARKED_BIKE,
            ID_ROUND_TRIP, ID_PLANNED_ROUTE
        )
    }

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _selectedSpace = MutableStateFlow<BikeParkingSpace?>(null)
    val selectedSpace: StateFlow<BikeParkingSpace?> = _selectedSpace.asStateFlow()

    /**
     * The user's favourite parking-space ids, straight from the database as a
     * reactive stream. `stateIn` shares the single upstream collection and caches
     * the latest value for the UI, replacing the previous hand-wired
     * `MutableStateFlow` that had to be kept in sync imperatively.
     */
    val favorites: StateFlow<List<String>> =
        favoritesRepository.getFavoritesFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The full [BikeParkingSpace]s for the current favourites, derived reactively
     * from [favorites]: whenever the favourite ids change, the spaces are re-loaded
     * via `mapLatest` (cancelling any in-flight load). No manual re-query, no risk
     * of the two lists drifting out of sync.
     */
    val favoriteSpaces: StateFlow<List<BikeParkingSpace>> =
        favoritesRepository.getFavoritesFlow()
            .mapLatest { ids ->
                runCatching { bikeParkingRepository.getSpacesByIds(ids) }.getOrDefault(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        onSimulationStarted = ::onRouteSimulationStarted,
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

    /**
     * Flags the auto-recorded ride as a **mock** the moment the debug route
     * simulator actually starts driving the route — so only simulator rides are
     * saved as mocks, not real navigations (whose puck is braked with a synthetic
     * speed-0 fix through the same path when navigation ends).
     */
    private fun onRouteSimulationStarted() {
        if (rideTracking.isRecording) rideTracking.markMock()
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
        locationController.setNavigating(true)
        // Auto-record the ride for the whole navigation (unless already recording).
        startRideTracking(autoStarted = true)
        // Name the auto-recorded ride after the navigation destination so it lands in
        // "My rides" with a meaningful label instead of just a date. Only do so when
        // this recording was actually auto-started by navigation (not a manual one
        // the rider already had running).
        if (rideTracking.isAutoStartedByNavigation) {
            resolveAndSetAutoRideName()
        }
    }

    /**
     * Derives a human-readable name for the auto-recorded navigation ride and pushes
     * it to the recording manager:
     *  - **Round trip** → "Round trip - {current place}" (reverse-geocoded city/town),
     *    falling back to the plain round-trip label when the place can't be resolved.
     *  - **Any other destination** → the **destination's place** (reverse-geocoded
     *    city/town, e.g. "Trier"), falling back to the destination's own label only
     *    when the place can't be resolved. The place is preferred so the ride reads
     *    "Trier" rather than the destination's street ("Hauptstraße").
     *
     * A best-effort synchronous name is set first so a very short ride is never left
     * blank, then the (network) reverse-geocode refines it once it returns.
     */
    private fun resolveAndSetAutoRideName() {
        val destination = navigationController.activeDestination ?: return
        val isRoundTrip = destination.id == ID_ROUND_TRIP
        val existingName = destination.name?.trim()?.takeIf { it.isNotBlank() }
        // Immediate fallback so the ride always has *some* label until the place
        // reverse-geocode returns and refines it to the city/town.
        if (!isRoundTrip && existingName != null) rideTracking.setPendingName(existingName)
        val lat = destination.latitude
        val lon = destination.longitude
        viewModelScope.launch {
            val place = nominatimGeocoder.reverseGeocodePlace(lat, lon)
            val name = when {
                isRoundTrip && place != null ->
                    context.getString(de.velospot.R.string.ride_round_trip_name, place)
                isRoundTrip ->
                    existingName ?: context.getString(de.velospot.R.string.round_trip_title)
                else -> place ?: existingName
            }
            // Only apply if the navigation-started recording is still the active one.
            if (name != null && rideTracking.isAutoStartedByNavigation) {
                rideTracking.setPendingName(name)
            }
        }
    }

    /** On navigation end: finish an auto-recorded ride, else relax GPS accuracy. */
    private fun onNavigationStopped() {
        // Navigation no longer needs the GPS at high accuracy; the controller keeps
        // it on (balanced) while the map is visible, or drops it otherwise.
        locationController.setNavigating(false)
        // If this ride was auto-recorded by navigation, finish + save it now. A
        // manually-started recording keeps running so the user controls it.
        if (rideTracking.isAutoStartedByNavigation) rideTracking.stop()
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

    /** Owns the route-planning + leaderboard concern (waypoints, preview, saved routes, attempts). */
    private val routePlanningController = RoutePlanningController(
        scope = viewModelScope,
        repository = plannedRoutesRepository,
        routingRepository = routingRepository
    )
    val isPlanningRoute: StateFlow<Boolean> = routePlanningController.isPlanning
    val planningWaypoints: StateFlow<List<de.velospot.domain.model.RouteWaypoint>> = routePlanningController.waypoints
    val planningPreviewRoute: StateFlow<BikeRoute?> = routePlanningController.previewRoute
    val isComputingRoutePreview: StateFlow<Boolean> = routePlanningController.isComputingPreview
    val plannedRoutes: StateFlow<List<PlannedRoute>> = routePlanningController.plannedRoutes
    val leaderboardRoute: StateFlow<PlannedRoute?> = routePlanningController.leaderboardRoute
    val routeAttempts: StateFlow<List<RouteAttempt>> = routePlanningController.attempts

    /**
     * One-shot, string-resource user message (e.g. "bike location saved").
     * The UI shows it as a Toast and then calls [consumeUserMessage] to clear it.
     */
    private val _userMessageRes = MutableStateFlow<Int?>(null)
    val userMessageRes: StateFlow<Int?> = _userMessageRes.asStateFlow()

    fun consumeUserMessage() { _userMessageRes.value = null }

    /** Which pin categories ("layers") are currently shown on the map. */
    val layerVisibility: StateFlow<LayerVisibility> =
        mapSettings.layerVisibility
            .stateIn(viewModelScope, SharingStarted.Eagerly, LayerVisibility())

    /** Toggles a pin layer's visibility and persists the choice. */
    fun setLayerVisible(category: MapLayerCategory, visible: Boolean) {
        viewModelScope.launch { mapSettings.setLayerVisible(category, visible) }
    }

    /**
     * Whether navigation uses the tilted 3D camera (`true`) or the flat 2D
     * heading-up view (`false`). Persisted across sessions and applied live to
     * the `NavigationManager`, so toggling it mid-navigation re-tilts the camera.
     */
    val is3DNavigation: StateFlow<Boolean> =
        mapSettings.is3DNavigation.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Switches the navigation perspective between 3D and 2D and persists it. */
    fun setNavigation3DEnabled(enabled: Boolean) {
        viewModelScope.launch { mapSettings.set3DNavigation(enabled) }
    }

    /**
     * Whether spoken turn-by-turn voice guidance (Text-to-Speech) is enabled.
     * Persisted across sessions; defaults to disabled (opt-in).
     */
    val voiceGuidanceEnabled: StateFlow<Boolean> =
        mapSettings.voiceGuidanceEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Toggles spoken voice guidance on/off and persists the choice. */
    fun setVoiceGuidanceEnabled(enabled: Boolean) {
        viewModelScope.launch { mapSettings.setVoiceGuidance(enabled) }
    }

    /**
     * Whether the display is kept awake during a follow session (active navigation
     * or a live ride recording). Persisted across sessions; defaults to enabled.
     */
    val keepScreenOnEnabled: StateFlow<Boolean> =
        mapSettings.keepScreenOnEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Toggles the keep-display-awake behaviour on/off and persists the choice. */
    fun setKeepScreenOnEnabled(enabled: Boolean) {
        viewModelScope.launch { mapSettings.setKeepScreenOn(enabled) }
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
        // In route-planning mode an empty-map tap drops a waypoint instead of a pin.
        if (routePlanningController.isPlanning.value) {
            routePlanningController.addWaypoint(lat, lon)
            viewModelScope.launch {
                val address = nominatimGeocoder.reverseGeocode(lat, lon)
                // Re-label the just-added waypoint once its address resolves.
                routePlanningController.labelLastWaypoint(address?.substringBefore(",")?.trim())
            }
            return
        }
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
        moveCamera = { target -> _mapCameraTarget.value = target },
        // Full ride tracks are only loaded while the heatmap or ridden-tracks
        // overlay is actually visible; otherwise no track is ever deserialised.
        overlayTracksNeeded = layerVisibility
            .map { it.showHeatmap || it.showTracks }
            .distinctUntilChanged(),
        // Turn a finished ride of a planned route into a leaderboard attempt.
        onRideSaved = { ride -> routePlanningController.onRideFinished(ride) }
    )
    val rideTrackingState: StateFlow<RideTrackingUiState> = rideTracking.trackingState

    /** Track-free ride summaries driving the "My rides" timeline + statistics. */
    val recordedRideSummaries: StateFlow<List<RecordedRideSummary>> = rideTracking.recordedRideSummaries

    /**
     * Every recorded ride **with** its track, but only while a map overlay
     * (heatmap / ridden tracks) is on — otherwise empty and nothing is parsed.
     */
    val recordedRideTracks: StateFlow<List<RecordedRide>> = rideTracking.recordedRideTracks
    val selectedRide: StateFlow<RecordedRide?> = rideTracking.selectedRide
    val rideTrackPoints: StateFlow<List<RoutePoint>> = rideTracking.trackPoints

    /**
     * The rider's persisted "inspect a past ride" overlay choices (max-speed
     * bubble + colour-by-speed track). Global and remembered across sessions, so
     * the last-used settings apply to every ride opened afterwards.
     */
    val rideViewOptions: StateFlow<RideViewOptions> =
        mapSettings.rideViewOptions.stateIn(viewModelScope, SharingStarted.Eagerly, RideViewOptions())

    /** Toggles the on-map "max speed" bubble for inspected rides and persists it. */
    fun setMaxSpeedBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch { mapSettings.setShowMaxSpeedBubble(enabled) }
    }

    /** Toggles colouring an inspected ride's track by speed and persists it. */
    fun setColorTrackBySpeedEnabled(enabled: Boolean) {
        viewModelScope.launch { mapSettings.setColorTrackBySpeed(enabled) }
    }

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

    // ── Name-on-stop prompt (manual recordings) ───────────────────────────────

    /**
     * When non-null, the UI shows a dialog asking the rider to name the recording
     * they are about to finish. [RideNamePromptUiState.suggestion] is the
     * reverse-geocoded current place (null while it's still being resolved), shown
     * as the pre-filled default.
     */
    private val _rideNamePrompt = MutableStateFlow<RideNamePromptUiState?>(null)
    val rideNamePrompt: StateFlow<RideNamePromptUiState?> = _rideNamePrompt.asStateFlow()

    /**
     * Called by the record FAB / live-stats overlay's **Stop** control for a
     * **manual** recording. Instead of saving straight away it opens the naming
     * prompt (pre-filled with the reverse-geocoded current place) and leaves the
     * recording running, so cancelling keeps the ride going. The actual save
     * happens in [confirmRideNameAndStop].
     */
    fun requestStopRideTracking() {
        if (!rideTracking.isRecording) return
        _rideNamePrompt.value = RideNamePromptUiState(suggestion = null)
        val location = _userLocation.value
        viewModelScope.launch {
            val place = location?.let {
                nominatimGeocoder.reverseGeocodePlace(it.latitude, it.longitude)
            }
            // Only fill the suggestion if the prompt is still open (not cancelled).
            _rideNamePrompt.update { if (it != null) it.copy(suggestion = place) else it }
        }
    }

    /** Confirms the naming prompt: names the active recording and saves it. */
    fun confirmRideNameAndStop(name: String?) {
        _rideNamePrompt.value = null
        rideTracking.setPendingName(name?.trim()?.takeIf { it.isNotBlank() })
        stopRideTracking()
    }

    /** Dismisses the naming prompt **without** stopping — the ride keeps recording. */
    fun cancelRideNamePrompt() {
        _rideNamePrompt.value = null
    }

    fun selectRecordedRide(ride: RecordedRideSummary) = rideTracking.selectRide(ride)
    fun dismissSelectedRide() = rideTracking.dismissSelectedRide()
    fun deleteRecordedRide(id: String) = rideTracking.deleteRide(id)
    fun renameRecordedRide(id: String, name: String?) = rideTracking.renameRide(id, name)
    fun setRecordedRideArchived(id: String, archived: Boolean) =
        rideTracking.setRideArchived(id, archived)

    /**
     * Exports [rides] as GPX and opens the system **share** sheet. When
     * [combineIntoSingleFile] is `true` (or only one ride is given) all tracks land
     * in one file; otherwise each ride is written to its own file named after it.
     *
     * The rides arrive as track-free summaries; their full GPS tracks are loaded
     * on demand (off the main thread) before the GPX is built.
     */
    fun exportRidesAsGpx(rides: List<RecordedRideSummary>, combineIntoSingleFile: Boolean) {
        if (rides.isEmpty()) return
        viewModelScope.launch {
            val documents = buildGpxDocuments(rides, combineIntoSingleFile)
            if (documents.isEmpty()) return@launch // empty selection or validation failed (toast shown)
            GpxExporter.share(
                context = context,
                documents = documents,
                chooserTitle = context.getString(de.velospot.R.string.ride_export_chooser_title)
            )
        }
    }

    /**
     * GPX documents built and validated for the selected rides, awaiting a Storage
     * Access Framework destination pick (the UI launches the picker, then calls
     * [saveGpxToUri] / [saveGpxToTree] with the chosen target). `null` when nothing
     * is staged. Replaces the old synchronous `buildGpxDocuments` call the UI used
     * to make before launching the picker — tracks now load asynchronously.
     */
    private val _pendingGpxExport = MutableStateFlow<List<de.velospot.core.share.GpxDocument>?>(null)
    val pendingGpxExport: StateFlow<List<de.velospot.core.share.GpxDocument>?> = _pendingGpxExport.asStateFlow()

    /**
     * Loads the selected rides' tracks, builds + validates their GPX and stages the
     * result in [pendingGpxExport] so the UI can launch the SAF picker. Shows a
     * toast (and stages nothing) when validation fails or the selection is empty.
     */
    fun prepareGpxSave(rides: List<RecordedRideSummary>, combineIntoSingleFile: Boolean) {
        if (rides.isEmpty()) return
        viewModelScope.launch {
            val documents = buildGpxDocuments(rides, combineIntoSingleFile)
            _pendingGpxExport.value = documents.ifEmpty { null }
        }
    }

    /** Clears the staged GPX export once the SAF picker has been handled (or cancelled). */
    fun consumePendingGpxExport() { _pendingGpxExport.value = null }

    /**
     * Loads the full tracks for [rides], builds their GPX documents and validates
     * them. Each document is checked (well-formed XML with at least one track
     * point) before it is returned, so a broken file is never shared or saved; on
     * failure a toast is shown and an empty list returned.
     */
    private suspend fun buildGpxDocuments(
        rides: List<RecordedRideSummary>,
        combineIntoSingleFile: Boolean
    ): List<de.velospot.core.share.GpxDocument> {
        if (rides.isEmpty()) return emptyList()
        val fullRides = recordedRidesRepository.getRides(rides.map { it.id })
        if (fullRides.isEmpty()) {
            _userMessageRes.value = de.velospot.R.string.ride_export_invalid
            return emptyList()
        }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val documents = GpxExporter.buildDocuments(
            rides = fullRides,
            combineIntoSingleFile = combineIntoSingleFile,
            combinedFileName = context.getString(de.velospot.R.string.ride_export_combined_filename, stamp)
        )
        val allValid = documents.isNotEmpty() &&
            documents.all { de.velospot.core.gpx.GpxValidator.validate(it.content).isValid }
        if (!allValid) {
            _userMessageRes.value = de.velospot.R.string.ride_export_invalid
            return emptyList()
        }
        return documents
    }

    /** Writes a single GPX document's [content] to the SAF-picked [uri]. */
    fun saveGpxToUri(uri: android.net.Uri, content: String) {
        viewModelScope.launch {
            val ok = gpxFileStore.writeDocument(uri, content)
            _userMessageRes.value =
                if (ok) de.velospot.R.string.ride_export_saved
                else de.velospot.R.string.ride_export_save_failed
        }
    }

    /** Writes each GPX [documents] into the SAF-picked folder [treeUri]. */
    fun saveGpxToTree(treeUri: android.net.Uri, documents: List<de.velospot.core.share.GpxDocument>) {
        if (documents.isEmpty()) return
        viewModelScope.launch {
            val saved = gpxFileStore.writeDocumentsToTree(treeUri, documents)
            _userMessageRes.value =
                if (saved > 0) de.velospot.R.string.ride_export_saved
                else de.velospot.R.string.ride_export_save_failed
        }
    }

    /**
     * Imports rides from the picked GPX file [uris]. Each `<trk>` becomes a ride
     * (its `<name>` kept); a short toast reports the outcome. Parsing runs off the
     * main thread in [GpxFileStore]; persistence goes through the ride controller.
     */
    fun importGpxFiles(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val rides = gpxFileStore.importRides(uris)
            rides.forEach { rideTracking.importRide(it) }
            _userMessageRes.value =
                if (rides.isNotEmpty()) de.velospot.R.string.ride_import_done
                else de.velospot.R.string.ride_import_failed
        }
    }

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
        // Wire the process-level recording manager to this (live) host so it can
        // resolve accurate terrain elevation from the active navigation route.
        recordingManager.routeElevationProvider = { navigationController.activeRoute }
        // The map screen is foreground when this ViewModel is created: declare its
        // location need to the single GPS owner.
        locationController.setMapVisible(true)
        loadSpacesForViewport(BoundingBox.DEFAULT)
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
        // A single atomic DB transaction (see FavoriteSpaceDao.toggleFavorite);
        // no read-then-write race when the button is tapped in quick succession.
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(parkingSpaceId)
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────

    /**
     * Whether the map has already been auto-centred on the user's position once
     * after launch. Ensures the one-shot startup centering only fires a single time.
     */
    private var hasCenteredOnStartup = false

    /** Called when the map screen returns to the foreground — re-arm location updates. */
    fun onAppForegrounded() {
        locationController.setMapVisible(true)
    }

    /** Called when the map screen leaves the foreground — relax the GPS to save battery. */
    fun onAppBackgrounded() {
        // The controller keeps the radio alive when a recording is active (the
        // foreground service continues the background track); otherwise it stops it.
        locationController.setMapVisible(false)
    }

    private fun observeUserLocation() {
        viewModelScope.launch {
            locationController.locationFlow().collect { location ->
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

    fun onLocationPermissionGranted() = locationController.refresh()

    fun centerMapOnUserLocation() {
        _userLocation.value?.let {
            _mapCameraTarget.value = MapCameraTarget(it.latitude, it.longitude, zoom = 16.0)
        }
    }

    fun onMapCameraTargetHandled() { _mapCameraTarget.value = null }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun startInAppNavigation(space: BikeParkingSpace) {
        // Navigating to a normal destination is not a planned-route ride: clear any
        // armed leaderboard-attempt context so this ride isn't wrongly recorded.
        routePlanningController.cancelPendingRide()
        navigationController.start(space)
    }

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
        routePlanningController.cancelPendingRide()
        navigationController.startRoundTrip(destination, distanceMeters)
    }

    fun stopInAppNavigation() = navigationController.stop()
    fun cancelRouteCalculation() = navigationController.cancelRouteCalculation()

    /** Called by the `NavigationManager` when the rider has strayed off-route. */
    fun onUserWentOffRoute() = navigationController.onUserWentOffRoute()

    fun clearNavigationError() = navigationController.clearError()

    // ── Route planning + leaderboards ─────────────────────────────────────────

    /** Enters route-planning mode: empty-map taps now drop ordered waypoints. */
    fun startRoutePlanning() {
        clearPlaceSelections()
        routePlanningController.startPlanning()
    }

    fun cancelRoutePlanning() = routePlanningController.cancelPlanning()
    fun undoLastWaypoint()    = routePlanningController.undoLastWaypoint()
    fun removeWaypointAt(index: Int) = routePlanningController.removeWaypointAt(index)

    /** Saves the current planning session as a named route (false if not routable yet). */
    fun savePlannedRoute(name: String): Boolean = routePlanningController.saveRoute(name)

    fun renamePlannedRoute(id: String, name: String) = routePlanningController.renameRoute(id, name)
    fun deletePlannedRoute(id: String) = routePlanningController.deleteRoute(id)

    fun openRouteLeaderboard(route: PlannedRoute) = routePlanningController.openLeaderboard(route)
    fun closeRouteLeaderboard() = routePlanningController.closeLeaderboard()
    fun deleteRouteAttempt(id: String) = routePlanningController.deleteAttempt(id)

    /**
     * Rides a saved [route] forward or backwards: arms the leaderboard-attempt
     * context, then starts turn-by-turn navigation through the route's stops (from
     * the rider's current position). Reverse rides land on their own leaderboard.
     */
    fun ridePlannedRoute(route: PlannedRoute, reversed: Boolean) {
        val direction = if (reversed) RideDirection.REVERSE else RideDirection.FORWARD
        val waypoints = routePlanningController.beginRide(route, direction) ?: return
        val last = waypoints.last()
        val label = if (reversed) {
            context.getString(de.velospot.R.string.route_ride_reverse_name, route.name)
        } else {
            route.name
        }
        val destination = syntheticSpace(
            id          = ID_PLANNED_ROUTE,
            latitude    = last.latitude,
            longitude   = last.longitude,
            name        = label,
            address     = null,
            sourceLayer = "planned_route"
        )
        closeRouteLeaderboard()
        navigationController.startVia(destination, waypoints)
    }

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
        recordingManager.routeElevationProvider = null
        // The map UI is gone: drop its location needs. A still-running background
        // recording keeps the GPS alive via the controller's recording need (and the
        // foreground service); otherwise the controller releases the radio.
        locationController.setNavigating(false)
        locationController.setMapVisible(false)
    }
}
