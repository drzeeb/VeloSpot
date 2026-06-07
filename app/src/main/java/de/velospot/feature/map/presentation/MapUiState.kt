package de.velospot.feature.map.presentation

import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.MapError

sealed class MapUiState {
    data object Loading : MapUiState()
    data class Success(val spaces: List<BikeParkingSpace>) : MapUiState()
    data class Error(val error: MapError) : MapUiState()
}



