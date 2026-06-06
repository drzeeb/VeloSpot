package de.velospot.feature.map.presentation

import de.velospot.domain.model.BikeParkingSpace

sealed class MapUiState {
    data object Loading : MapUiState()
    data class Success(val spaces: List<BikeParkingSpace>) : MapUiState()
    data class Error(val message: String) : MapUiState()
}

