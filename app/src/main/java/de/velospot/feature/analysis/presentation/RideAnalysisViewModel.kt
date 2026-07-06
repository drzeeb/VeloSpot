package de.velospot.feature.analysis.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velospot.core.analysis.Achievement
import de.velospot.core.analysis.BestEfforts
import de.velospot.core.analysis.RideAnalysis
import de.velospot.core.analysis.RideMapData
import de.velospot.core.analysis.analyzeRide
import de.velospot.core.analysis.buildRideMapData
import de.velospot.core.analysis.computeBestEfforts
import de.velospot.core.analysis.evaluateAchievements
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** UI state for the full-screen ride analysis. */
sealed interface RideAnalysisUiState {
    data object Loading : RideAnalysisUiState
    /** The ride id wasn't found (e.g. it was deleted while the screen was open). */
    data object NotFound : RideAnalysisUiState
    data class Ready(
        val ride: RecordedRide,
        val analysis: RideAnalysis,
        val mapData: RideMapData,
        val achievements: List<Achievement>,
        val bestEfforts: BestEfforts
    ) : RideAnalysisUiState
}

/**
 * Loads the recorded ride identified by the `rideId` navigation argument and
 * computes its [RideAnalysis] off the main thread. Reactive: if the ride is
 * renamed/archived/deleted the screen updates automatically.
 */
@HiltViewModel
class RideAnalysisViewModel @Inject constructor(
    repository: RecordedRidesRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val rideId: String = checkNotNull(savedStateHandle[ARG_RIDE_ID]) {
        "RideAnalysisViewModel requires a '$ARG_RIDE_ID' argument"
    }

    val uiState: StateFlow<RideAnalysisUiState> = repository.getRidesWithTracksFlow()
        .map { rides ->
            val ride = rides.firstOrNull { it.id == rideId }
            if (ride == null) {
                RideAnalysisUiState.NotFound
            } else {
                val analysis = analyzeRide(ride)
                RideAnalysisUiState.Ready(
                    ride = ride,
                    analysis = analysis,
                    mapData = buildRideMapData(ride),
                    achievements = evaluateAchievements(ride, analysis, rides),
                    bestEfforts = computeBestEfforts(ride)
                )
            }
        }
        .flowOn(Dispatchers.Default) // analysis can be heavy on long rides
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RideAnalysisUiState.Loading
        )

    companion object {
        const val ARG_RIDE_ID = "rideId"
    }
}
