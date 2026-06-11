package de.velospot.feature.map.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
import de.velospot.core.map.NavigationHandler
import de.velospot.domain.model.BoundingBox
import kotlin.math.roundToInt
import android.view.Gravity
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

// Free vector tile style from OpenFreeMap – no API key required.
// Switch to any other MapLibre-compatible style URL here.
private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val TRIER_LAT   = 49.7596
private const val TRIER_LON   = 6.6441
private const val DEFAULT_ZOOM = 14.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMapScreen(
    isDarkTheme: Boolean = false,
    onDarkThemeToggle: () -> Unit = {},
    viewModel: MapViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState              by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedSpace        by viewModel.selectedSpace.collectAsStateWithLifecycle()
    val favorites            by viewModel.favorites.collectAsStateWithLifecycle()
    val userLocation         by viewModel.userLocation.collectAsStateWithLifecycle()
    val favoriteSpaces       by viewModel.favoriteSpaces.collectAsStateWithLifecycle()
    val mapCameraTarget      by viewModel.mapCameraTarget.collectAsStateWithLifecycle()
    val navigationUiState    by viewModel.navigationUiState.collectAsStateWithLifecycle()
    val offlineRoutingUiState by viewModel.offlineRoutingUiState.collectAsStateWithLifecycle()
    val showOfflineSetupSheet by viewModel.showOfflineSetupSheet.collectAsStateWithLifecycle()
    val showProfileSheet     by viewModel.showProfileSheet.collectAsStateWithLifecycle()
    val showWifiWarning      by viewModel.showWifiWarning.collectAsStateWithLifecycle()
    val searchQuery          by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults        by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching          by viewModel.isSearching.collectAsStateWithLifecycle()
    val selectedSearchPin    by viewModel.selectedSearchPin.collectAsStateWithLifecycle()
    val customMapPin         by viewModel.customMapPin.collectAsStateWithLifecycle()
    val customMapPinAddress  by viewModel.customMapPinAddress.collectAsStateWithLifecycle()

    val activeNavigation = navigationUiState as? NavigationUiState.Active

    val mapView       = rememberMapViewWithLifecycle()
    val screenUiState = rememberMapScreenUiState()
    val markerStyleConfig = remember { defaultMarkerStyleConfig() }

    // The MapLibreMap is provided asynchronously via getMapAsync.
    // Using mutableStateOf triggers recomposition so LaunchedEffects below fire.
    var maplibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var zoomBucket  by remember { mutableIntStateOf(DEFAULT_ZOOM.roundToInt()) }

    // Show a Toast when the user zooms out below the minimum parking marker level.
    // Only fires on the false→true transition, not on further zoom-out.
    val isZoomedOutForParking = zoomBucket < MIN_ZOOM_PARKING_VISIBLE.toInt()
    val zoomHintText = stringResource(R.string.zoom_in_for_parking)
    LaunchedEffect(isZoomedOutForParking) {
        if (isZoomedOutForParking) {
            Toast.makeText(context, zoomHintText, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Permission handling ───────────────────────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.any { it.value }) viewModel.onLocationPermissionGranted()
    }
    val requestOrUseLocation: () -> Unit = {
        requestLocationAccessIfNeeded(
            context       = context,
            onPermissionGranted = {
                viewModel.onLocationPermissionGranted()
                viewModel.centerMapOnUserLocation()
            },
            requestPermissions = permissionLauncher::launch
        )
    }
    LaunchedEffect(Unit) {
        requestLocationAccessIfNeeded(
            context            = context,
            onPermissionGranted = viewModel::onLocationPermissionGranted,
            requestPermissions  = permissionLauncher::launch
        )
    }

    // ── Pre-compute icons (zoom-dependent) ───────────────────────────────────
    val normalMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, markerStyleConfig.normalPinColor)
    }
    val favoriteMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, markerStyleConfig.favoritePinColor)
    }
    val selectedMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, markerStyleConfig.selectedPinColor)
    }
    val mutedNormalMarkerIcon = remember(context, zoomBucket) {
        createMutedMarkerIcon(context, normalMarkerIcon, markerStyleConfig.mutedScale,
            markerStyleConfig.mutedAlpha, markerStyleConfig.mutedBrightenOffset)
    }
    val mutedFavoriteMarkerIcon = remember(context, zoomBucket) {
        createMutedMarkerIcon(context, favoriteMarkerIcon, markerStyleConfig.mutedScale,
            markerStyleConfig.mutedAlpha, markerStyleConfig.mutedBrightenOffset)
    }
    val mutedSelectedMarkerIcon = remember(context, zoomBucket) {
        createMutedMarkerIcon(context, selectedMarkerIcon, markerStyleConfig.mutedScale,
            markerStyleConfig.mutedAlpha, markerStyleConfig.mutedBrightenOffset)
    }
    val locationMarkerIcon = remember(context, activeNavigation != null) {
        createLocationMarkerIcon(context, isNavigationActive = activeNavigation != null)
    }

    // ── Language helpers ──────────────────────────────────────────────────────
    val configuration        = LocalConfiguration.current
    val currentLanguageCode  = remember(configuration) {
        resolveCurrentLanguageCode(context, configuration.locales.get(0)?.language.orEmpty())
    }
    val currentLanguageFlag  = languageFlagForCode(currentLanguageCode)
    val myLocationTitle      = stringResource(R.string.map_my_location)
    val snippetSpacesFormat  = stringResource(R.string.map_snippet_spaces_format)

    // ── Marker update whenever relevant state changes ─────────────────────────
    LaunchedEffect(
        maplibreMap, uiState, favorites, selectedSpace,
        userLocation, activeNavigation, zoomBucket,
        normalMarkerIcon, favoriteMarkerIcon, selectedMarkerIcon,
        selectedSearchPin, customMapPin
    ) {
        val map = maplibreMap ?: return@LaunchedEffect
        if (uiState is MapUiState.Success) {
            val spaces = (uiState as MapUiState.Success).spaces
            updateMarkers(
                map       = map,
                spaces    = spaces,
                icons     = MarkerIconSet(
                    normal           = normalMarkerIcon,
                    favorite         = favoriteMarkerIcon,
                    selected         = selectedMarkerIcon,
                    activeNavigation = selectedMarkerIcon,
                    mutedNormal      = mutedNormalMarkerIcon,
                    mutedFavorite    = mutedFavoriteMarkerIcon,
                    mutedSelected    = mutedSelectedMarkerIcon,
                    location         = locationMarkerIcon
                ),
                state     = MarkerRenderState(
                    favoriteIds              = favorites,
                    selectedSpaceId          = selectedSpace?.id,
                    activeNavigationSpaceId  = activeNavigation?.destination?.id,
                    userLocation             = userLocation
                ),
                display   = MarkerDisplayConfig(
                    context = context,
                    labels  = MarkerRenderLabels(myLocationTitle, snippetSpacesFormat)
                ),
                    route     = RouteRenderData(
                        color  = markerStyleConfig.routeColor,
                        points = activeNavigation?.route?.points.orEmpty()
                    ),
                    searchPin    = selectedSearchPin,
                    customMapPin = customMapPin
                )
        }
    }

    // ── Camera animation ──────────────────────────────────────────────────────
    LaunchedEffect(mapCameraTarget, maplibreMap) {
        val target = mapCameraTarget ?: return@LaunchedEffect
        val map    = maplibreMap    ?: return@LaunchedEffect
        animateMapCameraToTarget(map = map, cameraTarget = target)
        viewModel.onMapCameraTargetHandled()
    }

    // ── Map initialisation (runs once when mapView is created) ────────────────
    // We keep a stable reference to spacesProvider so getMapAsync doesn't
    // capture a stale uiState snapshot.
    val uiStateRef = remember { mutableStateOf(uiState) }
    LaunchedEffect(uiState) { uiStateRef.value = uiState }

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            map.setStyle(MAP_STYLE_URL) { _ ->
                // Compass bottom-left – clear of the search bar (top) and menu card (top-right).
                map.uiSettings.compassGravity = Gravity.BOTTOM or Gravity.START
                map.uiSettings.setCompassMargins(16, 0, 0, 120)

                // Initial camera position
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(TRIER_LAT, TRIER_LON))
                            .zoom(DEFAULT_ZOOM)
                            .build()
                    )
                )

                // Viewport → load nearby spots when camera comes to rest
                map.addOnCameraIdleListener {
                    val bounds = map.projection.visibleRegion.latLngBounds
                    val sw = bounds.southWest
                    val ne = bounds.northEast
                    viewModel.onViewportChanged(
                        BoundingBox(
                            minLat = sw.latitude,
                            minLon = sw.longitude,
                            maxLat = ne.latitude,
                            maxLon = ne.longitude
                        )
                    )
                }

                // Zoom bucket tracking (for icon size)
                map.addOnCameraMoveListener {
                    val next = map.cameraPosition.zoom.roundToInt()
                    if (next != zoomBucket) zoomBucket = next
                }

                // Click → find the parking spot whose GeoJSON feature was tapped;
                // if the tap hits empty space, place a custom pin there.
                map.addOnMapClickListener { latLng ->
                    val screenPoint = map.projection.toScreenLocation(latLng)
                    val features    = map.queryRenderedFeatures(screenPoint, LAYER_PARKING)
                    val spaceId     = features.firstOrNull()?.getStringProperty(PROP_SPACE_ID)
                    val spaces      = (uiStateRef.value as? MapUiState.Success)?.spaces.orEmpty()
                    val clicked     = spaces.find { it.id == spaceId }
                    if (clicked != null) {
                        viewModel.selectSpace(clicked)
                    } else {
                        viewModel.onMapTapped(latLng.latitude, latLng.longitude)
                    }
                    true
                }

                // Signal Compose that the map is ready → triggers LaunchedEffects above
                maplibreMap = map
            }
        }
        onDispose { maplibreMap = null }
    }

    // ── Navigation handlers ───────────────────────────────────────────────────
    val startNavigationHandler: NavigationHandler = remember(viewModel) {
        { space ->
            screenUiState.closeFavorites()
            viewModel.selectSpace(null)
            viewModel.startInAppNavigation(space)
        }
    }
    val showDetailsHandler: NavigationHandler = remember(viewModel) {
        { space ->
            screenUiState.closeFavorites()
            viewModel.selectSpace(space)
        }
    }

    // ── UI layout ─────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory  = { mapView },
            modifier = Modifier.fillMaxSize()
            // No update block needed – all updates go through LaunchedEffect above.
        )

        MapStatusOverlay(uiState = uiState)
        MapNavigationOverlay(
            navigationUiState = navigationUiState,
            onStopNavigation  = viewModel::stopInAppNavigation,
            onDismissError    = viewModel::clearNavigationError
        )


        // ── Search bar + Menu button – vertically centred in one Row ─────────
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AddressSearchBar(
                modifier         = Modifier.weight(1f),
                query            = searchQuery,
                results          = searchResults,
                isSearching      = isSearching,
                onQueryChange    = viewModel::onSearchQueryChanged,
                onResultSelected = viewModel::onSearchResultSelected,
                onClear          = viewModel::onSearchCleared
            )
            Spacer(Modifier.width(8.dp))
            MapMenuCard(
                state = MapMenuCardState(
                    favoritesCount     = favorites.size,
                    isDarkTheme        = isDarkTheme,
                    currentLanguageFlag = currentLanguageFlag,
                    isExpanded         = screenUiState.isMenuExpanded,
                    offlineRoutingUiState = offlineRoutingUiState
                ),
                actions = MapMenuCardActions(
                    onExpand              = screenUiState::expandMenu,
                    onDismiss             = screenUiState::dismissMenu,
                    onOpenFavorites       = screenUiState::openFavorites,
                    onOpenLanguage        = screenUiState::openLanguage,
                    onToggleDarkMode      = { onDarkThemeToggle(); screenUiState.dismissMenu() },
                    onActivateOfflineRouting = viewModel::requestOfflineRoutingSetup,
                    onOpenProfileSheet    = viewModel::openProfileSheet
                )
            )
        }

        when (val offState = offlineRoutingUiState) {
            is OfflineRoutingUiState.Downloading      -> OfflineSetupProgressOverlay(state = offState)
            is OfflineRoutingUiState.DownloadComplete -> OfflineSetupSuccessOverlay()
            else -> Unit
        }

        MyLocationFab(onClick = requestOrUseLocation)
    }

    // ── Bottom sheets ─────────────────────────────────────────────────────────
    if (screenUiState.isFavoritesSheetVisible) {
        FavoritesSheet(
            spaces          = favoriteSpaces,
            favoriteIds     = favorites,
            onDismiss       = screenUiState::closeFavorites,
            onStartNavigation = startNavigationHandler,
            onShowDetails   = showDetailsHandler,
            onToggleFavorite = viewModel::toggleFavorite
        )
    }

    if (screenUiState.isLanguageSheetVisible) {
        LanguageSheet(
            currentLanguageCode = currentLanguageCode,
            onDismiss           = screenUiState::closeLanguage,
            onSelectLanguage    = { languageCode ->
                applyLanguageSelection(context, languageCode)
                screenUiState.closeLanguage()
            }
        )
    }

    if (showOfflineSetupSheet) {
        OfflineRoutingSetupSheet(
            onConfirm = viewModel::confirmOfflineRoutingSetup,
            onDismiss = viewModel::dismissOfflineSetupSheet
        )
    }

    if (showWifiWarning) {
        WifiWarningDialog(
            onConfirm = viewModel::confirmDownloadOnMobileData,
            onDismiss = viewModel::dismissWifiWarning
        )
    }

    if (showProfileSheet) {
        val currentProfile = (offlineRoutingUiState as? OfflineRoutingUiState.Enabled)?.profile
            ?: de.velospot.data.brouter.BRouterProfile.TREKKING
        RoutingProfileSheet(
            currentProfile         = currentProfile,
            onSelectProfile        = viewModel::selectRoutingProfile,
            onDismiss              = viewModel::dismissProfileSheet,
            onDisableOfflineRouting = viewModel::disableOfflineRouting
        )
    }

    selectedSpace?.let { space ->
        SelectedSpaceSheet(
            space           = space,
            onDismiss       = { viewModel.selectSpace(null) },
            onNavigate      = startNavigationHandler,
            isFavorite      = favorites.contains(space.id),
            onToggleFavorite = viewModel::toggleFavorite
        )
    }

    selectedSearchPin?.let { pin ->
        SearchPinSheet(
            result     = pin,
            onDismiss  = viewModel::dismissSearchPin,
            onNavigate = viewModel::startNavigationToAddress
        )
    }

        customMapPin?.let { pin ->
        // Hide the sheet while actively navigating to this pin –
        // the pin stays visible on the map as a route end-point.
        val navigatingToPin = activeNavigation?.destination?.id == "custom_map_pin"
        if (!navigatingToPin) {
            CustomMapPinSheet(
                pin        = pin,
                address    = customMapPinAddress,
                onDismiss  = viewModel::dismissCustomMapPin,
                onNavigate = viewModel::startNavigationToCustomPin,
                onRemove   = viewModel::dismissCustomMapPin
            )
        }
    }
}


