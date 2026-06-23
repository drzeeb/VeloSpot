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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
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
import de.velospot.feature.map.presentation.markers.updateHeatmapLayer
import de.velospot.core.map.RideHeatmap
import de.velospot.feature.map.presentation.sheets.MapBottomSheets
import de.velospot.feature.map.presentation.sheets.RideDetailSheet
import de.velospot.feature.map.presentation.sheets.languageFlagForCode
import de.velospot.feature.map.presentation.sheets.resolveCurrentLanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap


/** Duration (ms) of the gentle camera glide that follows the live position while
 *  recording a ride without navigation. */
private const val CAMERA_FOLLOW_DURATION_MS = 600


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
    val isSimulatingRoute    by viewModel.isSimulatingRoute.collectAsStateWithLifecycle()
    val rideTrackingState    by viewModel.rideTrackingState.collectAsStateWithLifecycle()
    val rideTrackPoints      by viewModel.rideTrackPoints.collectAsStateWithLifecycle()
    val recordedRides        by viewModel.recordedRides.collectAsStateWithLifecycle()
    val selectedRide         by viewModel.selectedRide.collectAsStateWithLifecycle()
    val isFollowingLocation  by viewModel.isFollowingLocation.collectAsStateWithLifecycle()

    val activeNavigation = navigationUiState as? NavigationUiState.Active


    // Whether a follow-capable session is running (active navigation OR a live ride
    // recording). Drives the re-centre button + the recording follow camera.
    val isRecordingRide  = rideTrackingState is RideTrackingUiState.Recording
    val isFollowSession  = activeNavigation != null || isRecordingRide

    // Keep the screen awake while navigation is running, so the display does not
    // dim/lock mid-ride. The flag is cleared automatically when navigation ends
    // or the screen leaves composition.
    val isNavigating = activeNavigation != null
    val currentView = LocalView.current
    DisposableEffect(currentView, isNavigating) {
        currentView.keepScreenOn = isNavigating
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

    val mapView       = rememberMapViewWithLifecycle()
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
    LaunchedEffect(
        maplibreMap, uiState, favorites, selectedSpace,
        userLocation, activeNavigation, zoomBucket,
        normalMarkerIcon, favoriteMarkerIcon, selectedMarkerIcon,
        selectedSearchPin, customMapPin, styleVersion, savedPlaces, layerVisibility, parkedBike
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
                    suppressLocationDot = activeNavigation != null,
                    // …and it owns the route polyline (travelled/remaining split).
                    suppressRoute = activeNavigation != null,
                    // Minimal nav mode: hide all non-trip markers while navigating
                    // so only the route, destination and live position remain.
                    minimalNavMode = activeNavigation != null
                )
        }
    }

    // ── Recorded-ride heatmap overlay ─────────────────────────────────────────
    // Aggregate all recorded tracks into weighted cells (off the main thread) and
    // (re)draw the heatmap layer whenever the rides change, the layer is toggled,
    // or the style reloads. Hidden (and skipped) while the layer is off.
    LaunchedEffect(maplibreMap, styleVersion, recordedRides, layerVisibility.showHeatmap) {
        val style = maplibreMap?.style ?: return@LaunchedEffect
        if (!layerVisibility.showHeatmap) {
            updateHeatmapLayer(style, emptyList(), visible = false)
            return@LaunchedEffect
        }
        val cells = withContext(Dispatchers.Default) {
            RideHeatmap.build(recordedRides).map { Triple(it.latitude, it.longitude, it.intensity) }
        }
        updateHeatmapLayer(style, cells, visible = true)
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

    // ── Recorded-ride track polyline (live recording or a reopened ride) ──────
    LaunchedEffect(maplibreMap, styleVersion, rideTrackPoints) {
        val style = maplibreMap?.style ?: return@LaunchedEffect
        de.velospot.feature.map.presentation.markers.updateTrackLayer(
            style = style,
            points = rideTrackPoints.map { it.latitude to it.longitude },
            colorInt = markerStyleConfig.routeColor
        )
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
            onToggleSimulation    = viewModel::toggleRouteSimulation,
            onOpenAbout           = screenUiState::openAbout,
            onOpenRides           = screenUiState::openRides,
            onOpenRoundTrip       = screenUiState::openRoundTrip
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
                    onStop    = viewModel::stopRideTracking,
                    onDiscard = viewModel::discardRideTracking
                )
            }
            RecordRideFab(
                isRecording = recording != null,
                onClick = {
                    if (recording != null) viewModel.stopRideTracking()
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
                onDismiss = viewModel::dismissSelectedRide,
                onDelete  = { id -> viewModel.deleteRecordedRide(id) }
            )
        }
    }

    // ── Bottom sheets & dialogs ───────────────────────────────────────────────
    MapBottomSheets(viewModel = viewModel, screenUiState = screenUiState)
}


