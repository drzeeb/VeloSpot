package de.velospot.feature.map.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
import de.velospot.core.map.NavigationHandler
import kotlin.math.roundToInt
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint

private const val TRIER_LAT = 49.7596
private const val TRIER_LON = 6.6441
private const val DEFAULT_ZOOM = 14.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMapScreen(
    isDarkTheme: Boolean = false,
    onDarkThemeToggle: () -> Unit = {},
    viewModel: MapViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedSpace by viewModel.selectedSpace.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val userLocation by viewModel.userLocation.collectAsStateWithLifecycle()
    val mapCameraTarget by viewModel.mapCameraTarget.collectAsStateWithLifecycle()
    val navigationUiState by viewModel.navigationUiState.collectAsStateWithLifecycle()
    val activeNavigation = navigationUiState as? NavigationUiState.Active

    val mapView = rememberMapViewWithLifecycle()
    val screenUiState = rememberMapScreenUiState()
    var zoomBucket by remember { mutableIntStateOf(DEFAULT_ZOOM.roundToInt()) }
    val markerStyleConfig = remember { defaultMarkerStyleConfig() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.any { it.value }) {
            viewModel.onLocationPermissionGranted()
        }
    }

    val requestOrUseLocation: () -> Unit = {
        requestLocationAccessIfNeeded(
            context = context,
            onPermissionGranted = {
                viewModel.onLocationPermissionGranted()
                viewModel.centerMapOnUserLocation()
            },
            requestPermissions = permissionLauncher::launch
        )
    }

    LaunchedEffect(Unit) {
        requestLocationAccessIfNeeded(
            context = context,
            onPermissionGranted = viewModel::onLocationPermissionGranted,
            requestPermissions = permissionLauncher::launch
        )
    }

    DisposableEffect(mapView) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false

            override fun onZoom(event: ZoomEvent?): Boolean {
                val nextZoomBucket = (event?.zoomLevel ?: mapView.zoomLevelDouble).roundToInt()
                if (nextZoomBucket != zoomBucket) {
                    zoomBucket = nextZoomBucket
                }
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose {
            mapView.removeMapListener(listener)
        }
    }

    remember(mapView) {
        mapView.controller.apply {
            setZoom(DEFAULT_ZOOM)
            setCenter(GeoPoint(TRIER_LAT, TRIER_LON))
        }
    }

    val normalMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, pinColor = markerStyleConfig.normalPinColor)
    }
    val favoriteMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, pinColor = markerStyleConfig.favoritePinColor)
    }
    val selectedMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, pinColor = markerStyleConfig.selectedPinColor)
    }
    val activeNavigationMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, pinColor = markerStyleConfig.selectedPinColor)
    }
    val mutedNormalMarkerIcon = remember(context, zoomBucket) {
        createMutedMarkerIcon(
            context = context,
            source = normalMarkerIcon,
            scale = markerStyleConfig.mutedScale,
            alpha = markerStyleConfig.mutedAlpha,
            brightenOffset = markerStyleConfig.mutedBrightenOffset
        )
    }
    val mutedFavoriteMarkerIcon = remember(context, zoomBucket) {
        createMutedMarkerIcon(
            context = context,
            source = favoriteMarkerIcon,
            scale = markerStyleConfig.mutedScale,
            alpha = markerStyleConfig.mutedAlpha,
            brightenOffset = markerStyleConfig.mutedBrightenOffset
        )
    }
    val mutedSelectedMarkerIcon = remember(context, zoomBucket) {
        createMutedMarkerIcon(
            context = context,
            source = selectedMarkerIcon,
            scale = markerStyleConfig.mutedScale,
            alpha = markerStyleConfig.mutedAlpha,
            brightenOffset = markerStyleConfig.mutedBrightenOffset
        )
    }
    val locationMarkerIcon = remember(context, activeNavigation != null) {
        createLocationMarkerIcon(context, isNavigationActive = activeNavigation != null)
    }
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
    val myLocationTitle = stringResource(id = R.string.map_my_location)
    val snippetSpacesFormat = stringResource(id = R.string.map_snippet_spaces_format)
    val configuration = LocalConfiguration.current
    val currentLanguageCode = remember(configuration) {
        resolveCurrentLanguageCode(
            context = context,
            fallbackLanguage = configuration.locales.get(0)?.language.orEmpty()
        )
    }
    val currentLanguageFlag = languageFlagForCode(currentLanguageCode)

    LaunchedEffect(mapCameraTarget) {
        val cameraTarget = mapCameraTarget ?: return@LaunchedEffect
        animateMapCameraToTarget(mapView = mapView, cameraTarget = cameraTarget)
        viewModel.onMapCameraTargetHandled()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                if (uiState is MapUiState.Success) {
                    val spaces = (uiState as MapUiState.Success).spaces
                    updateMarkers(
                        map = map,
                        spaces = spaces,
                        normalMarkerIcon = normalMarkerIcon,
                        favoriteMarkerIcon = favoriteMarkerIcon,
                        selectedMarkerIcon = selectedMarkerIcon,
                        activeNavigationMarkerIcon = activeNavigationMarkerIcon,
                        mutedNormalMarkerIcon = mutedNormalMarkerIcon,
                        mutedFavoriteMarkerIcon = mutedFavoriteMarkerIcon,
                        mutedSelectedMarkerIcon = mutedSelectedMarkerIcon,
                        locationMarkerIcon = locationMarkerIcon,
                        favoriteIds = favorites,
                        selectedSpaceId = selectedSpace?.id,
                        activeNavigationSpaceId = activeNavigation?.destination?.id,
                        userLocation = userLocation,
                        context = context,
                        myLocationTitle = myLocationTitle,
                        snippetSpacesFormat = snippetSpacesFormat,
                        routeColor = markerStyleConfig.routeColor,
                        routePoints = activeNavigation?.route?.points.orEmpty(),
                        onMarkerClick = viewModel::selectSpace
                    )
                }
            }
        )

        MapStatusOverlay(uiState = uiState)
        MapNavigationOverlay(
            navigationUiState = navigationUiState,
            onStopNavigation = viewModel::stopInAppNavigation,
            onDismissError = viewModel::clearNavigationError
        )

        MapMenuCard(
            favoritesCount = favorites.size,
            isDarkTheme = isDarkTheme,
            currentLanguageFlag = currentLanguageFlag,
            isExpanded = screenUiState.isMenuExpanded,
            onExpand = screenUiState::expandMenu,
            onDismiss = screenUiState::dismissMenu,
            onOpenFavorites = screenUiState::openFavorites,
            onOpenLanguage = screenUiState::openLanguage,
            onToggleDarkMode = {
                onDarkThemeToggle()
                screenUiState.dismissMenu()
            }
        )

        MyLocationFab(onClick = requestOrUseLocation)
    }

    if (screenUiState.isFavoritesSheetVisible) {
        FavoritesSheet(
            spaces = (uiState as? MapUiState.Success)?.spaces.orEmpty(),
            favoriteIds = favorites,
            onDismiss = screenUiState::closeFavorites,
            onStartNavigation = startNavigationHandler,
            onShowDetails = showDetailsHandler,
            onToggleFavorite = viewModel::toggleFavorite
        )
    }

    if (screenUiState.isLanguageSheetVisible) {
        LanguageSheet(
            currentLanguageCode = currentLanguageCode,
            onDismiss = screenUiState::closeLanguage,
            onSelectLanguage = { languageCode ->
                applyLanguageSelection(context, languageCode)
                screenUiState.closeLanguage()
            }
        )
    }

    selectedSpace?.let { space ->
        SelectedSpaceSheet(
            space = space,
            onDismiss = { viewModel.selectSpace(null) },
            onNavigate = startNavigationHandler,
            isFavorite = favorites.contains(space.id),
            onToggleFavorite = viewModel::toggleFavorite
        )
    }
}


