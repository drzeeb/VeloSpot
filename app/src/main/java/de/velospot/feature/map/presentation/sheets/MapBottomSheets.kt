package de.velospot.feature.map.presentation.sheets

import de.velospot.feature.map.presentation.*

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.velospot.core.map.NavigationHandler

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
    val offlineRoutingUiState by viewModel.offlineRoutingUiState.collectAsStateWithLifecycle()
    val showOfflineSetupSheet by viewModel.showOfflineSetupSheet.collectAsStateWithLifecycle()
    val showProfileSheet     by viewModel.showProfileSheet.collectAsStateWithLifecycle()
    val showWifiWarning      by viewModel.showWifiWarning.collectAsStateWithLifecycle()
    val navigationUiState    by viewModel.navigationUiState.collectAsStateWithLifecycle()
    val layerVisibility      by viewModel.layerVisibility.collectAsStateWithLifecycle()
    val is3DNavigation       by viewModel.is3DNavigation.collectAsStateWithLifecycle()
    val activeNavigation = navigationUiState as? NavigationUiState.Active

    // Controls the "name this favourite" dialog opened from the custom pin sheet.
    var showSavePlaceDialog by remember { mutableStateOf(false) }

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
        val navigatingToPin = activeNavigation?.destination?.id == MapViewModel.ID_CUSTOM_MAP_PIN
        if (!navigatingToPin) {
            CustomMapPinSheet(
                pin        = pin,
                address    = customMapPinAddress,
                onDismiss  = viewModel::dismissCustomMapPin,
                onNavigate = viewModel::startNavigationToCustomPin,
                onRemove   = viewModel::dismissCustomMapPin,
                onSaveAsFavorite = { showSavePlaceDialog = true }
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
            onRemove   = viewModel::removeSavedPlace
        )
    }
}

