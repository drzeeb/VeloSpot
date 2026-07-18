package de.velospot.feature.map.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
import de.velospot.core.format.formatRideSpeed
import de.velospot.core.tracking.RideTrackingUiState
import de.velospot.feature.map.presentation.markers.MarkerDisplayConfig
import de.velospot.feature.map.presentation.markers.MarkerIconSet
import de.velospot.feature.map.presentation.markers.MarkerRenderLabels
import de.velospot.feature.map.presentation.markers.MarkerRenderState
import de.velospot.feature.map.presentation.markers.MIN_ZOOM_PARKING_VISIBLE
import de.velospot.feature.map.presentation.markers.ClusterRenderStyle
import de.velospot.feature.map.presentation.markers.RouteRenderData
import de.velospot.feature.map.presentation.markers.createBikeMarkerIcon
import de.velospot.feature.map.presentation.markers.createLocationMarkerIcon
import de.velospot.feature.map.presentation.markers.createMutedMarkerIcon
import de.velospot.feature.map.presentation.markers.defaultMarkerStyleConfig
import de.velospot.feature.map.presentation.markers.updateMarkers
import de.velospot.feature.map.presentation.markers.updateLocationDot
import de.velospot.feature.map.presentation.markers.updateHeatmapLayer
import de.velospot.feature.map.presentation.markers.updateMaxSpeedMarker
import de.velospot.feature.map.presentation.markers.updateTrackSpeedLayer
import de.velospot.feature.map.presentation.markers.createSpeedBubbleIcon
import de.velospot.feature.map.presentation.markers.updateTracksHistoryLayer
import de.velospot.feature.map.presentation.markers.updateWaypointsLayer
import de.velospot.feature.map.presentation.markers.createWaypointPinIcon
import de.velospot.core.map.RideHeatmap
import de.velospot.core.map.RideMaxSpeedPoint
import de.velospot.core.map.RideTrackLines
import de.velospot.feature.map.presentation.sheets.MapBottomSheets
import de.velospot.feature.map.presentation.sheets.RideDetailSheet
import de.velospot.feature.map.presentation.sheets.languageFlagForCode
import de.velospot.feature.map.presentation.sheets.resolveCurrentLanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap


/** Duration (ms) of the gentle camera glide that follows the live position while
 *  recording a ride without navigation. */
private const val CAMERA_FOLLOW_DURATION_MS = 600

/**
 * Debounce (ms) for redrawing the **live recording track**. Each redraw rebuilds
 * the whole polyline GeoJSON, so when several GPS fixes arrive in a burst (e.g.
 * batched delivery after Doze, or the debug simulator) we coalesce them into a
 * single redraw instead of one per fix. Small enough to feel immediate.
 */
private const val LIVE_TRACK_REDRAW_DEBOUNCE_MS = 120L

/**
 * How long the splash plays its **reveal animation** after the map becomes ready,
 * before fading away. By then the main thread is free (map loaded), so this stretch
 * of animation is guaranteed smooth — long enough to read as a deliberate "GPS lock"
 * flourish rather than a flash.
 */
private const val SPLASH_REVEAL_MS = 1150L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMapScreen(
    isDarkTheme: Boolean = false,
    onDarkThemeToggle: () -> Unit = {},
    onOpenRideAnalysis: (String) -> Unit = {},
    viewModel: MapViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState              by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedSpace        by viewModel.selectedSpace.collectAsStateWithLifecycle()
    val favorites            by viewModel.favorites.collectAsStateWithLifecycle()
    val userLocation         by viewModel.userLocation.collectAsStateWithLifecycle()
    val mapCameraTarget      by viewModel.mapCameraTarget.collectAsStateWithLifecycle()
    val navigationUiState    by viewModel.navigationUiState.collectAsStateWithLifecycle()
    val navigationProgress   by viewModel.navigationProgress.collectAsStateWithLifecycle()
    val offlineRoutingUiState by viewModel.offlineRoutingUiState.collectAsStateWithLifecycle()
    val searchQuery          by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults        by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching          by viewModel.isSearching.collectAsStateWithLifecycle()
    val selectedSearchPin    by viewModel.selectedSearchPin.collectAsStateWithLifecycle()
    val customMapPin         by viewModel.customMapPin.collectAsStateWithLifecycle()
    val savedPlaces          by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val parkedBike           by viewModel.parkedBike.collectAsStateWithLifecycle()
    val layerVisibility      by viewModel.layerVisibility.collectAsStateWithLifecycle()
    val is3DNavigation       by viewModel.is3DNavigation.collectAsStateWithLifecycle()
    val voiceGuidanceEnabled by viewModel.voiceGuidanceEnabled.collectAsStateWithLifecycle()
    val keepScreenOnEnabled  by viewModel.keepScreenOnEnabled.collectAsStateWithLifecycle()
    val isSimulatingRoute    by viewModel.isSimulatingRoute.collectAsStateWithLifecycle()
    val rideTrackingState    by viewModel.rideTrackingState.collectAsStateWithLifecycle()
    val rideTrackPoints      by viewModel.rideTrackPoints.collectAsStateWithLifecycle()
    val recordedRideTracks   by viewModel.recordedRideTracks.collectAsStateWithLifecycle()
    val selectedRide         by viewModel.selectedRide.collectAsStateWithLifecycle()
    val rideViewOptions      by viewModel.rideViewOptions.collectAsStateWithLifecycle()
    val rideNamePrompt       by viewModel.rideNamePrompt.collectAsStateWithLifecycle()
    val isFollowingLocation  by viewModel.isFollowingLocation.collectAsStateWithLifecycle()
    val isPlanningRoute       by viewModel.isPlanningRoute.collectAsStateWithLifecycle()
    val planningWaypoints     by viewModel.planningWaypoints.collectAsStateWithLifecycle()
    val planningPreviewRoute  by viewModel.planningPreviewRoute.collectAsStateWithLifecycle()
    val isComputingRoutePreview by viewModel.isComputingRoutePreview.collectAsStateWithLifecycle()
    val previewedRoute        by viewModel.previewedRoute.collectAsStateWithLifecycle()
    val previewedRouteSummary by viewModel.previewedRouteSummary.collectAsStateWithLifecycle()

    val activeNavigation = navigationUiState as? NavigationUiState.Active


    // Whether a follow-capable session is running (active navigation OR a live ride
    // recording). Drives the re-centre button + the recording follow camera.
    val isRecordingRide  = rideTrackingState is RideTrackingUiState.Recording
    val isFollowSession  = activeNavigation != null || isRecordingRide

    // Keep the screen awake during a follow session — active navigation OR a live
    // ride recording — so the display does not dim/lock mid-ride. Gated by the
    // user's "keep screen on" preference (default on), and the flag is cleared
    // automatically when the session ends or the screen leaves composition.
    val isNavigating = activeNavigation != null
    val keepScreenAwake = keepScreenOnEnabled && (isNavigating || isRecordingRide)
    val currentView = LocalView.current
    DisposableEffect(currentView, keepScreenAwake) {
        currentView.keepScreenOn = keepScreenAwake
        onDispose { currentView.keepScreenOn = false }
    }

    val viewportLoadError by viewModel.viewportLoadError.collectAsStateWithLifecycle()
    val viewportErrorText = stringResource(R.string.error_loading_parking)
    LaunchedEffect(viewportLoadError) {
        if (viewportLoadError != null) {
            Toast.makeText(context, viewportErrorText, Toast.LENGTH_SHORT).show()
            viewModel.clearViewportLoadError()
        }
    }

    // One-shot user messages (e.g. "bike location saved") surfaced as a Toast.
    val userMessageRes by viewModel.userMessageRes.collectAsStateWithLifecycle()
    // Resolve the message via stringResource (composition scope) rather than
    // context.getString — the latter trips the LocalContextGetResourceValueCall lint.
    val userMessageText = userMessageRes?.let { stringResource(it) }
    LaunchedEffect(userMessageText) {
        userMessageText?.let { text ->
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            viewModel.consumeUserMessage()
        }
    }

    // Mount the MapLibre [MapView] only after the first splash frame has painted, so
    // the branded splash is on-screen before the (heavy, main-thread) native renderer
    // init runs. While that init blocks the thread the splash shows a STATIC logo — so
    // there is nothing animating to stutter. The cool animation plays later, once the
    // map is ready and the main thread is free again (see the splash dismissal below).
    var mapMounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }            // let the first (static) splash frame paint
        mapMounted = true
    }

    val mapView       = rememberMapViewWithLifecycle(enabled = mapMounted)
    val screenUiState = rememberMapScreenUiState()
    val markerStyleConfig = remember(isDarkTheme) { defaultMarkerStyleConfig(isDarkTheme) }

    // Drives the live 3D navigation camera + heading arrow. Owned by the screen,
    // bound to the MapLibreMap once a style is ready (see effects below).
    val navigationManager = remember(context) { NavigationManager(context) }

    // Speaks turn-by-turn instructions (TTS) when voice guidance is enabled. The
    // engine is released when the screen leaves composition.
    val voiceGuide = remember(context) { NavigationVoiceGuide(context) }
    DisposableEffect(voiceGuide) { onDispose { voiceGuide.shutdown() } }
    LaunchedEffect(voiceGuidanceEnabled) { voiceGuide.setEnabled(voiceGuidanceEnabled) }
    // Re-arm the announcement state when a navigation session starts; silence it
    // when navigation ends.
    LaunchedEffect(isNavigating) {
        if (isNavigating) voiceGuide.reset() else voiceGuide.stop()
    }
    // Feed every progress snapshot to the voice guide; it decides what (if anything)
    // to speak based on the upcoming turn / arrival.
    LaunchedEffect(navigationProgress) {
        val progress = navigationProgress
        if (isNavigating && progress != null) voiceGuide.onProgress(progress)
    }

    // The MapLibreMap is provided asynchronously via getMapAsync.
    // Using mutableStateOf triggers recomposition so LaunchedEffects below fire.
    var maplibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var zoomBucket  by remember { mutableIntStateOf(DEFAULT_ZOOM.roundToInt()) }

    // Incremented every time a (new) style finishes loading. Re-loading the style –
    // e.g. when toggling dark mode – wipes all custom sources/layers/images, so we
    // use this as a key to re-run the marker rendering effect and rebuild them.
    var styleVersion by remember { mutableIntStateOf(0) }

    // ── Animated launch splash ────────────────────────────────────────────────
    // Cover the map load with the branded logo. While the map loads (main thread busy
    // with the native renderer init) the splash shows a STATIC logo — nothing animates,
    // so nothing can stutter. Once the map is ready (styleVersion > 0) the main thread
    // is free again: the splash then plays its cool "GPS-lock" reveal animation for a
    // fixed beat and fades/scales away to the live map.
    val mapReady = styleVersion > 0
    // Saveable so the splash doesn't replay when returning from another screen
    // (e.g. the ride analysis): the map destination's state survives on the back stack.
    var showSplash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(mapReady) {
        if (mapReady) {
            delay(SPLASH_REVEAL_MS)   // let the smooth reveal animation play out
            showSplash = false
        }
    }


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

    // Notification permission (Android 13+) for the background-recording notification.
    // The recording itself runs regardless; without the grant the notification simply
    // isn't shown, so we start the ride either way after asking.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* result ignored: recording proceeds with or without the notification */ }
    val startRideRecording: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.startRideTracking()
    }

    // ── Battery: stop GPS in the background, re-arm it on return ───────────────
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_STOP  -> viewModel.onAppBackgrounded()
                else                     -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
    // NOTE: deliberately NOT keyed on `userLocation`. The live-location dot is moved
    // by its own lightweight effect (`updateLocationDot` below) so a fresh GPS fix
    // doesn't re-serialise the whole parking / favourites / saved-places GeoJSON on
    // every fix. This pass therefore always suppresses the dot.
    LaunchedEffect(
        maplibreMap, uiState, favorites, selectedSpace,
        activeNavigation, zoomBucket,
        normalMarkerIcon, favoriteMarkerIcon, selectedMarkerIcon,
        selectedSearchPin, customMapPin, styleVersion, savedPlaces, layerVisibility, parkedBike,
        showSplash, planningPreviewRoute, previewedRoute
    ) {
        val map = maplibreMap ?: return@LaunchedEffect
        // While the animated launch splash covers the map, defer this heavy pass
        // (it serialises the whole parking / favourites / saved-places GeoJSON on the
        // main thread) so it doesn't steal frames from the splash animation. It re-runs
        // the moment the splash is dismissed (showSplash is a key).
        if (showSplash) return@LaunchedEffect
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
                    // The location dot is drawn by the dedicated effect below.
                    userLocation             = null
                ),
                display   = MarkerDisplayConfig(
                    context = context,
                    labels  = MarkerRenderLabels(myLocationTitle, snippetSpacesFormat)
                ),
                    route     = RouteRenderData(
                        color  = markerStyleConfig.routeColor,
                        // Show the active navigation route, or — when planning /
                        // previewing a saved route on the idle map — its preview line.
                        points = activeNavigation?.route?.points
                            ?: planningPreviewRoute?.points
                            ?: previewedRoute?.geometry.orEmpty()
                    ),
                    clusterStyle = ClusterRenderStyle(
                        circleColor = markerStyleConfig.normalPinColor,
                        textColor   = android.graphics.Color.WHITE
                    ),
                    searchPin    = selectedSearchPin,
                    customMapPin = customMapPin,
                    savedPlaces  = savedPlaces,
                    parkedBike   = parkedBike,
                    layerVisibility = layerVisibility,
                    // While navigating, NavigationManager owns the location puck
                    // (animated heading arrow), so the renderer must not draw it.
                    suppressLocationDot = true,
                    // …and it owns the route polyline (travelled/remaining split).
                    suppressRoute = activeNavigation != null,
                    // Minimal nav mode: hide all non-trip markers while navigating
                    // so only the route, destination and live position remain.
                    minimalNavMode = activeNavigation != null
                )
        }
    }

    // ── Live-location dot (its own lightweight effect) ────────────────────────
    // Moved to AFTER the NavigationManager effects below: navigation start/stop both
    // write SOURCE_LOCATION (start renders the puck, stop clears it), so the dot must
    // get the final say when idle. See the effect after `DisposableEffect(navigationManager)`.

    // ── Recorded-ride heatmap overlay ─────────────────────────────────────────
    // Aggregate all recorded tracks into weighted cells (off the main thread) and
    // (re)draw the heatmap layer whenever the rides change, the layer is toggled,
    // or the style reloads. Hidden (and skipped) while the layer is off.
    LaunchedEffect(maplibreMap, styleVersion, recordedRideTracks, layerVisibility.showHeatmap) {
        val style = maplibreMap?.style ?: return@LaunchedEffect
        if (!layerVisibility.showHeatmap) {
            updateHeatmapLayer(style, emptyList(), visible = false)
            return@LaunchedEffect
        }
        val cells = withContext(Dispatchers.Default) {
            RideHeatmap.build(recordedRideTracks.filterNot { it.isMock })
                .map { Triple(it.latitude, it.longitude, it.intensity) }
        }
        updateHeatmapLayer(style, cells, visible = true)
    }

    // ── Recorded-ride "ridden tracks" overlay ─────────────────────────────────
    // Draw every recorded ride as its own thin, translucent line (simplified off
    // the main thread). Overlapping passes accumulate, so often-ridden streets
    // read stronger. (Re)built whenever the rides change, the layer is toggled or
    // the style reloads; hidden (and skipped) while the layer is off.
    LaunchedEffect(maplibreMap, styleVersion, recordedRideTracks, layerVisibility.showTracks) {
        val style = maplibreMap?.style ?: return@LaunchedEffect
        if (!layerVisibility.showTracks) {
            updateTracksHistoryLayer(style, emptyList(), markerStyleConfig.routeColor, visible = false)
            return@LaunchedEffect
        }
        val polylines = withContext(Dispatchers.Default) {
            RideTrackLines.build(recordedRideTracks.filterNot { it.isMock })
        }
        updateTracksHistoryLayer(style, polylines, markerStyleConfig.routeColor, visible = true)
    }

    // ── Route-planning waypoint pins ──────────────────────────────────────────
    // Draw a numbered pin for each chosen stop while planning (the last one amber
    // so the current end is obvious). Cleared when planning ends or between style
    // reloads. Runs off the marker pass so dropping a stop updates instantly.
    LaunchedEffect(maplibreMap, styleVersion, isPlanningRoute, planningWaypoints) {
        val style = maplibreMap?.style ?: return@LaunchedEffect
        if (!isPlanningRoute || planningWaypoints.isEmpty()) {
            updateWaypointsLayer(style, emptyList(), emptyList())
            return@LaunchedEffect
        }
        val lastIndex = planningWaypoints.lastIndex
        val icons = planningWaypoints.mapIndexed { i, _ ->
            createWaypointPinIcon(number = i + 1, isLast = i == lastIndex)
        }
        val points = planningWaypoints.map { it.latitude to it.longitude }
        updateWaypointsLayer(style, points, icons)
    }

    // ── 3D navigation: bind manager + start/stop with the active route ────────
    // Re-attach after every (re)loaded style so the arrow image / 3D building
    // layer are re-registered (style reload wipes custom sources/layers/images).
    LaunchedEffect(maplibreMap, styleVersion) {
        val map = maplibreMap ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        navigationManager.attach(map, style)
    }

    // Start navigation when a route becomes active, stop when it ends.
    LaunchedEffect(activeNavigation?.route, maplibreMap, styleVersion) {
        val route = activeNavigation?.route
        if (route != null && route.points.size > 1) {
            navigationManager.start(
                routePoints          = route.points,
                totalDistanceMeters  = route.distanceMeters,
                totalDurationSeconds = route.durationSeconds,
                routeColor           = markerStyleConfig.routeColor
            )
            // Immediately feed the last known fix so the camera tilts straight in.
            userLocation?.let(navigationManager::onLocationUpdate)
        } else {
            navigationManager.stop()
        }
    }

    // Feed every GPS fix into the manager while navigating.
    LaunchedEffect(userLocation, activeNavigation) {
        if (activeNavigation != null) {
            userLocation?.let(navigationManager::onLocationUpdate)
        }
    }

    // Apply the user's 2D/3D preference live (also re-applied after style reloads).
    LaunchedEffect(is3DNavigation, maplibreMap, styleVersion) {
        navigationManager.setMode(is3DNavigation)
    }

    DisposableEffect(navigationManager) {
        // Live route progress (distance + ETA) and the off-route reroute trigger
        // are forwarded to the ViewModel.
        navigationManager.onProgress = viewModel::updateNavigationProgress
        navigationManager.onOffRoute = viewModel::onUserWentOffRoute
        onDispose {
            navigationManager.onProgress = null
            navigationManager.onOffRoute = null
            navigationManager.stop()
        }
    }

    // ── Live-location dot (its own lightweight effect) ────────────────────────
    // Only this small source is rewritten on a fresh GPS fix, instead of the whole
    // marker GeoJSON (the dominant per-fix cost while browsing / recording).
    // Declared AFTER the NavigationManager effects on purpose: navigation start
    // renders the puck into SOURCE_LOCATION and navigation stop CLEARS it — so the
    // idle dot must run last to get the final say (otherwise `stop()` would wipe a
    // dot drawn earlier in the same recomposition, e.g. at startup). Suppressed
    // while navigating (the manager owns the animated puck); re-runs after a style
    // reload to re-register the image / layer.
    LaunchedEffect(maplibreMap, styleVersion, userLocation, activeNavigation, locationMarkerIcon) {
        val map = maplibreMap ?: return@LaunchedEffect
        updateLocationDot(
            map          = map,
            location     = userLocation,
            locationIcon = locationMarkerIcon,
            suppress     = activeNavigation != null
        )
    }

    // ── Camera animation ──────────────────────────────────────────────────────
    LaunchedEffect(mapCameraTarget, maplibreMap) {
        val target = mapCameraTarget ?: return@LaunchedEffect
        val map    = maplibreMap    ?: return@LaunchedEffect
        // During active navigation the NavigationManager owns the camera; ignore
        // one-shot targets (startup centering, FAB) so they don't fight the
        // per-frame 3D camera.
        if (activeNavigation == null) {
            animateMapCameraToTarget(map = map, cameraTarget = target)
        }
        viewModel.onMapCameraTargetHandled()
    }

    // ── Route preview: fit the camera to the whole saved route ────────────────
    // When a saved route is opened for inspection, frame its entire geometry so the
    // rider can see the whole loop/line before riding. Skipped while navigating
    // (the NavigationManager owns the camera then).
    LaunchedEffect(previewedRoute, maplibreMap, styleVersion) {
        val map = maplibreMap ?: return@LaunchedEffect
        val geometry = previewedRoute?.geometry ?: return@LaunchedEffect
        if (geometry.size < 2 || activeNavigation != null) return@LaunchedEffect
        val lats = geometry.map { it.latitude }
        val lons = geometry.map { it.longitude }
        // A small epsilon avoids a degenerate (zero-area) bounds that LatLngBounds rejects.
        val eps = 1e-4
        val bounds = org.maplibre.android.geometry.LatLngBounds.from(
            lats.max() + eps, lons.max() + eps, lats.min() - eps, lons.min() - eps
        )
        runCatching {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96), 600)
        }
    }

    // ── Follow camera: navigation ────────────────────────────────────────────
    // Bridge the ViewModel's follow lock into the NavigationManager, which owns the
    // per-frame camera while navigating. When unlocked the rider can pan freely; the
    // heading arrow keeps tracking. Re-locking glides the camera back.
    LaunchedEffect(isFollowingLocation, activeNavigation) {
        if (activeNavigation != null) navigationManager.setFollowing(isFollowingLocation)
    }

    // ── Follow camera: ride recording (no navigation) ────────────────────────
    // Navigation has its own follow camera (above); here we keep the map centred on
    // the live position while a ride is being recorded without navigation. Panning
    // the map clears the follow lock (handled in the ViewModel) so this stops
    // chasing until the user taps the re-centre button.
    LaunchedEffect(userLocation, isFollowingLocation, activeNavigation, isRecordingRide) {
        val map = maplibreMap ?: return@LaunchedEffect
        if (activeNavigation == null && isRecordingRide && isFollowingLocation) {
            userLocation?.let { loc ->
                map.animateCamera(
                    CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude)),
                    CAMERA_FOLLOW_DURATION_MS
                )
            }
        }
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
        val mv = mapView ?: return@DisposableEffect onDispose { }
        mv.initVeloSpotMap(
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

    // ── Recorded-ride track polyline (live recording or a reopened ride) ──────
    // When inspecting a past ride with "colour by speed" on, the flat line is
    // replaced by a green→red speed-coloured line; otherwise the plain line shows.
    //
    // While navigating, the raw GPS track is deliberately NOT drawn: the
    // NavigationManager already renders the planned route with a travelled/remaining
    // split (and reroutes if the rider leaves it), so overlaying the jagged raw-GPS
    // recording line on top looks messy and redundant. The real GPS fixes are still
    // recorded for the ride analysis — only their on-map polyline is suppressed here.
    LaunchedEffect(maplibreMap, styleVersion, rideTrackPoints, selectedRide, activeNavigation != null, rideViewOptions.colorTrackBySpeed) {
        val style = maplibreMap?.style ?: return@LaunchedEffect
        // While recording, coalesce a burst of fixes into one redraw (the effect is
        // cancelled & restarted on each new emission, so only the last one redraws).
        if (rideTrackingState is RideTrackingUiState.Recording) delay(LIVE_TRACK_REDRAW_DEBOUNCE_MS)
        val ride = selectedRide
        // Mock rides carry no speed samples (max speed 0), so the speed-coloured
        // line would render invisible — always draw their track as the plain line.
        val colorBySpeed = ride != null && rideViewOptions.colorTrackBySpeed && !ride.isMock
        if (colorBySpeed) {
            val segments = withContext(Dispatchers.Default) {
                de.velospot.core.map.RideSpeedSegments.build(ride.points)
            }
            de.velospot.feature.map.presentation.markers.updateTrackLayer(
                style = style, points = emptyList(), colorInt = markerStyleConfig.routeColor
            )
            updateTrackSpeedLayer(style, segments, ride.maxSpeedMps, visible = true)
        } else {
            updateTrackSpeedLayer(style, emptyList(), 0.0, visible = false)
            // Suppress the live recording polyline while the navigation route owns
            // the map; still draw the track when just recording or inspecting a ride.
            val points = if (activeNavigation != null && ride == null) {
                emptyList()
            } else {
                rideTrackPoints.map { it.latitude to it.longitude }
            }
            de.velospot.feature.map.presentation.markers.updateTrackLayer(
                style = style,
                points = points,
                colorInt = markerStyleConfig.routeColor
            )
        }
    }

    // ── Max-speed bubble for a reopened ride ──────────────────────────────────
    // When the rider inspects a past ride via the detail sheet, mark the spot it
    // reached its top speed with a speech bubble showing that speed. Gated on the
    // persisted "show max speed bubble" option and cleared when off or no ride is
    // selected. Re-run after style reloads (the layer/image are wiped).
    LaunchedEffect(maplibreMap, styleVersion, selectedRide, rideViewOptions.showMaxSpeedBubble) {
        val style = maplibreMap?.style ?: return@LaunchedEffect
        val ride = selectedRide
        val peak = ride?.let { RideMaxSpeedPoint.find(it) }
        if (ride == null || peak == null || !rideViewOptions.showMaxSpeedBubble) {
            updateMaxSpeedMarker(style, null, null)
            return@LaunchedEffect
        }
        val label = formatRideSpeed(ride.maxSpeedMps)
        val icon = withContext(Dispatchers.Default) { createSpeedBubbleIcon(label) }
        updateMaxSpeedMarker(style, peak.latitude to peak.longitude, icon)
    }


    // ── UI layout ─────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        mapView?.let { mv ->
            AndroidView(
                factory  = { mv },
                modifier = Modifier.fillMaxSize()
                // No update block needed – all updates go through LaunchedEffect above.
            )
        }

        MapStatusOverlay(uiState = uiState)
        MapNavigationOverlay(
            navigationUiState = navigationUiState,
            progress          = navigationProgress,
            onStopNavigation  = viewModel::stopInAppNavigation,
            onDismissError    = viewModel::clearNavigationError,
            onCancel          = viewModel::cancelRouteCalculation
        )

        // Turn-by-turn banner (top) — only during active navigation.
        if (activeNavigation != null) {
            MapTurnBanner(progress = navigationProgress)
        }


        // ── Search bar + Menu button – vertically centred in one Row ─────────
        val menuState = MapMenuCardState(
            favoritesCount     = favorites.size + savedPlaces.size,
            isDarkTheme        = isDarkTheme,
            currentLanguageFlag = currentLanguageFlag,
            isExpanded         = screenUiState.isSettingsSheetVisible,
            offlineRoutingUiState = offlineRoutingUiState,
            isBikeParked       = parkedBike != null,
            voiceGuidanceEnabled = voiceGuidanceEnabled,
            keepScreenOnEnabled = keepScreenOnEnabled,
            // Debug-only GPS route simulator: always visible in debug
            // builds, enabled once a route is available to drive along.
            showSimulator      = de.velospot.BuildConfig.DEBUG,
            simulatorEnabled   = activeNavigation != null,
            isSimulating       = isSimulatingRoute
        )
        val menuActions = MapMenuCardActions(
            onExpand              = screenUiState::expandMenu,
            onDismiss             = screenUiState::dismissMenu,
            onOpenFavorites       = screenUiState::openFavorites,
            onOpenLanguage        = screenUiState::openLanguage,
            onToggleDarkMode      = { onDarkThemeToggle(); screenUiState.dismissMenu() },
            onOpenLayers          = screenUiState::openLayers,
            onOpenNavigationView  = screenUiState::openNavigationView,
            onActivateOfflineRouting = viewModel::requestOfflineRoutingSetup,
            onOpenProfileSheet    = viewModel::openProfileSheet,
            onParkBikeHere        = viewModel::parkBikeAtCurrentLocation,
            onShowParkedBike      = viewModel::showParkedBike,
            onToggleVoiceGuidance = { viewModel.setVoiceGuidanceEnabled(!voiceGuidanceEnabled) },
            onToggleKeepScreenOn  = { viewModel.setKeepScreenOnEnabled(!keepScreenOnEnabled) },
            onToggleSimulation    = viewModel::toggleRouteSimulation,
            onOpenAbout           = screenUiState::openAbout,
            onOpenRides           = screenUiState::openRides,
            onOpenRoundTrip       = screenUiState::openRoundTrip,
            onStartRoutePlanning  = viewModel::startRoutePlanning,
            onOpenPlannedRoutes   = screenUiState::openPlannedRoutes,
            onOpenDisplaySettings = screenUiState::openDisplaySettings,
            onOpenNavRouting      = screenUiState::openNavRouting,
            onOpenBikeGarage      = screenUiState::openBikeGarage
        )
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
            MapMenuCard(state = menuState, actions = menuActions)
        }

        // Unified Settings sheet (replaces the old top-bar dropdown menu).
        if (screenUiState.isSettingsSheetVisible) {
            de.velospot.feature.map.presentation.sheets.SettingsSheet(
                state = menuState,
                actions = menuActions
            )
        }
        // Settings sub-sheets (grouped so the main Settings list stays short).
        if (screenUiState.isDisplaySettingsSheetVisible) {
            de.velospot.feature.map.presentation.sheets.DisplaySettingsSheet(
                state = menuState,
                actions = menuActions,
                onDismiss = screenUiState::closeDisplaySettings
            )
        }
        if (screenUiState.isNavRoutingSheetVisible) {
            de.velospot.feature.map.presentation.sheets.NavigationRoutingSheet(
                state = menuState,
                actions = menuActions,
                onDismiss = screenUiState::closeNavRouting
            )
        }
        // Bike garage: per-bike profiles with their own statistics + the quick
        // pre-ride bike switch. Its own Hilt ViewModel is scoped inside the sheet.
        if (screenUiState.isBikeGarageSheetVisible) {
            de.velospot.feature.map.presentation.sheets.BikeGarageSheet(
                onDismiss = screenUiState::closeBikeGarage
            )
        }

        // ── Map actions speed-dial (bottom-left FAB) ─────────────────────────
        // The frequent things a rider *does* — plan a route, round trip, park the
        // bike, rides, saved routes, favourites — fan out from a single FAB, so the
        // Settings sheet only holds actual settings. Hidden during active
        // navigation, where the bottom area belongs to the navigation card.
        if (activeNavigation == null) {
            val speedDialActions = listOf(
                SpeedDialAction(
                    label = stringResource(R.string.route_plan_menu),
                    icon = Icons.Default.Route,
                    onClick = viewModel::startRoutePlanning
                ),
                SpeedDialAction(
                    label = stringResource(R.string.round_trip_menu),
                    icon = Icons.Default.Loop,
                    onClick = screenUiState::openRoundTrip
                ),
                SpeedDialAction(
                    label = stringResource(R.string.route_my_routes_menu),
                    icon = Icons.AutoMirrored.Filled.AltRoute,
                    onClick = screenUiState::openPlannedRoutes
                ),
                SpeedDialAction(
                    label = stringResource(R.string.menu_my_rides),
                    icon = Icons.Default.Timeline,
                    onClick = screenUiState::openRides
                ),
                SpeedDialAction(
                    label = stringResource(
                        if (parkedBike != null) R.string.menu_show_parked_bike
                        else R.string.menu_park_bike_here
                    ),
                    icon = Icons.AutoMirrored.Filled.DirectionsBike,
                    onClick = {
                        if (parkedBike != null) viewModel.showParkedBike()
                        else viewModel.parkBikeAtCurrentLocation()
                    }
                ),
                SpeedDialAction(
                    label = stringResource(R.string.favorites_title),
                    icon = Icons.Default.Favorite,
                    onClick = screenUiState::openFavorites
                )
            )
            MapActionsSpeedDial(actions = speedDialActions)
        }

        // ── Ride-inspection overlay toggles (right edge, below the menu) ──────
        // Only while looking at a past ride: switch the max-speed bubble and the
        // speed-coloured track on/off. Choices are persisted globally.
        RideViewOptionsControls(
            visible          = selectedRide != null,
            showMaxSpeedBubble = rideViewOptions.showMaxSpeedBubble,
            colorTrackBySpeed = rideViewOptions.colorTrackBySpeed,
            // Mock rides have no speed data, so colouring by speed is meaningless and
            // would hide the track — disable that toggle while inspecting one.
            colorBySpeedEnabled = selectedRide?.isMock != true,
            onToggleMaxSpeedBubble = viewModel::setMaxSpeedBubbleEnabled,
            onToggleColorBySpeed   = viewModel::setColorTrackBySpeedEnabled
        )

        when (val offState = offlineRoutingUiState) {
            is OfflineRoutingUiState.Downloading      -> OfflineSetupProgressOverlay(state = offState)
            is OfflineRoutingUiState.DownloadComplete -> OfflineSetupSuccessOverlay()
            else -> Unit
        }

        // ── Ride tracking — live stats card + record/stop FAB ────────────────
        // Hidden during active navigation: the ride is auto-recorded there and the
        // navigation card already owns the bottom area.
        if (activeNavigation == null) {
            val recording = rideTrackingState as? RideTrackingUiState.Recording
            if (recording != null) {
                RideTrackingOverlay(
                    stats     = recording.stats,
                    onStop    = viewModel::requestStopRideTracking,
                    onDiscard = viewModel::discardRideTracking
                )
            }
            RecordRideFab(
                isRecording = recording != null,
                onClick = {
                    if (recording != null) viewModel.requestStopRideTracking()
                    else startRideRecording()
                }
            )
        }

        // Dedicated "re-centre & follow" button — appears only during a follow
        // session (navigation / recording) once the user has panned the map away.
        // Stacked above the right-edge FABs: clear of the record FAB (88 dp) when
        // it is shown (recording without navigation), otherwise just above the
        // location FAB (88 dp), so it never overlaps them.
        RecenterFollowFab(
            visible       = isFollowSession && !isFollowingLocation,
            bottomPadding = if (activeNavigation == null && isRecordingRide) 160.dp else 88.dp,
            onClick       = viewModel::recenterOnUserLocation
        )

        // My-location button — hidden during a follow session (active navigation
        // or a ride recording): it's a no-op then, and the dedicated re-centre &
        // follow button takes over once the rider pans the map away.
        if (!isFollowSession) {
            MyLocationFab(onClick = requestOrUseLocation)
        }

        // ── Recorded-ride detail — non-modal sheet ───────────────────────────
        // Lives inside the map Box (not in MapBottomSheets) so it overlays the
        // map without a scrim: only its surface consumes touches, leaving the
        // drawn ride track fully pan/pinch/zoom-able above the collapsed sheet.
        selectedRide?.let { ride ->
            RideDetailSheet(
                ride      = ride,
                onDismiss = {
                    // Closing a ride's detail returns to the "My rides" list it was
                    // opened from, instead of leaving the bare map.
                    viewModel.dismissSelectedRide()
                    screenUiState.openRides()
                },
                onDelete  = { id -> viewModel.deleteRecordedRide(id) },
                onRename  = { id, name -> viewModel.renameRecordedRide(id, name) },
                onSetArchived = { id, archived -> viewModel.setRecordedRideArchived(id, archived) },
                onOpenAnalysis = onOpenRideAnalysis,
                onSaveAsRoute = { r -> viewModel.saveRideAsRoute(r) }
            )
        }

        // ── Saved-route preview — non-modal card over the map ────────────────
        // Draws the route's line (via the marker pass) and frames it, letting the
        // rider inspect it and its leaderboard before riding, while the map stays
        // pan/zoom-able above the card.
        previewedRoute?.let { route ->
            de.velospot.feature.map.presentation.sheets.RoutePreviewSheet(
                route = route,
                summary = previewedRouteSummary,
                onRideForward = { viewModel.ridePlannedRoute(route, reversed = false) },
                onRideReverse = { viewModel.ridePlannedRoute(route, reversed = true) },
                onOpenLeaderboard = { viewModel.openRouteLeaderboard(route) },
                onClose = {
                    // Closing the preview returns to the "My routes" list it was
                    // opened from, instead of leaving the bare map.
                    viewModel.closeRoutePreview()
                    screenUiState.openPlannedRoutes()
                }
            )
        }

        // ── Name-on-stop prompt for a manual recording ───────────────────────
        rideNamePrompt?.let { prompt ->
            de.velospot.feature.map.presentation.sheets.RideNamePromptDialog(
                suggestion = prompt.suggestion,
                onConfirm  = { name -> viewModel.confirmRideNameAndStop(name) },
                onDismiss  = viewModel::cancelRideNamePrompt
            )
        }

        // ── Route planning panel (non-modal, keeps the map tappable) ─────────
        if (isPlanningRoute) {
            var showSaveRouteDialog by remember { mutableStateOf(false) }
            de.velospot.feature.map.presentation.sheets.RoutePlanningPanel(
                waypoints    = planningWaypoints,
                previewRoute = planningPreviewRoute,
                isComputing  = isComputingRoutePreview,
                onUndo       = viewModel::undoLastWaypoint,
                onCancel     = viewModel::cancelRoutePlanning,
                onSave       = { showSaveRouteDialog = true }
            )
            if (showSaveRouteDialog) {
                de.velospot.feature.map.presentation.sheets.SavePlaceDialog(
                    suggestedName = planningWaypoints.lastOrNull()?.label.orEmpty(),
                    onConfirm = { name ->
                        viewModel.savePlannedRoute(name)
                        showSaveRouteDialog = false
                    },
                    onDismiss = { showSaveRouteDialog = false }
                )
            }
        }

        // ── Animated branded launch overlay (top of the stack) ───────────────
        // Sits above the map and all controls while the style/tiles load, then
        // fades + scales away once the map is ready.
        VeloSpotSplash(visible = showSplash, mapReady = mapReady)
    }

    // ── Bottom sheets & dialogs ───────────────────────────────────────────────
    MapBottomSheets(viewModel = viewModel, screenUiState = screenUiState)
}


