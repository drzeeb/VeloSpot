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
import de.velospot.feature.map.presentation.markers.MarkerDisplayConfig
import de.velospot.feature.map.presentation.markers.MarkerIconSet
import de.velospot.feature.map.presentation.markers.MarkerRenderLabels
import de.velospot.feature.map.presentation.markers.MarkerRenderState
import de.velospot.feature.map.presentation.markers.MIN_ZOOM_PARKING_VISIBLE
import de.velospot.feature.map.presentation.markers.RouteRenderData
import de.velospot.feature.map.presentation.markers.createBikeMarkerIcon
import de.velospot.feature.map.presentation.markers.createLocationMarkerIcon
import de.velospot.feature.map.presentation.markers.createMutedMarkerIcon
import de.velospot.feature.map.presentation.markers.defaultMarkerStyleConfig
import de.velospot.feature.map.presentation.markers.updateMarkers
import de.velospot.feature.map.presentation.sheets.MapBottomSheets
import de.velospot.feature.map.presentation.sheets.languageFlagForCode
import de.velospot.feature.map.presentation.sheets.resolveCurrentLanguageCode
import kotlin.math.roundToInt
import org.maplibre.android.maps.MapLibreMap


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
    val mapCameraTarget      by viewModel.mapCameraTarget.collectAsStateWithLifecycle()
    val navigationUiState    by viewModel.navigationUiState.collectAsStateWithLifecycle()
    val offlineRoutingUiState by viewModel.offlineRoutingUiState.collectAsStateWithLifecycle()
    val searchQuery          by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults        by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching          by viewModel.isSearching.collectAsStateWithLifecycle()
    val selectedSearchPin    by viewModel.selectedSearchPin.collectAsStateWithLifecycle()
    val customMapPin         by viewModel.customMapPin.collectAsStateWithLifecycle()
    val savedPlaces          by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val layerVisibility      by viewModel.layerVisibility.collectAsStateWithLifecycle()

    val activeNavigation = navigationUiState as? NavigationUiState.Active

    val viewportLoadError by viewModel.viewportLoadError.collectAsStateWithLifecycle()
    val viewportErrorText = stringResource(R.string.error_loading_parking)
    LaunchedEffect(viewportLoadError) {
        if (viewportLoadError != null) {
            Toast.makeText(context, viewportErrorText, Toast.LENGTH_SHORT).show()
            viewModel.clearViewportLoadError()
        }
    }

    val mapView       = rememberMapViewWithLifecycle()
    val screenUiState = rememberMapScreenUiState()
    val markerStyleConfig = remember(isDarkTheme) { defaultMarkerStyleConfig(isDarkTheme) }

    // The MapLibreMap is provided asynchronously via getMapAsync.
    // Using mutableStateOf triggers recomposition so LaunchedEffects below fire.
    var maplibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var zoomBucket  by remember { mutableIntStateOf(DEFAULT_ZOOM.roundToInt()) }

    // Incremented every time a (new) style finishes loading. Re-loading the style –
    // e.g. when toggling dark mode – wipes all custom sources/layers/images, so we
    // use this as a key to re-run the marker rendering effect and rebuild them.
    var styleVersion by remember { mutableIntStateOf(0) }


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
    val normalMarkerIcon = remember(context, zoomBucket, markerStyleConfig) {
        createBikeMarkerIcon(context, zoomBucket, markerStyleConfig.normalPinColor)
    }
    val favoriteMarkerIcon = remember(context, zoomBucket, markerStyleConfig) {
        createBikeMarkerIcon(context, zoomBucket, markerStyleConfig.favoritePinColor)
    }
    val selectedMarkerIcon = remember(context, zoomBucket, markerStyleConfig) {
        createBikeMarkerIcon(context, zoomBucket, markerStyleConfig.selectedPinColor)
    }
    val mutedNormalMarkerIcon = remember(context, zoomBucket, markerStyleConfig) {
        createMutedMarkerIcon(context, normalMarkerIcon, markerStyleConfig.mutedScale,
            markerStyleConfig.mutedAlpha, markerStyleConfig.mutedBrightenOffset)
    }
    val mutedFavoriteMarkerIcon = remember(context, zoomBucket, markerStyleConfig) {
        createMutedMarkerIcon(context, favoriteMarkerIcon, markerStyleConfig.mutedScale,
            markerStyleConfig.mutedAlpha, markerStyleConfig.mutedBrightenOffset)
    }
    val mutedSelectedMarkerIcon = remember(context, zoomBucket, markerStyleConfig) {
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
        selectedSearchPin, customMapPin, styleVersion, savedPlaces, layerVisibility
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
                    customMapPin = customMapPin,
                    savedPlaces  = savedPlaces,
                    layerVisibility = layerVisibility
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

    // Stable reference to the saved places so the once-registered map click
    // listener always sees the current list when hit-testing the saved layer.
    val savedPlacesRef = remember { mutableStateOf(savedPlaces) }
    LaunchedEffect(savedPlaces) { savedPlacesRef.value = savedPlaces }

    DisposableEffect(mapView) {
        mapView.initVeloSpotMap(
            viewModel          = viewModel,
            currentSpaces      = { (uiStateRef.value as? MapUiState.Success)?.spaces.orEmpty() },
            currentSavedPlaces = { savedPlacesRef.value },
            onZoomBucketChanged = { next -> if (next != zoomBucket) zoomBucket = next },
            onMapReady         = { maplibreMap = it }
        )
        onDispose { maplibreMap = null }
    }

    // ── Style loading / dark-mode switching ───────────────────────────────────
    // Loads the light or dark tile style depending on the current theme. Runs on
    // first map creation and again whenever the user toggles dark mode. Re-loading
    // the style discards all custom sources/layers/images, so we bump styleVersion
    // to re-run the marker rendering effect above.
    LaunchedEffect(maplibreMap, isDarkTheme) {
        val map = maplibreMap ?: return@LaunchedEffect
        map.setStyle(mapStyleUrl(isDarkTheme)) { _ ->
            styleVersion++
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
                    favoritesCount     = favorites.size + savedPlaces.size,
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
                    onOpenLayers          = screenUiState::openLayers,
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

    // ── Bottom sheets & dialogs ───────────────────────────────────────────────
    MapBottomSheets(viewModel = viewModel, screenUiState = screenUiState)
}


