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
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.repository.MapSettingsRepository
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
 *
 * Only the **target** ride's GPS track is ever deserialised. The cross-ride
 * personal-record comparison reads the track-free [RecordedRideSummary]s, so the
 * screen never pays to parse every other ride's track. The expensive analysis
 * ([analyzeRide]/[buildRideMapData]/[computeBestEfforts], incl. climbs, best
 * efforts and the map-replay prep) is keyed on the ride's **track identity**
 * ([TrackKey]) and cached: a rename, a weather toggle or a bike re-assignment
 * only re-projects the cheap reactive fields on top of it, never recomputes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RideAnalysisViewModel(
    repository: RecordedRidesRepository,
    mapSettings: MapSettingsRepository,
    savedStateHandle: SavedStateHandle,
    /**
     * Dispatcher the heavy analysis runs on via [flowOn]. Defaults to
     * [Dispatchers.Default] in production; tests inject a scheduler-backed test
     * dispatcher so the whole flow stays on the virtual clock (no leaked
     * real-thread continuation can dispatch onto a reset test `Main`).
     */
    private val analysisDispatcher: CoroutineDispatcher,
) : ViewModel() {

    // Hilt binds every parameter of this secondary constructor already; it uses
    // it as the injection point while production keeps the real Default dispatcher.
    @Inject
    constructor(
        repository: RecordedRidesRepository,
        mapSettings: MapSettingsRepository,
        savedStateHandle: SavedStateHandle,
    ) : this(repository, mapSettings, savedStateHandle, Dispatchers.Default)

    private val rideId: String = checkNotNull(savedStateHandle[ARG_RIDE_ID]) {
        "RideAnalysisViewModel requires a '$ARG_RIDE_ID' argument"
    }

    /**
     * The lightweight identity of the target ride's **track**: it changes only when
     * the ride appears/disappears or its track is replaced (e.g. re-imported), never
     * on a rename / archive / bike-reassign. Gates the expensive analysis so those
     * metadata edits don't recompute climbs, best efforts or the replay frames.
     */
    private data class TrackKey(
        val id: String,
        val startedAt: Long,
        val endedAt: Long,
        val distanceMeters: Double
    )

    /** The cached heavy analysis, computed once per [TrackKey]. */
    private data class Heavy(
        val ride: RecordedRide,
        val analysis: RideAnalysis,
        val mapData: RideMapData,
        val bestEfforts: BestEfforts
    )

    private val summaries: Flow<List<RecordedRideSummary>> = repository.getRideSummariesFlow()

    /**
     * Loads only the target ride's full track and computes the heavy analysis,
     * re-running solely when the [TrackKey] changes (a real track change), not on
     * unrelated metadata writes. Emits `null` when the ride no longer exists.
     */
    private val heavy: Flow<Heavy?> =
        summaries
            .map { list ->
                list.firstOrNull { it.id == rideId }
                    ?.let { TrackKey(it.id, it.startedAt, it.endedAt, it.distanceMeters) }
            }
            .distinctUntilChanged()
            .mapLatest { key ->
                if (key == null) return@mapLatest null
                repository.getRide(rideId)?.let { ride ->
                    val analysis = analyzeRide(ride)
                    Heavy(
                        ride = ride,
                        analysis = analysis,
                        mapData = buildRideMapData(ride),
                        bestEfforts = computeBestEfforts(ride)
                    )
                }
            }
            .flowOn(analysisDispatcher) // analysis can be heavy on long rides

    val uiState: StateFlow<RideAnalysisUiState> =
        combine(
            heavy,
            summaries,
            mapSettings.weatherEnabled
        ) { heavy, summaries, weatherEnabled ->
            if (heavy == null) {
                RideAnalysisUiState.NotFound
            } else {
                // Merge the cheap, reactive metadata (name / archived / bike) and the
                // weather toggle on top of the cached heavy result — none of these
                // affect the analysis numbers, so the heavy work is never redone.
                val summary = summaries.firstOrNull { it.id == rideId }
                val ride = heavy.ride.copy(
                    name = summary?.name ?: heavy.ride.name,
                    archivedAt = summary?.archivedAt,
                    bikeProfileId = summary?.bikeProfileId ?: heavy.ride.bikeProfileId,
                    // Hide the stored snapshot while the opt-in weather feature is off.
                    weather = if (weatherEnabled) heavy.ride.weather else null
                )
                RideAnalysisUiState.Ready(
                    ride = ride,
                    analysis = heavy.analysis,
                    mapData = heavy.mapData,
                    achievements = evaluateAchievements(ride, heavy.analysis, summaries),
                    bestEfforts = heavy.bestEfforts
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RideAnalysisUiState.Loading
        )

    companion object {
        const val ARG_RIDE_ID = "rideId"
    }
}
