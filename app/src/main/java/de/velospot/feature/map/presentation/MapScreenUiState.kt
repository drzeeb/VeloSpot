package de.velospot.feature.map.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Stable
internal class MapScreenUiState {
    var isMenuExpanded by mutableStateOf(false)
        private set

    /** The unified Settings sheet that replaced the old top-bar dropdown menu. */
    var isSettingsSheetVisible by mutableStateOf(false)
        private set

    var isFavoritesSheetVisible by mutableStateOf(false)
        private set

    var isLanguageSheetVisible by mutableStateOf(false)
        private set

    var isLayersSheetVisible by mutableStateOf(false)
        private set

    var isNavigationViewSheetVisible by mutableStateOf(false)
        private set

    var isAboutSheetVisible by mutableStateOf(false)
        private set

    var isRidesSheetVisible by mutableStateOf(false)
        private set

    /** The round-trip generator sheet (pick a target distance). */
    var isRoundTripSheetVisible by mutableStateOf(false)
        private set

    /** The list of saved planned multi-waypoint routes. */
    var isPlannedRoutesSheetVisible by mutableStateOf(false)
        private set

    /** Sub-sheet: appearance & map settings (dark mode, language, 2D/3D, layers). */
    var isDisplaySettingsSheetVisible by mutableStateOf(false)
        private set

    /** Sub-sheet: navigation & routing settings (voice, keep screen on, offline profile). */
    var isNavRoutingSheetVisible by mutableStateOf(false)
        private set

    /** Opens the unified Settings sheet (top-bar menu button). */
    fun expandMenu() {
        isSettingsSheetVisible = true
        isMenuExpanded = true
    }

    fun dismissMenu() {
        isSettingsSheetVisible = false
        isMenuExpanded = false
    }

    fun openFavorites() {
        isFavoritesSheetVisible = true
        dismissMenu()
    }

    fun closeFavorites() {
        isFavoritesSheetVisible = false
    }

    fun openLanguage() {
        isLanguageSheetVisible = true
        dismissMenu()
    }

    fun closeLanguage() {
        isLanguageSheetVisible = false
    }

    fun openLayers() {
        isLayersSheetVisible = true
        dismissMenu()
    }

    fun closeLayers() {
        isLayersSheetVisible = false
    }

    fun openNavigationView() {
        isNavigationViewSheetVisible = true
        dismissMenu()
    }

    fun closeNavigationView() {
        isNavigationViewSheetVisible = false
    }

    fun openAbout() {
        isAboutSheetVisible = true
        dismissMenu()
    }

    fun closeAbout() {
        isAboutSheetVisible = false
    }

    fun openRides() {
        isRidesSheetVisible = true
        dismissMenu()
    }

    fun closeRides() {
        isRidesSheetVisible = false
    }

    fun openRoundTrip() {
        isRoundTripSheetVisible = true
        dismissMenu()
    }

    fun closeRoundTrip() {
        isRoundTripSheetVisible = false
    }

    fun openPlannedRoutes() {
        isPlannedRoutesSheetVisible = true
        dismissMenu()
    }

    fun closePlannedRoutes() {
        isPlannedRoutesSheetVisible = false
    }

    /** Opens the "Appearance & map" sub-sheet from the main Settings sheet. */
    fun openDisplaySettings() {
        isDisplaySettingsSheetVisible = true
    }

    fun closeDisplaySettings() {
        isDisplaySettingsSheetVisible = false
    }

    /** Opens the "Navigation & routing" sub-sheet from the main Settings sheet. */
    fun openNavRouting() {
        isNavRoutingSheetVisible = true
    }

    fun closeNavRouting() {
        isNavRoutingSheetVisible = false
    }
}

@Composable
internal fun rememberMapScreenUiState(): MapScreenUiState = remember { MapScreenUiState() }
