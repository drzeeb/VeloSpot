package de.velospot.feature.map.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberUpdatedState
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
import de.velospot.core.location.hasLocationPermission
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
import de.velospot.core.map.RideTracksMode
import de.velospot.feature.map.presentation.sheets.MapBottomSheets
import de.velospot.feature.map.presentation.sheets.GpxOpenChooserDialog
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
    autoStartRideRecording: Boolean = false,
    onAutoStartRideRecordingConsumed: () -> Unit = {},
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
    val offlineUiState       by viewModel.offlineUiState.collectAsStateWithLifecycle()
    val isPickingOfflineRegion by viewModel.isPickingOfflineRegion.collectAsStateWithLifecycle()
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
    val hudEnabled           by viewModel.hudEnabled.collectAsStateWithLifecycle()
    val hudExpanded          by viewModel.hudExpanded.collectAsStateWithLifecycle()
    val portraitLockEnabled  by viewModel.portraitLockEnabled.collectAsStateWithLifecycle()
    val roundedBuildingsEnabled by viewModel.roundedBuildingsEnabled.collectAsStateWithLifecycle()
    val amoledEnabled        by viewModel.amoledEnabled.collectAsStateWithLifecycle()
    val sunAlertEnabled      by viewModel.sunAlertEnabled.collectAsStateWithLifecycle()
    val sunAlert             by viewModel.sunAlert.collectAsStateWithLifecycle()
    val weatherEnabled       by viewModel.weatherEnabled.collectAsStateWithLifecycle()
    val weather              by viewModel.weather.collectAsStateWithLifecycle()
    val onboardingCompleted  by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val isSimulatingRoute    by viewModel.isSimulatingRoute.collectAsStateWithLifecycle()
    val rideTrackingState    by viewModel.rideTrackingState.collectAsStateWithLifecycle()
    val rideTrackSegments    by viewModel.rideTrackSegments.collectAsStateWithLifecycle()
    val recordedRideTracks   by viewModel.recordedRideTracks.collectAsStateWithLifecycle()
    val selectedRide         by viewModel.selectedRide.collectAsStateWithLifecycle()
    val isPreviewRide        by viewModel.isPreviewRide.collectAsStateWithLifecycle()
    val gpxOpenChooser       by viewModel.gpxOpenChooser.collectAsStateWithLifecycle()
    val rideViewOptions      by viewModel.rideViewOptions.collectAsStateWithLifecycle()
    val rideNamePrompt       by viewModel.rideNamePrompt.collectAsStateWithLifecycle()
    val isFollowingLocation  by viewModel.isFollowingLocation.collectAsStateWithLifecycle()
    val isPlanningRoute       by viewModel.isPlanningRoute.collectAsStateWithLifecycle()
    val planningWaypoints     by viewModel.planningWaypoints.collectAsStateWithLifecycle()
    val planningPreviewRoute  by viewModel.planningPreviewRoute.collectAsStateWithLifecycle()
    val isComputingRoutePreview by viewModel.isComputingRoutePreview.collectAsStateWithLifecycle()
    val previewedRoute        by viewModel.previewedRoute.collectAsStateWithLifecycle()
    val previewedRouteSummary by viewModel.previewedRouteSummary.collectAsStateWithLifecycle()
    val recentDestinations    by viewModel.recentDestinations.collectAsStateWithLifecycle()
    val sensorSnapshot        by viewModel.sensorSnapshot.collectAsStateWithLifecycle()

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

    // Lock the screen orientation to portrait when the user enabled the setting,
    // so the display does not rotate while cycling. Disabled by default → the
    // activity follows the device's auto-rotate. The original orientation is
    // restored when the effect leaves composition.
    val activity = context as? android.app.Activity
    DisposableEffect(activity, portraitLockEnabled) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = if (portraitLockEnabled) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            previousOrientation?.let { activity?.requestedOrientation = it }
        }
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


    // Drives the subtle inline "zoom in to see parking" hint chip (see ZoomHintChip
    // in the UI layout below). State-driven — true only while zoomed out below the
    // minimum parking marker level — so it fades in/out instead of firing a Toast.
    val isZoomedOutForParking = zoomBucket < MIN_ZOOM_PARKING_VISIBLE.toInt()

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

    // ── Auto-start from the widget / Quick-Settings tile ──────────────────────
    // The widget/tile route "start recording" through MainActivity so the location
    // foreground service is launched from a foreground Activity (always allowed,
    // even cold-started on OEMs that block background FGS starts). When that request
    // arrives, fire the very same start the FAB uses — exactly once. With no location
    // permission we just consume the request and fall through to the normal UI (the
    // app is now open, so the user can grant it), matching the widget/tile behaviour.
    LaunchedEffect(autoStartRideRecording) {
        if (!autoStartRideRecording) return@LaunchedEffect
        val hasPermission = hasLocationPermission(context)
        if (hasPermission && !viewModel.isRecordingRide) {
            startRideRecording()
        }
        onAutoStartRideRecordingConsumed()
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

    // ── Recorded-ride "My rides" overlay (lines ↔ heatmap) ────────────────────
    // One unified layer driven by a single visibility flag + a display mode.
    // Exactly one of the two renderers (thin per-ride lines / aggregated heatmap)
    // is active when the layer is on, and neither when off. Switching mode swaps
    // cleanly: the inactive renderer is always hidden and its source emptied, so no
    // orphaned heatmap remains when showing lines and vice-versa. Both aggregations
    // run off the main thread. (Re)built whenever the rides change, the layer is
    // toggled, the mode changes, or the style reloads.
    LaunchedEffect(
        maplibreMap, styleVersion, recordedRideTracks,
        layerVisibility.showRideTracks, layerVisibility.rideTracksMode
    ) {
        val style = maplibreMap?.style ?: return@LaunchedEffect
        if (!layerVisibility.showRideTracks) {
            // Layer off: clear/hide both renderers.
            updateHeatmapLayer(style, emptyList(), visible = false)
            updateTracksHistoryLayer(style, emptyList(), markerStyleConfig.routeColor, visible = false)
            return@LaunchedEffect
        }
        val rides = recordedRideTracks.filterNot { it.isMock }
        when (layerVisibility.rideTracksMode) {
            RideTracksMode.HEATMAP -> {
                // Hide the lines renderer, then (re)build the heatmap.
                updateTracksHistoryLayer(style, emptyList(), markerStyleConfig.routeColor, visible = false)
                val cells = withContext(Dispatchers.Default) {
                    RideHeatmap.build(rides).map { Triple(it.latitude, it.longitude, it.intensity) }
                }
                updateHeatmapLayer(style, cells, visible = true)
            }
            RideTracksMode.LINES -> {
                // Hide the heatmap renderer, then (re)build the thin per-ride lines.
                updateHeatmapLayer(style, emptyList(), visible = false)
                val polylines = withContext(Dispatchers.Default) { RideTrackLines.build(rides) }
                updateTracksHistoryLayer(style, polylines, markerStyleConfig.routeColor, visible = true)
            }
        }
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

    // Apply the user's rounded-3D-building preference live (also re-applied after
    // style reloads, which rebuild the extrusion layer with sharp corners).
    LaunchedEffect(roundedBuildingsEnabled, maplibreMap, styleVersion) {
        navigationManager.setRoundedBuildings(roundedBuildingsEnabled)
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
    LaunchedEffect(maplibreMap, isDarkTheme, amoledEnabled) {
        val map = maplibreMap ?: return@LaunchedEffect
        map.setStyle(mapStyleUrl(isDarkTheme, amoledEnabled)) { style ->
            // Re-localize the base-map place labels to the app language on every
            // style (re)load — a fresh style resets them to their endonym default.
            de.velospot.feature.map.presentation.markers.localizeMapLabels(
                style,
                de.velospot.feature.map.presentation.markers.currentMapLanguage()
            )
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
    LaunchedEffect(maplibreMap, styleVersion, rideTrackSegments, selectedRide, activeNavigation != null, rideViewOptions.colorTrackBySpeed) {
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
                style = style, segments = emptyList(), colorInt = markerStyleConfig.routeColor
            )
            updateTrackSpeedLayer(style, segments, ride.maxSpeedMps, visible = true)
        } else {
            updateTrackSpeedLayer(style, emptyList(), 0.0, visible = false)
            // Suppress the live recording polyline while the navigation route owns
            // the map; still draw the track when just recording or inspecting a ride.
            val segments = if (activeNavigation != null && ride == null) {
                emptyList()
            } else {
                rideTrackSegments.map { seg -> seg.map { it.latitude to it.longitude } }
            }
            de.velospot.feature.map.presentation.markers.updateTrackLayer(
                style = style,
                segments = segments,
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
            onCancel          = viewModel::cancelRouteCalculation,
            isRecordingRide   = rideTrackingState is RideTrackingUiState.Recording,
            isRidePaused      = (rideTrackingState as? RideTrackingUiState.Recording)?.stats?.isPaused == true,
            onPauseToggle     = viewModel::togglePauseRideTracking,
            // Merge the trip-computer stats into the navigation card while recording,
            // if the HUD is enabled — the standalone HUD band is suppressed during
            // navigation (below), so the values live in one unified card instead.
            tripStats         = if (hudEnabled) {
                (rideTrackingState as? RideTrackingUiState.Recording)?.stats
            } else null,
            // Live external-sensor cells (HR/power/cadence) inside the merged card.
            sensorSnapshot    = if (hudEnabled) sensorSnapshot else null
        )

        // Turn-by-turn banner (top) — only during active navigation.
        if (activeNavigation != null) {
            MapTurnBanner(progress = navigationProgress)
        }

        // Trip Computer HUD (bottom band) — only while a ride is being recorded and
        // the user has opted in, and NOT during active navigation. During navigation
        // the trip-computer stats are instead MERGED into the MapNavigationOverlay
        // card (see tripStats above), so there is a single unified bottom card rather
        // than two overlapping ones.
        if (hudEnabled && activeNavigation == null) {
            (rideTrackingState as? RideTrackingUiState.Recording)?.let { recording ->
                TripComputerHud(
                    stats = recording.stats,
                    navigationProgress = navigationProgress,
                    expanded = hudExpanded,
                    onToggleExpanded = { viewModel.setHudExpanded(!hudExpanded) },
                    sensor = sensorSnapshot
                )
            }
        }


        // ── Search bar + Menu button – vertically centred in one Row ─────────
        // PERF: `menuState` only depends on rarely-changing settings/toggles and never
        // on the hot GPS-cadence flows (userLocation / navigationProgress /
        // rideTrackingState). Memoise it on its actual inputs so a plain GPS fix does
        // not re-allocate this large state object every recomposition (~1×/sec while
        // simulating), which would otherwise defeat skipping in `MapMenuCard`.
        val favoritesCount = favorites.size + savedPlaces.size
        val isBikeParked = parkedBike != null
        val isSettingsSheetVisible = screenUiState.isSettingsSheetVisible
        val isNavigationRouteActive = activeNavigation != null
        val menuState = remember(
            favoritesCount,
            isDarkTheme,
            currentLanguageFlag,
            isSettingsSheetVisible,
            offlineUiState,
            isBikeParked,
            voiceGuidanceEnabled,
            keepScreenOnEnabled,
            hudEnabled,
            portraitLockEnabled,
            roundedBuildingsEnabled,
            amoledEnabled,
            sunAlertEnabled,
            weatherEnabled,
            isNavigationRouteActive,
            isSimulatingRoute
        ) {
            MapMenuCardState(
                favoritesCount     = favoritesCount,
                isDarkTheme        = isDarkTheme,
                currentLanguageFlag = currentLanguageFlag,
                isExpanded         = isSettingsSheetVisible,
                offlineUiState     = offlineUiState,
                isBikeParked       = isBikeParked,
                voiceGuidanceEnabled = voiceGuidanceEnabled,
                keepScreenOnEnabled = keepScreenOnEnabled,
                hudEnabled         = hudEnabled,
                portraitLockEnabled = portraitLockEnabled,
                roundedBuildingsEnabled = roundedBuildingsEnabled,
                amoledEnabled      = amoledEnabled,
                sunAlertEnabled    = sunAlertEnabled,
                weatherEnabled     = weatherEnabled,
                // Debug-only GPS route simulator: always visible in debug
                // builds, enabled once a route is available to drive along.
                showSimulator      = de.velospot.BuildConfig.DEBUG,
                simulatorEnabled   = isNavigationRouteActive,
                isSimulating       = isSimulatingRoute
            )
        }
        // PERF: the ~30 action lambdas are stable — they only call ViewModel methods,
        // navigation callbacks or (for the toggles) read the *latest* setting value via
        // `rememberUpdatedState`. Memoising `MapMenuCardActions` with no keys reuses the
        // same instances across recompositions so a GPS fix does not re-allocate them.
        // The toggle handlers must NOT capture the value directly (that would go stale
        // once the memoised instance is retained), so they read through the updated
        // state holders below.
        val currentVoiceGuidance = rememberUpdatedState(voiceGuidanceEnabled)
        val currentKeepScreenOn = rememberUpdatedState(keepScreenOnEnabled)
        val currentHud = rememberUpdatedState(hudEnabled)
        val currentPortraitLock = rememberUpdatedState(portraitLockEnabled)
        val currentRoundedBuildings = rememberUpdatedState(roundedBuildingsEnabled)
        val currentAmoled = rememberUpdatedState(amoledEnabled)
        val currentDarkTheme = rememberUpdatedState(isDarkTheme)
        val currentSunAlert = rememberUpdatedState(sunAlertEnabled)
        val currentWeather = rememberUpdatedState(weatherEnabled)
        val currentDarkThemeToggle = rememberUpdatedState(onDarkThemeToggle)
        val menuActions = remember {
            MapMenuCardActions(
                onExpand              = screenUiState::expandMenu,
                onDismiss             = screenUiState::dismissMenu,
                onOpenFavorites       = screenUiState::openFavorites,
                onOpenLanguage        = screenUiState::openLanguage,
                onToggleDarkMode      = { currentDarkThemeToggle.value(); screenUiState.dismissMenu() },
                onOpenLayers          = screenUiState::openLayers,
                onOpenNavigationView  = screenUiState::openNavigationView,
                onOpenOfflineRegions  = viewModel::openOfflineRegions,
                onOpenProfileSheet    = viewModel::openProfileSheet,
                onParkBikeHere        = viewModel::parkBikeAtCurrentLocation,
                onShowParkedBike      = viewModel::showParkedBike,
                onToggleVoiceGuidance = { viewModel.setVoiceGuidanceEnabled(!currentVoiceGuidance.value) },
                onToggleKeepScreenOn  = { viewModel.setKeepScreenOnEnabled(!currentKeepScreenOn.value) },
                onToggleHud           = { viewModel.setHudEnabled(!currentHud.value) },
                onTogglePortraitLock  = { viewModel.setPortraitLockEnabled(!currentPortraitLock.value) },
                onToggleRoundedBuildings = { viewModel.setRoundedBuildingsEnabled(!currentRoundedBuildings.value) },
                onToggleAmoled        = {
                    // Enabling AMOLED implies dark mode; turn it on if not already.
                    if (!currentAmoled.value && !currentDarkTheme.value) currentDarkThemeToggle.value()
                    viewModel.setAmoledEnabled(!currentAmoled.value)
                },
                onToggleSunAlert      = { viewModel.setSunAlertEnabled(!currentSunAlert.value) },
                onToggleWeather       = { viewModel.setWeatherEnabled(!currentWeather.value) },
                onToggleSimulation    = viewModel::toggleRouteSimulation,
                onOpenAbout           = screenUiState::openAbout,
                onOpenRides           = screenUiState::openRides,
                onOpenRoundTrip       = screenUiState::openRoundTrip,
                onStartRoutePlanning  = viewModel::startRoutePlanning,
                onOpenPlannedRoutes   = screenUiState::openPlannedRoutes,
                onOpenDisplaySettings = screenUiState::openDisplaySettings,
                onOpenNavRouting      = screenUiState::openNavRouting,
                onOpenBikeGarage      = screenUiState::openBikeGarage,
                onOpenSensors         = screenUiState::openSensors
            )
        }
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
                recentDestinations = recentDestinations,
                onQueryChange    = viewModel::onSearchQueryChanged,
                onResultSelected = viewModel::onSearchResultSelected,
                onRecentSelected = viewModel::navigateToRecentDestination,
                onClear          = viewModel::onSearchCleared
            )
            Spacer(Modifier.width(8.dp))
            MapMenuCard(state = menuState, actions = menuActions)
        }

        // ── Current-weather chip (opt-in Open-Meteo) ─────────────────────────
        // Placed under the search bar at the top-start so it does not collide with
        // the right-edge speed-dial / menu FAB or the bottom HUD. Only composed when
        // a snapshot exists, which already implies the feature is enabled.
        weather?.let { snapshot ->
            WeatherChip(
                weather = snapshot,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 72.dp)
            )
        }

        // ── Zoom-in-for-parking hint ─────────────────────────────────────────
        // Top-centre, tucked under the search bar so it clears the top search bar,
        // the right-edge menu / speed-dial and the bottom record FAB / HUD. A calm,
        // in-theme replacement for the old zoom-out Toast: fades in only while
        // zoomed out and auto-hides (see ZoomHintChip).
        ZoomHintChip(
            visible = isZoomedOutForParking,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp)
        )

        // Recent destinations now live inside the search bar's initially-expanded
        // results dropdown (see AddressSearchBar), so there is no permanent on-map row.

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
        // External BLE sensor pairing (speed/cadence, power, heart-rate).
        if (screenUiState.isSensorsSheetVisible) {
            val rememberedSensors by viewModel.rememberedSensorAddresses.collectAsStateWithLifecycle()
            val wheelCircumference by viewModel.wheelCircumferenceMeters.collectAsStateWithLifecycle()
            de.velospot.feature.map.presentation.sheets.SensorsSheet(
                snapshot = sensorSnapshot,
                rememberedAddresses = rememberedSensors,
                wheelCircumferenceMeters = wheelCircumference,
                scan = viewModel::scanSensors,
                onRemember = viewModel::rememberSensor,
                onForget = viewModel::forgetSensor,
                onSetWheelCircumference = viewModel::setWheelCircumferenceMeters,
                onDismiss = screenUiState::closeSensors
            )
        }

        // ── Map actions speed-dial (right-centre FAB) ────────────────────────
        // The frequent things a rider *does* — plan a route, round trip, park the
        // bike, rides, saved routes, favourites — fan out over a half-circle from a
        // single FAB anchored on the right edge (vertically centred), so the
        // Settings sheet only holds actual settings. Hidden during active
        // navigation, where the bottom area belongs to the navigation card.
        if (activeNavigation == null) {
            // PERF: resolve the labels once per recomposition instead of re-running the
            // `stringResource` lookups inside a freshly-allocated `listOf(...)` on every
            // GPS fix, and memoise the action list on its only real input (whether a bike
            // is currently parked, which swaps the park ↔ show entry). `isBikeParked` is
            // captured for the park onClick via the memo key so it never goes stale.
            val routePlanLabel = stringResource(R.string.route_plan_menu)
            val roundTripLabel = stringResource(R.string.round_trip_menu)
            val myRoutesLabel = stringResource(R.string.route_my_routes_menu)
            val myRidesLabel = stringResource(R.string.menu_my_rides)
            val parkBikeLabel = stringResource(
                if (isBikeParked) R.string.menu_show_parked_bike
                else R.string.menu_park_bike_here
            )
            val favoritesLabel = stringResource(R.string.favorites_title)
            val speedDialActions = remember(
                isBikeParked,
                routePlanLabel,
                roundTripLabel,
                myRoutesLabel,
                myRidesLabel,
                parkBikeLabel,
                favoritesLabel
            ) {
                listOf(
                    SpeedDialAction(
                        label = routePlanLabel,
                        icon = Icons.Default.Route,
                        onClick = viewModel::startRoutePlanning
                    ),
                    SpeedDialAction(
                        label = roundTripLabel,
                        icon = Icons.Default.Loop,
                        onClick = screenUiState::openRoundTrip
                    ),
                    SpeedDialAction(
                        label = myRoutesLabel,
                        icon = Icons.AutoMirrored.Filled.AltRoute,
                        onClick = screenUiState::openPlannedRoutes
                    ),
                    SpeedDialAction(
                        label = myRidesLabel,
                        icon = Icons.Default.Timeline,
                        onClick = screenUiState::openRides
                    ),
                    SpeedDialAction(
                        label = parkBikeLabel,
                        icon = Icons.AutoMirrored.Filled.DirectionsBike,
                        onClick = {
                            if (isBikeParked) viewModel.showParkedBike()
                            else viewModel.parkBikeAtCurrentLocation()
                        }
                    ),
                    SpeedDialAction(
                        label = favoritesLabel,
                        icon = Icons.Default.Favorite,
                        onClick = screenUiState::openFavorites
                    )
                )
            }
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

        offlineUiState.downloading?.let { OfflineSetupProgressOverlay(state = it) }

        // ── Ride tracking — live stats card + record/stop FAB ────────────────
        // Hidden during active navigation: the ride is auto-recorded there and the
        // navigation card already owns the bottom area.
        if (activeNavigation == null) {
            val recording = rideTrackingState as? RideTrackingUiState.Recording
            if (recording != null) {
                RideTrackingOverlay(
                    stats     = recording.stats,
                    onStop    = viewModel::requestStopRideTracking,
                    onDiscard = viewModel::discardRideTracking,
                    onPauseToggle = viewModel::togglePauseRideTracking
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

        // Golden-hour sunrise/sunset alert FAB — bottom-left so it never overlaps
        // the right-edge FABs or the centre-right speed-dial. Only shown outside
        // active navigation (consistent with the speed-dial); visibility within the
        // 30-minute pre-window is decided upstream (sunAlert == null hides it).
        if (activeNavigation == null) {
            SunAlertFab(sunAlert = sunAlert)
        }

        // ── Recorded-ride detail — non-modal sheet ───────────────────────────
        // Lives inside the map Box (not in MapBottomSheets) so it overlays the
        // map without a scrim: only its surface consumes touches, leaving the
        // drawn ride track fully pan/pinch/zoom-able above the collapsed sheet.
        selectedRide?.let { ride ->
            // "Save as" GPX picker for the single ride shown in the detail sheet.
            // A ride always yields exactly one document, so this is always a
            // CreateDocument (file) pick. Kept independent from the "My rides"
            // multi-select save (which stages viewModel.pendingGpxExport instead),
            // so the two SAF flows never cross-trigger.
            val pendingRideGpxSave by viewModel.pendingRideGpxSave.collectAsStateWithLifecycle()
            val gpxSaveLauncher = rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/gpx+xml")
            ) { uri ->
                val doc = viewModel.pendingRideGpxSave.value
                if (uri != null && doc != null) viewModel.saveGpxToUri(uri, doc.content)
                viewModel.consumePendingRideGpxSave()
            }
            LaunchedEffect(pendingRideGpxSave) {
                val doc = pendingRideGpxSave ?: return@LaunchedEffect
                gpxSaveLauncher.launch(doc.fileName)
            }
            RideDetailSheet(
                ride      = ride,
                onDismiss = {
                    // Closing a ride's detail returns to the "My rides" list it was
                    // opened from, instead of leaving the bare map. A GPX preview is
                    // simply discarded (nothing was persisted) and stays on the map.
                    val wasPreview = isPreviewRide
                    viewModel.dismissSelectedRide()
                    if (!wasPreview) screenUiState.openRides()
                },
                onDelete  = { id -> viewModel.deleteRecordedRide(id) },
                onRename  = { id, name -> viewModel.renameRecordedRide(id, name) },
                onSetArchived = { id, archived -> viewModel.setRecordedRideArchived(id, archived) },
                onOpenAnalysis = onOpenRideAnalysis,
                onSaveAsRoute = { r -> viewModel.saveRideAsRoute(r) },
                onSaveGpx = { r -> viewModel.prepareRideGpxSave(r) },
                isImportable = isPreviewRide,
                onImport = { viewModel.importPreviewedRide() },
                weatherEnabled = weatherEnabled
            )
        }

        // ── "Open .gpx" chooser — import directly or just preview ────────────
        gpxOpenChooser?.let {
            GpxOpenChooserDialog(
                onImport  = { viewModel.importOpenedGpx() },
                onPreview = { viewModel.previewOpenedGpx() },
                onDismiss = { viewModel.dismissGpxOpenChooser() }
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
                onDownloadOffline = { viewModel.downloadRouteForOffline(route) },
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

        // ── Offline-region picker (pick a spot on the map to download) ───────
        if (isPickingOfflineRegion) {
            OfflineRegionPickerOverlay(
                onConfirm = {
                    val center = maplibreMap?.cameraPosition?.target
                    if (center != null) {
                        viewModel.addOfflineRegionAt(center.latitude, center.longitude)
                    } else {
                        viewModel.cancelPickingOfflineRegion()
                    }
                },
                onCancel = viewModel::cancelPickingOfflineRegion
            )
        }

        // ── Animated branded launch overlay (top of the stack) ───────────────
        // Sits above the map and all controls while the style/tiles load, then
        // fades + scales away once the map is ready.
        VeloSpotSplash(visible = showSplash, mapReady = mapReady)
    }

    // ── First-launch welcome onboarding ───────────────────────────────────────
    // A compact 3-card sheet shown once on the very first start (and re-openable
    // from the About sheet). Gated on `!showSplash` so it never overlaps the launch
    // splash, and only shown once the stored flag has definitely loaded as `false`
    // (null = still loading → don't flash it for returning users).
    if (!showSplash && onboardingCompleted == false) {
        de.velospot.feature.map.presentation.sheets.WelcomeOnboardingSheet(
            onFinish = viewModel::completeOnboarding,
            onActivateOfflineRouting = {
                viewModel.completeOnboarding()
                viewModel.openOfflineRegions()
            }
        )
    }

    // ── Bottom sheets & dialogs ───────────────────────────────────────────────
    MapBottomSheets(viewModel = viewModel, screenUiState = screenUiState)
}


