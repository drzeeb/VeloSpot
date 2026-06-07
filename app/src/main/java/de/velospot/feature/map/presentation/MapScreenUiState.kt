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

    var isFavoritesSheetVisible by mutableStateOf(false)
        private set

    var isLanguageSheetVisible by mutableStateOf(false)
        private set

    fun expandMenu() {
        isMenuExpanded = true
    }

    fun dismissMenu() {
        isMenuExpanded = false
    }

    fun openFavorites() {
        isFavoritesSheetVisible = true
        isMenuExpanded = false
    }

    fun closeFavorites() {
        isFavoritesSheetVisible = false
    }

    fun openLanguage() {
        isLanguageSheetVisible = true
        isMenuExpanded = false
    }

    fun closeLanguage() {
        isLanguageSheetVisible = false
    }
}

@Composable
internal fun rememberMapScreenUiState(): MapScreenUiState = remember { MapScreenUiState() }

