package de.velospot.feature.map.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
import de.velospot.core.map.NavigationHandler
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
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
    var zoomBucket by remember { mutableIntStateOf(DEFAULT_ZOOM.roundToInt()) }
    var isMenuExpanded by remember { mutableStateOf(false) }
    var isFavoritesSheetVisible by remember { mutableStateOf(false) }
    var isLanguageSheetVisible by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.any { it.value }) {
            viewModel.onLocationPermissionGranted()
        }
    }

    val requestOrUseLocation: () -> Unit = {
        if (hasLocationPermission(context)) {
            viewModel.onLocationPermissionGranted()
            viewModel.centerMapOnUserLocation()
        } else {
            permissionLauncher.launch(locationPermissions())
        }
    }

    LaunchedEffect(Unit) {
        if (hasLocationPermission(context)) {
            viewModel.onLocationPermissionGranted()
        } else {
            permissionLauncher.launch(locationPermissions())
        }
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
        createBikeMarkerIcon(context, zoomBucket, pinColor = "#0A2A66".toColorInt())
    }
    val favoriteMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, pinColor = "#D32F2F".toColorInt())
    }
    val selectedMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, pinColor = "#FF8F00".toColorInt())
    }
    val activeNavigationMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, pinColor = "#FF8F00".toColorInt())
    }
    val mutedNormalMarkerIcon = remember(context, zoomBucket) {
        createMutedMarkerIcon(context, normalMarkerIcon)
    }
    val mutedFavoriteMarkerIcon = remember(context, zoomBucket) {
        createMutedMarkerIcon(context, favoriteMarkerIcon)
    }
    val mutedSelectedMarkerIcon = remember(context, zoomBucket) {
        createMutedMarkerIcon(context, selectedMarkerIcon)
    }
    val locationMarkerIcon = remember(context, activeNavigation != null) {
        createLocationMarkerIcon(context, isNavigationActive = activeNavigation != null)
    }
    val startNavigationHandler: NavigationHandler = remember(viewModel) {
        { space ->
            isFavoritesSheetVisible = false
            viewModel.selectSpace(null)
            viewModel.startInAppNavigation(space)
        }
    }
    val showDetailsHandler: NavigationHandler = remember(viewModel) {
        { space ->
            isFavoritesSheetVisible = false
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
        val startZoom = mapView.zoomLevelDouble
        val targetZoom = cameraTarget.zoom
        val baseCenter = GeoPoint(cameraTarget.latitude, cameraTarget.longitude)
        val hasZoomChange = kotlin.math.abs(targetZoom - startZoom) > 0.01
        val startCenter = mapView.mapCenter
        val startLat = startCenter.latitude
        val startLon = startCenter.longitude
        val startLatitudeSpan = mapView.boundingBox?.latitudeSpan ?: 0.0

        if (!hasZoomChange) {
            val latitudeSpan = mapView.boundingBox?.latitudeSpan ?: 0.0
            val adjustedCenter = if (cameraTarget.verticalOffsetFraction > 0.0) {
                GeoPoint(
                    baseCenter.latitude - (latitudeSpan * cameraTarget.verticalOffsetFraction),
                    baseCenter.longitude
                )
            } else {
                baseCenter
            }
            // Fast smooth shift (~120 ms) when zoom already matches target.
            val steps = 8
            repeat(steps) { index ->
                val t = (index + 1).toDouble() / steps
                val eased = t * t * (3 - 2 * t)
                mapView.controller.setCenter(
                    GeoPoint(
                        startLat + (adjustedCenter.latitude - startLat) * eased,
                        startLon + (adjustedCenter.longitude - startLon) * eased
                    )
                )
                delay(15.milliseconds)
            }
            mapView.controller.setCenter(adjustedCenter)
            viewModel.onMapCameraTargetHandled()
            return@LaunchedEffect
        }

        // Estimate final latitude span for target zoom to compute the final vertical offset first.
        val zoomDelta = targetZoom - startZoom
        val targetLatitudeSpan = if (startLatitudeSpan > 0.0) {
            startLatitudeSpan / Math.pow(2.0, zoomDelta)
        } else {
            0.0
        }
        val adjustedCenter = if (cameraTarget.verticalOffsetFraction > 0.0) {
            GeoPoint(
                baseCenter.latitude - (targetLatitudeSpan * cameraTarget.verticalOffsetFraction),
                baseCenter.longitude
            )
        } else {
            baseCenter
        }

        // Single fast smooth animation: zoom + center together (~180 ms).
        val steps = 12
        repeat(steps) { index ->
            val t = (index + 1).toDouble() / steps
            val eased = t * t * (3 - 2 * t)
            mapView.controller.setZoom(startZoom + (targetZoom - startZoom) * eased)
            mapView.controller.setCenter(
                GeoPoint(
                    startLat + (adjustedCenter.latitude - startLat) * eased,
                    startLon + (adjustedCenter.longitude - startLon) * eased
                )
            )
            delay(15.milliseconds)
        }

        mapView.controller.setZoom(targetZoom)
        mapView.controller.setCenter(adjustedCenter)
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
            isExpanded = isMenuExpanded,
            onExpand = { isMenuExpanded = true },
            onDismiss = { isMenuExpanded = false },
            onOpenFavorites = {
                isFavoritesSheetVisible = true
                isMenuExpanded = false
            },
            onOpenLanguage = {
                isLanguageSheetVisible = true
                isMenuExpanded = false
            },
            onToggleDarkMode = {
                onDarkThemeToggle()
                isMenuExpanded = false
            }
        )

        MyLocationFab(onClick = requestOrUseLocation)
    }

    if (isFavoritesSheetVisible) {
        FavoritesSheet(
            spaces = (uiState as? MapUiState.Success)?.spaces.orEmpty(),
            favoriteIds = favorites,
            onDismiss = { isFavoritesSheetVisible = false },
            onStartNavigation = startNavigationHandler,
            onShowDetails = showDetailsHandler,
            onToggleFavorite = viewModel::toggleFavorite
        )
    }

    if (isLanguageSheetVisible) {
        LanguageSheet(
            currentLanguageCode = currentLanguageCode,
            onDismiss = { isLanguageSheetVisible = false },
            onSelectLanguage = { languageCode ->
                applyLanguageSelection(context, languageCode)
                isLanguageSheetVisible = false
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

private fun hasLocationPermission(context: Context): Boolean {
    val fineGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fineGranted || coarseGranted
}

private fun locationPermissions(): Array<String> = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

