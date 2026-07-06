package de.velospot.feature.map.presentation.sheets

import de.velospot.feature.map.presentation.*

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.R
import de.velospot.core.map.NavigationHandler
import de.velospot.core.share.LocationSharer
import de.velospot.domain.model.GeoCoordinate

/**
 * All modal bottom sheets and dialogs of the map screen, grouped in one place.
 *
 * Collects the sheet-related state directly from [MapViewModel] and owns the
 * small bits of local UI state (e.g. the "name this favourite" dialog), keeping
 * [MainMapScreen] focused on the map view, overlays and top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapBottomSheets(
    viewModel: MapViewModel,
    screenUiState: MapScreenUiState
) {
    val context = LocalContext.current

    val favorites            by viewModel.favorites.collectAsStateWithLifecycle()
    val favoriteSpaces       by viewModel.favoriteSpaces.collectAsStateWithLifecycle()
    val savedPlaces          by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val selectedSpace        by viewModel.selectedSpace.collectAsStateWithLifecycle()
    val selectedSearchPin    by viewModel.selectedSearchPin.collectAsStateWithLifecycle()
    val customMapPin         by viewModel.customMapPin.collectAsStateWithLifecycle()
    val customMapPinAddress  by viewModel.customMapPinAddress.collectAsStateWithLifecycle()
    val selectedSavedPlace   by viewModel.selectedSavedPlace.collectAsStateWithLifecycle()
    val parkedBike           by viewModel.parkedBike.collectAsStateWithLifecycle()
    val isParkedBikeSheetVisible by viewModel.isParkedBikeSheetVisible.collectAsStateWithLifecycle()
    val recordedRideSummaries by viewModel.recordedRideSummaries.collectAsStateWithLifecycle()
    val userLocation         by viewModel.userLocation.collectAsStateWithLifecycle()
    val offlineRoutingUiState by viewModel.offlineRoutingUiState.collectAsStateWithLifecycle()
    val showOfflineSetupSheet by viewModel.showOfflineSetupSheet.collectAsStateWithLifecycle()
    val showProfileSheet     by viewModel.showProfileSheet.collectAsStateWithLifecycle()
    val showWifiWarning      by viewModel.showWifiWarning.collectAsStateWithLifecycle()
    val navigationUiState    by viewModel.navigationUiState.collectAsStateWithLifecycle()
    val layerVisibility      by viewModel.layerVisibility.collectAsStateWithLifecycle()
    val is3DNavigation       by viewModel.is3DNavigation.collectAsStateWithLifecycle()
    val plannedRoutes        by viewModel.plannedRoutes.collectAsStateWithLifecycle()
    val leaderboardRoute     by viewModel.leaderboardRoute.collectAsStateWithLifecycle()
    val routeAttempts        by viewModel.routeAttempts.collectAsStateWithLifecycle()
    val activeNavigation = navigationUiState as? NavigationUiState.Active

    // Controls the "name this favourite" dialog opened from the custom pin sheet.
    var showSavePlaceDialog by remember { mutableStateOf(false) }
    // Controls the "name this favourite" dialog opened from the search pin sheet.
    var showSaveSearchPinDialog by remember { mutableStateOf(false) }

    // Title for the system share sheet when sharing a pin's location.
    val shareLocationChooserTitle = stringResource(R.string.share_location_chooser_title)

    val configuration       = LocalConfiguration.current
    val currentLanguageCode = remember(configuration) {
        resolveCurrentLanguageCode(context, configuration.locales.get(0)?.language.orEmpty())
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

    if (screenUiState.isFavoritesSheetVisible) {
        FavoritesSheet(
            spaces          = favoriteSpaces,
            favoriteIds     = favorites,
            savedPlaces     = savedPlaces,
            onDismiss       = screenUiState::closeFavorites,
            onStartNavigation = startNavigationHandler,
            onShowDetails   = showDetailsHandler,
            onToggleFavorite = viewModel::toggleFavorite,
            onNavigateSavedPlace = { place ->
                screenUiState.closeFavorites()
                viewModel.navigateToSavedPlace(place)
            },
            onShowSavedPlace = { place ->
                screenUiState.closeFavorites()
                viewModel.selectSavedPlace(place)
            },
            onRemoveSavedPlace = viewModel::removeSavedPlace
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

    if (screenUiState.isLayersSheetVisible) {
        LayersSheet(
            visibility = layerVisibility,
            onToggle   = viewModel::setLayerVisible,
            onDismiss  = screenUiState::closeLayers
        )
    }

    if (screenUiState.isNavigationViewSheetVisible) {
        NavigationViewSheet(
            is3DEnabled = is3DNavigation,
            onSelect    = viewModel::setNavigation3DEnabled,
            onDismiss   = screenUiState::closeNavigationView
        )
    }

    if (screenUiState.isAboutSheetVisible) {
        AboutSheet(onDismiss = screenUiState::closeAbout)
    }

    // "My rides" timeline (list of recorded rides).
    if (screenUiState.isRidesSheetVisible) {
        // GPX documents staged for "save to file" (built asynchronously in the
        // ViewModel once the selected rides' tracks are loaded).
        val pendingGpxExport by viewModel.pendingGpxExport.collectAsStateWithLifecycle()
        val gpxImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) viewModel.importGpxFiles(uris)
        }
        // Single file → "Create document"; several separate files → pick a folder.
        val gpxCreateLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/gpx+xml")
        ) { uri ->
            val doc = viewModel.pendingGpxExport.value?.firstOrNull()
            if (uri != null && doc != null) viewModel.saveGpxToUri(uri, doc.content)
            viewModel.consumePendingGpxExport()
        }
        val gpxTreeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
        ) { treeUri ->
            val documents = viewModel.pendingGpxExport.value.orEmpty()
            if (treeUri != null && documents.isNotEmpty()) {
                viewModel.saveGpxToTree(treeUri, documents)
            }
            viewModel.consumePendingGpxExport()
        }
        // Once documents are staged, launch the matching SAF picker (a single file
        // creates a document; several separate files pick a target folder).
        LaunchedEffect(pendingGpxExport) {
            val documents = pendingGpxExport ?: return@LaunchedEffect
            when {
                documents.size == 1 -> gpxCreateLauncher.launch(documents.first().fileName)
                documents.isNotEmpty() -> gpxTreeLauncher.launch(null)
            }
        }
        RidesSheet(
            rides        = recordedRideSummaries,
            onDismiss    = screenUiState::closeRides,
            onSelectRide = { ride ->
                screenUiState.closeRides()
                viewModel.selectRecordedRide(ride)
            },
            onExportRides = { selected, combine, save ->
                if (save) {
                    viewModel.prepareGpxSave(selected, combine)
                } else {
                    viewModel.exportRidesAsGpx(selected, combine)
                }
            },
            onImport = {
                // GPX has no widely-registered MIME type, so allow any document and
                // let the parser validate; common GPX/XML types are offered as hints.
                gpxImportLauncher.launch(
                    arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")
                )
            }
        )
    }

    // Round-trip generator (pick a target distance, loop back to start).
    if (screenUiState.isRoundTripSheetVisible) {
        RoundTripSheet(
            onStart = { distanceMeters ->
                screenUiState.closeRoundTrip()
                viewModel.startRoundTrip(distanceMeters)
            },
            onDismiss = screenUiState::closeRoundTrip
        )
    }

    // Saved multi-waypoint routes list.
    if (screenUiState.isPlannedRoutesSheetVisible) {
        PlannedRoutesSheet(
            routes = plannedRoutes,
            onDismiss = screenUiState::closePlannedRoutes,
            onRide = { route, reversed ->
                screenUiState.closePlannedRoutes()
                viewModel.ridePlannedRoute(route, reversed)
            },
            onOpenLeaderboard = { route -> viewModel.openRouteLeaderboard(route) },
            onDelete = viewModel::deletePlannedRoute
        )
    }

    // A route's forward / reverse leaderboard.
    leaderboardRoute?.let { route ->
        RouteLeaderboardSheet(
            route = route,
            attempts = routeAttempts,
            onDismiss = viewModel::closeRouteLeaderboard
        )
    }

    // Detail view for a single recorded ride (stats + speed timeline) is a
    // *non-modal* sheet so the map stays interactive while a ride is drawn — it
    // is rendered inside the map layout Box of MainMapScreen, not here.

    if (showOfflineSetupSheet) {
        OfflineRoutingSetupSheet(
            onConfirm     = viewModel::confirmOfflineRoutingSetup,
            onConfirmFull = viewModel::confirmOfflineRoutingFullSetup,
            onDismiss     = viewModel::dismissOfflineSetupSheet
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
        val elevationPreference by viewModel.elevationPreference.collectAsStateWithLifecycle()
        RoutingProfileSheet(
            currentProfile         = currentProfile,
            onSelectProfile        = viewModel::selectRoutingProfile,
            currentElevation       = elevationPreference,
            onSelectElevation      = viewModel::selectElevationPreference,
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
            onToggleFavorite = viewModel::toggleFavorite,
            onShare         = {
                LocationSharer.shareLocation(
                    context      = context,
                    latitude     = space.latitude,
                    longitude    = space.longitude,
                    label        = space.name ?: space.address,
                    chooserTitle = shareLocationChooserTitle
                )
            }
        )
    }

    selectedSearchPin?.let { pin ->
        // Reuse the very same card as the custom map pin so a searched address
        // offers navigate, save-as-favourite and remove-pin actions too.
        CustomMapPinSheet(
            pin              = GeoCoordinate(pin.latitude, pin.longitude),
            address          = pin.displayName,
            onDismiss        = viewModel::dismissSearchPin,
            onNavigate       = { viewModel.startNavigationToAddress(pin) },
            onRemove         = viewModel::dismissSearchPin,
            onSaveAsFavorite = { showSaveSearchPinDialog = true },
            onShare          = {
                LocationSharer.shareLocation(
                    context      = context,
                    latitude     = pin.latitude,
                    longitude    = pin.longitude,
                    label        = pin.displayName,
                    chooserTitle = shareLocationChooserTitle
                )
            },
            title            = stringResource(R.string.search_result_pin_title),
            subtitle         = null
        )
    }

    // Name-and-save dialog for the current search pin.
    if (showSaveSearchPinDialog && selectedSearchPin != null) {
        val suggestedName = selectedSearchPin?.displayName?.substringBefore(",")?.trim().orEmpty()
        SavePlaceDialog(
            suggestedName = suggestedName,
            onConfirm = { name ->
                viewModel.saveSearchPinAsFavorite(name)
                showSaveSearchPinDialog = false
            },
            onDismiss = { showSaveSearchPinDialog = false }
        )
    }

    customMapPin?.let { pin ->
        // Hide the sheet while navigating to this pin – this covers both the
        // route-calculation phase (Loading / DownloadingSegments) and the active
        // navigation. The pin stays visible on the map as a route end-point.
        val navigatingToPin = activeNavigation?.destination?.id == MapViewModel.ID_CUSTOM_MAP_PIN
        val calculatingRoute = navigationUiState is NavigationUiState.Loading ||
            navigationUiState is NavigationUiState.DownloadingSegments
        if (!navigatingToPin && !calculatingRoute) {
            CustomMapPinSheet(
                pin        = pin,
                address    = customMapPinAddress,
                onDismiss  = viewModel::dismissCustomMapPin,
                onNavigate = viewModel::startNavigationToCustomPin,
                onRemove   = viewModel::dismissCustomMapPin,
                onSaveAsFavorite = { showSavePlaceDialog = true },
                onShare = {
                    LocationSharer.shareLocation(
                        context      = context,
                        latitude     = pin.latitude,
                        longitude    = pin.longitude,
                        label        = customMapPinAddress,
                        chooserTitle = shareLocationChooserTitle
                    )
                },
                onParkBikeHere = { viewModel.parkBikeAt(pin.latitude, pin.longitude) }
            )
        }
    }

    // Name-and-save dialog for the current custom pin.
    if (showSavePlaceDialog && customMapPin != null) {
        val suggestedName = customMapPinAddress?.substringBefore(",")?.trim().orEmpty()
        SavePlaceDialog(
            suggestedName = suggestedName,
            onConfirm = { name ->
                viewModel.saveCustomPinAsFavorite(name)
                showSavePlaceDialog = false
            },
            onDismiss = { showSavePlaceDialog = false }
        )
    }

    selectedSavedPlace?.let { place ->
        SavedPlaceSheet(
            place      = place,
            onDismiss  = viewModel::dismissSelectedSavedPlace,
            onNavigate = viewModel::navigateToSavedPlace,
            onRemove   = viewModel::removeSavedPlace,
            onShare    = {
                LocationSharer.shareLocation(
                    context      = context,
                    latitude     = place.latitude,
                    longitude    = place.longitude,
                    label        = place.name,
                    chooserTitle = shareLocationChooserTitle
                )
            }
        )
    }

    // Parked-bike detail sheet (where the user left their bike).
    val bike = parkedBike
    if (isParkedBikeSheetVisible && bike != null) {
        ParkedBikeSheet(
            bike         = bike,
            userLocation = userLocation,
            onDismiss    = viewModel::dismissParkedBikeSheet,
            onNavigate   = viewModel::navigateToParkedBike,
            onPickUp     = viewModel::pickUpBike
        )
    }
}

