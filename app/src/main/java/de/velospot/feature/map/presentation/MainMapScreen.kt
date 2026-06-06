package de.velospot.feature.map.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.core.map.NavigationHandler
import de.velospot.core.map.externalNavigationHandler
import de.velospot.domain.model.BikeParkingSpace
import kotlin.math.roundToInt
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

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

    val mapView = rememberMapViewWithLifecycle()
    var zoomBucket by remember { mutableIntStateOf(DEFAULT_ZOOM.roundToInt()) }
    var isMenuExpanded by remember { mutableStateOf(false) }
    var isFavoritesSheetVisible by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.any { it.value }) {
            viewModel.onLocationPermissionGranted()
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
        createBikeMarkerIcon(context, zoomBucket, pinColor = Color.parseColor("#0A2A66"))
    }
    val favoriteMarkerIcon = remember(context, zoomBucket) {
        createBikeMarkerIcon(context, zoomBucket, pinColor = Color.parseColor("#D32F2F"))
    }
    val locationMarkerIcon = remember(context) {
        createLocationMarkerIcon(context)
    }
    val navigationHandler = remember(context) { externalNavigationHandler(context) }

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
                        locationMarkerIcon = locationMarkerIcon,
                        favoriteIds = favorites,
                        userLocation = userLocation,
                        onMarkerClick = viewModel::selectSpace
                    )
                }
            }
        )

        if (uiState is MapUiState.Loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (uiState is MapUiState.Error) {
            val message = (uiState as MapUiState.Error).message
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
                    text = message,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

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
                IconButton(onClick = { isMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open menu",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Favorites (${favorites.size})") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            isFavoritesSheetVisible = true
                            isMenuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isDarkTheme) "Disable dark mode" else "Enable dark mode") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.DarkMode,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            onDarkThemeToggle()
                            isMenuExpanded = false
                        }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                if (hasLocationPermission(context)) {
                    viewModel.onLocationPermissionGranted()
                    viewModel.centerMapOnUserLocation(mapView)
                } else {
                    permissionLauncher.launch(locationPermissions())
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = "Center map on my location",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }

    if (isFavoritesSheetVisible) {
        FavoritesSheet(
            spaces = (uiState as? MapUiState.Success)?.spaces.orEmpty(),
            favoriteIds = favorites,
            onDismiss = { isFavoritesSheetVisible = false },
            onNavigate = navigationHandler,
            onToggleFavorite = viewModel::toggleFavorite
        )
    }

    selectedSpace?.let { space ->
        SelectedSpaceSheet(
            space = space,
            onDismiss = { viewModel.selectSpace(null) },
            onNavigate = navigationHandler,
            isFavorite = favorites.contains(space.id),
            onToggleFavorite = viewModel::toggleFavorite
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesSheet(
    spaces: List<BikeParkingSpace>,
    favoriteIds: List<String>,
    onDismiss: () -> Unit,
    onNavigate: NavigationHandler,
    onToggleFavorite: (String) -> Unit
) {
    val favoriteSpaces = remember(spaces, favoriteIds) {
        spaces.filter { favoriteIds.contains(it.id) }
            .sortedBy { it.name ?: it.type.label() }
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
                text = "Favorites",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (favoriteSpaces.isEmpty()) {
                    "You have not saved any favorites yet."
                } else {
                    "Start navigation directly from your saved parking spots."
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
                        text = "Tap the heart icon in a parking detail sheet to add a favorite.",
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
                            onNavigate = onNavigate,
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
    onNavigate: NavigationHandler,
    onToggleFavorite: (String) -> Unit
) {
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
                        text = space.name ?: space.type.label(),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                IconButton(onClick = { onToggleFavorite(space.id) }) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Remove from favorites",
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
                    FavoriteMetaChip(label = "$cap spaces")
                }
                if (space.isCovered == true) {
                    FavoriteMetaChip(label = "Covered")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { onNavigate(space) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start navigation")
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
    locationMarkerIcon: Drawable,
    favoriteIds: List<String>,
    userLocation: Pair<Double, Double>?,
    onMarkerClick: (BikeParkingSpace) -> Unit
) {
    map.overlays.clear()

    spaces.forEach { space ->
        val marker = Marker(map).apply {
            position = GeoPoint(space.latitude, space.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = if (favoriteIds.contains(space.id)) {
                favoriteMarkerIcon.constantState?.newDrawable()?.mutate() ?: favoriteMarkerIcon
            } else {
                normalMarkerIcon.constantState?.newDrawable()?.mutate() ?: normalMarkerIcon
            }
            title = space.name ?: space.type.label()
            snippet = buildSnippet(space)
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
            title = "My location"
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

private fun createLocationMarkerIcon(context: Context): Drawable {
    val size = 34
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, 15f, outerPaint)

    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, 7f, innerPaint)

    return BitmapDrawable(context.resources, bitmap)
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

private fun buildSnippet(space: BikeParkingSpace): String = buildString {
    space.address?.let { append(it) }
    space.capacity?.let { cap ->
        if (isNotEmpty()) append(" · ")
        append("$cap Stellplätze")
    }
    if (space.isCovered == true) {
        if (isNotEmpty()) append(" · ")
        append("überdacht")
    }
}
