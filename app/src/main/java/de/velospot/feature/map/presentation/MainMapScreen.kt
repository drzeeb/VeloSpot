package de.velospot.feature.map.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import de.velospot.R
import de.velospot.core.locale.LanguagePreferences
import de.velospot.core.map.NavigationHandler
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.RoutePoint
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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
        val appLocaleLanguage = AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()
        if (appLocaleLanguage.isNotBlank()) {
            appLocaleLanguage
        } else {
            LanguagePreferences.getSavedLanguageCode(context)
                ?: configuration.locales.get(0)?.language.orEmpty().ifBlank { "en" }
        }
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
                delay(15)
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
            delay(15)
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
                LanguagePreferences.saveLanguageCode(context, languageCode)
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(languageCode)
                )
                (context as? Activity)?.recreate()
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

@Composable
private fun BoxScope.MapStatusOverlay(uiState: MapUiState) {
    if (uiState is MapUiState.Loading) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }

    if (uiState is MapUiState.Error) {
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = uiState.message,
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun BoxScope.MapNavigationOverlay(
    navigationUiState: NavigationUiState,
    onStopNavigation: () -> Unit,
    onDismissError: () -> Unit
) {
    when (navigationUiState) {
        is NavigationUiState.Idle -> Unit
        is NavigationUiState.Loading -> {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(text = stringResource(id = R.string.navigation_route_loading))
                }
            }
        }

        is NavigationUiState.Error -> {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = navigationUiState.message,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onDismissError) {
                        Text(text = stringResource(id = R.string.common_ok))
                    }
                }
            }
        }

        is NavigationUiState.Active -> {
            val distanceKm = navigationUiState.route.distanceMeters / 1000.0
            val durationMin = (navigationUiState.route.durationSeconds / 60.0).roundToInt()
            val context = LocalContext.current

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = navigationUiState.destination.name
                            ?: navigationUiState.destination.type.label(context),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            id = R.string.navigation_route_summary,
                            distanceKm,
                            durationMin
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(onClick = onStopNavigation) {
                        Text(text = stringResource(id = R.string.navigation_stop))
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.MapMenuCard(
    favoritesCount: Int,
    isDarkTheme: Boolean,
    currentLanguageFlag: String,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLanguage: () -> Unit,
    onToggleDarkMode: () -> Unit
) {
    Card(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box {
            IconButton(onClick = onExpand) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(id = R.string.menu_open),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            DropdownMenu(
                expanded = isExpanded,
                onDismissRequest = onDismiss
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                id = R.string.menu_favorites_count,
                                favoritesCount
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null
                        )
                    },
                    onClick = onOpenFavorites
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                id = R.string.menu_language_with_flag,
                                currentLanguageFlag
                            )
                        )
                    },
                    leadingIcon = {
                        Text(text = currentLanguageFlag)
                    },
                    onClick = onOpenLanguage
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isDarkTheme) {
                                stringResource(id = R.string.menu_disable_dark_mode)
                            } else {
                                stringResource(id = R.string.menu_enable_dark_mode)
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.DarkMode,
                            contentDescription = null
                        )
                    },
                    onClick = onToggleDarkMode
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(
    currentLanguageCode: String,
    onDismiss: () -> Unit,
    onSelectLanguage: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.language_sheet_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(12.dp))

            SUPPORTED_LANGUAGES.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { language ->
                        val isSelected = language.code == currentLanguageCode
                        if (isSelected) {
                            Button(
                                onClick = { onSelectLanguage(language.code) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = language.flag)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSelectLanguage(language.code) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = language.flag)
                            }
                        }
                    }
                    repeat(4 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.MyLocationFab(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp),
        containerColor = MaterialTheme.colorScheme.primary
    ) {
        Icon(
            imageVector = Icons.Default.MyLocation,
            contentDescription = stringResource(id = R.string.map_center_on_my_location),
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesSheet(
    spaces: List<BikeParkingSpace>,
    favoriteIds: List<String>,
    onDismiss: () -> Unit,
    onStartNavigation: NavigationHandler,
    onShowDetails: NavigationHandler,
    onToggleFavorite: (String) -> Unit
) {
    val context = LocalContext.current
    val favoriteSpaces = remember(spaces, favoriteIds) {
        spaces.filter { favoriteIds.contains(it.id) }
            .sortedBy { it.name ?: it.type.label(context) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.favorites_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (favoriteSpaces.isEmpty()) {
                    stringResource(id = R.string.favorites_empty_text)
                } else {
                    stringResource(id = R.string.favorites_hint_text)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (favoriteSpaces.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = stringResource(id = R.string.favorites_empty_card_text),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(favoriteSpaces, key = { it.id }) { space ->
                        FavoriteSpaceCard(
                            space = space,
                            onStartNavigation = onStartNavigation,
                            onShowDetails = onShowDetails,
                            onToggleFavorite = onToggleFavorite
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteSpaceCard(
    space: BikeParkingSpace,
    onStartNavigation: NavigationHandler,
    onShowDetails: NavigationHandler,
    onToggleFavorite: (String) -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = space.name ?: space.type.label(context),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                IconButton(onClick = { onToggleFavorite(space.id) }) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = stringResource(id = R.string.favorites_remove),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            space.address?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                space.capacity?.let { cap ->
                    FavoriteMetaChip(
                        label = stringResource(id = R.string.favorites_spaces_format, cap)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onStartNavigation(space) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.navigation_start),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = { onShowDetails(space) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(id = R.string.favorites_show_spot),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteMetaChip(label: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

private fun updateMarkers(
    map: MapView,
    spaces: List<BikeParkingSpace>,
    normalMarkerIcon: Drawable,
    favoriteMarkerIcon: Drawable,
    selectedMarkerIcon: Drawable,
    activeNavigationMarkerIcon: Drawable,
    mutedNormalMarkerIcon: Drawable,
    mutedFavoriteMarkerIcon: Drawable,
    mutedSelectedMarkerIcon: Drawable,
    locationMarkerIcon: Drawable,
    favoriteIds: List<String>,
    selectedSpaceId: String?,
    activeNavigationSpaceId: String?,
    userLocation: Pair<Double, Double>?,
    context: Context,
    myLocationTitle: String,
    snippetSpacesFormat: String,
    routePoints: List<RoutePoint>,
    onMarkerClick: (BikeParkingSpace) -> Unit
) {
    map.overlays.clear()

    if (routePoints.size > 1) {
        val routePolyline = Polyline().apply {
            outlinePaint.color = "#1976D2".toColorInt()
            outlinePaint.strokeWidth = 10f
            setPoints(routePoints.map { GeoPoint(it.latitude, it.longitude) })
        }
        map.overlays.add(routePolyline)
    }

    // Draw selected or active destination space last so it stays visible when markers overlap.
    val highlightedSpaceId = activeNavigationSpaceId ?: selectedSpaceId
    val (otherSpaces, highlightedSpaces) = spaces.partition { it.id != highlightedSpaceId }
    (otherSpaces + highlightedSpaces).forEach { space ->
        val isNavigationDestination = space.id == activeNavigationSpaceId
        val showMutedStyle = activeNavigationSpaceId != null && !isNavigationDestination
        val marker = Marker(map).apply {
            position = GeoPoint(space.latitude, space.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = if (space.id == activeNavigationSpaceId) {
                activeNavigationMarkerIcon.constantState?.newDrawable()?.mutate()
                    ?: activeNavigationMarkerIcon
            } else if (space.id == selectedSpaceId) {
                val selectedIcon = if (showMutedStyle) mutedSelectedMarkerIcon else selectedMarkerIcon
                selectedIcon.constantState?.newDrawable()?.mutate() ?: selectedIcon
            } else if (favoriteIds.contains(space.id)) {
                val favoriteIcon = if (showMutedStyle) mutedFavoriteMarkerIcon else favoriteMarkerIcon
                favoriteIcon.constantState?.newDrawable()?.mutate() ?: favoriteIcon
            } else {
                val defaultIcon = if (showMutedStyle) mutedNormalMarkerIcon else normalMarkerIcon
                defaultIcon.constantState?.newDrawable()?.mutate() ?: defaultIcon
            }
            title = space.name ?: space.type.label(context)
            snippet = buildSnippet(space, snippetSpacesFormat)
            setOnMarkerClickListener { _, _ ->
                onMarkerClick(space)
                true
            }
        }
        map.overlays.add(marker)
    }

    userLocation?.let { (latitude, longitude) ->
        val locationMarker = Marker(map).apply {
            position = GeoPoint(latitude, longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = locationMarkerIcon.constantState?.newDrawable()?.mutate() ?: locationMarkerIcon
            title = myLocationTitle
        }
        map.overlays.add(locationMarker)
    }

    map.invalidate()
}

private fun createBikeMarkerIcon(
    context: Context,
    zoomBucket: Int,
    pinColor: Int
): Drawable {
    val scale = when {
        zoomBucket <= 12 -> 0.42f
        zoomBucket == 13 -> 0.52f
        zoomBucket == 14 -> 0.62f
        zoomBucket == 15 -> 0.76f
        zoomBucket == 16 -> 0.90f
        zoomBucket == 17 -> 1.00f
        else -> 1.12f
    }

    val width = (120 * scale).roundToInt()
    val height = (148 * scale).roundToInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pinColor
        style = Paint.Style.FILL
    }

    val circleCenterX = width / 2f
    val circleCenterY = 52f * scale
    val circleRadius = 44f * scale
    canvas.drawCircle(circleCenterX, circleCenterY, circleRadius, pinPaint)

    val tipPath = Path().apply {
        moveTo(width / 2f, 140f * scale)
        lineTo(30f * scale, 78f * scale)
        lineTo(90f * scale, 78f * scale)
        close()
    }
    canvas.drawPath(tipPath, pinPaint)

    val emoji = "🚲"
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f * scale
        textAlign = Paint.Align.CENTER
    }
    val baselineY = circleCenterY -
        ((textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f) -
        (2f * scale)
    canvas.drawText(emoji, circleCenterX, baselineY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createLocationMarkerIcon(context: Context, isNavigationActive: Boolean): Drawable {
    val size = if (isNavigationActive) 46 else 34
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isNavigationActive) "#00695C".toColorInt() else "#2196F3".toColorInt()
        style = Paint.Style.FILL
    }
    val outerRadius = if (isNavigationActive) 21f else 15f
    canvas.drawCircle(size / 2f, size / 2f, outerRadius, outerPaint)

    if (isNavigationActive) {
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#A7FFEB".toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, 15f, ringPaint)
    }

    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, if (isNavigationActive) 8.5f else 7f, innerPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createMutedMarkerIcon(
    context: Context,
    source: Drawable,
    scale: Float = 0.84f,
    alpha: Int = 130
): Drawable {
    val sourceWidth = source.intrinsicWidth.coerceAtLeast(1)
    val sourceHeight = source.intrinsicHeight.coerceAtLeast(1)
    val sourceBitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
    val sourceCanvas = Canvas(sourceBitmap)
    val drawable = source.constantState?.newDrawable()?.mutate() ?: source.mutate()
    drawable.setBounds(0, 0, sourceWidth, sourceHeight)
    drawable.draw(sourceCanvas)

    val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    val targetBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val targetCanvas = Canvas(targetBitmap)
    val grayscaleAndBrightenMatrix = ColorMatrix().apply {
        setSaturation(0f)
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 34f,
                    0f, 1f, 0f, 0f, 34f,
                    0f, 0f, 1f, 0f, 34f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(grayscaleAndBrightenMatrix)
        this.alpha = alpha
    }

    targetCanvas.drawBitmap(
        sourceBitmap,
        Rect(0, 0, sourceWidth, sourceHeight),
        Rect(0, 0, targetWidth, targetHeight),
        paint
    )

    return BitmapDrawable(context.resources, targetBitmap)
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

private data class SupportedLanguage(
    val code: String,
    val flag: String
)

private val SUPPORTED_LANGUAGES = listOf(
    SupportedLanguage(code = "de", flag = "🇩🇪"),
    SupportedLanguage(code = "en", flag = "🇬🇧"),
    SupportedLanguage(code = "fr", flag = "🇫🇷"),
    SupportedLanguage(code = "it", flag = "🇮🇹"),
    SupportedLanguage(code = "pt", flag = "🇵🇹"),
    SupportedLanguage(code = "lb", flag = "🇱🇺"),
    SupportedLanguage(code = "nl", flag = "🇳🇱"),
    SupportedLanguage(code = "es", flag = "🇪🇸")
)

private fun languageFlagForCode(languageCode: String): String {
    return SUPPORTED_LANGUAGES.firstOrNull { it.code == languageCode }?.flag ?: "🏳️"
}

private fun buildSnippet(
    space: BikeParkingSpace,
    snippetSpacesFormat: String
): String = buildString {
    space.address?.let { append(it) }
    space.capacity?.let { cap ->
        if (isNotEmpty()) append(" · ")
        append(String.format(snippetSpacesFormat, cap))
    }
}
