package de.velospot.feature.bikeprofiles.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velospot.domain.model.BikeProfile
import de.velospot.domain.model.BikeType
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.repository.BikeProfilesRepository
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Per-bike aggregate ride statistics (real rides only — mock rides excluded). */
data class BikeProfileStats(
    val rideCount: Int = 0,
    val totalDistanceMeters: Double = 0.0,
    val totalMovingSeconds: Long = 0L,
    val totalElevationGainMeters: Double = 0.0,
    val lastRideAt: Long? = null
)

/** A bike plus its computed statistics and whether it is the active "ride next" bike. */
data class BikeProfileRow(
    val profile: BikeProfile,
    val stats: BikeProfileStats,
    /** `true` for the bike the next recording will be tagged with (active or default). */
    val isActive: Boolean
)

data class BikeProfilesUiState(
    val bikes: List<BikeProfileRow> = emptyList(),
    /** Rides not yet assigned to any bike (real rides only). */
    val unassignedRideCount: Int = 0,
    val isLoading: Boolean = true
) {
    val isEmpty: Boolean get() = bikes.isEmpty()
}

/**
 * Drives the bike garage UI: the list of bikes each with their own ride
 * statistics, the active/default markers, and the create / edit / delete /
 * set-default / switch-active actions.
 *
 * Statistics are derived purely from the track-free ride summaries grouped by
 * [RecordedRideSummary.bikeProfileId], so the split-per-bike numbers cost nothing
 * beyond the timeline data already in memory.
 */
@HiltViewModel
class BikeProfilesViewModel @Inject constructor(
    private val bikeProfilesRepository: BikeProfilesRepository,
    private val recordedRidesRepository: RecordedRidesRepository
) : ViewModel() {

    val uiState: StateFlow<BikeProfilesUiState> = combine(
        bikeProfilesRepository.bikeProfilesFlow(),
        recordedRidesRepository.getRideSummariesFlow(),
        bikeProfilesRepository.activeBikeProfileId
    ) { bikes, rides, activeId ->
        // Real rides only feed the split statistics (mock/simulator rides excluded).
        val realRides = rides.filterNot { it.isMock }
        val byBike = realRides.groupBy { it.bikeProfileId }
        // The effective active bike: explicit selection, else the default bike.
        val effectiveActiveId = activeId ?: bikes.firstOrNull { it.isDefault }?.id

        val rows = bikes.map { bike ->
            BikeProfileRow(
                profile = bike,
                stats = byBike[bike.id].orEmpty().toStats(),
                isActive = bike.id == effectiveActiveId
            )
        }
        BikeProfilesUiState(
            bikes = rows,
            unassignedRideCount = byBike[null].orEmpty().size,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BikeProfilesUiState()
    )

    /** Creates a new bike. The first bike created is made the default automatically. */
    fun addBike(draft: BikeDraft) {
        viewModelScope.launch {
            val existing = bikeProfilesRepository.bikeProfilesFlow()
            val makeDefault = draft.isDefault || uiState.value.isEmpty
            bikeProfilesRepository.upsert(
                draft.toProfile(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
                    .copy(isDefault = makeDefault)
            )
        }
    }

    /** Saves edits to an existing bike (keeping its id / creation time). */
    fun updateBike(id: String, createdAt: Long, draft: BikeDraft) {
        viewModelScope.launch {
            bikeProfilesRepository.upsert(draft.toProfile(id = id, createdAt = createdAt))
        }
    }

    fun deleteBike(id: String) {
        viewModelScope.launch { bikeProfilesRepository.delete(id) }
    }

    fun setDefault(id: String) {
        viewModelScope.launch { bikeProfilesRepository.setDefault(id) }
    }

    /** Picks the bike to record the next ride with (a quick pre-ride switch). */
    fun setActive(id: String) {
        viewModelScope.launch { bikeProfilesRepository.setActive(id) }
    }

    private fun List<RecordedRideSummary>.toStats(): BikeProfileStats {
        if (isEmpty()) return BikeProfileStats()
        return BikeProfileStats(
            rideCount = size,
            totalDistanceMeters = sumOf { it.distanceMeters },
            totalMovingSeconds = sumOf { it.movingSeconds },
            totalElevationGainMeters = sumOf { it.elevationGainMeters },
            lastRideAt = maxOf { it.startedAt }
        )
    }
}

/**
 * Mutable editor payload for creating / editing a bike, kept separate from the
 * immutable [BikeProfile] domain model. Blank optional fields become `null`.
 */
data class BikeDraft(
    val name: String,
    val brand: String = "",
    val model: String = "",
    val type: BikeType = BikeType.OTHER,
    val tireSize: String = "",
    val weightKg: String = "",
    val color: String = "",
    val modelYear: String = "",
    val notes: String = "",
    val isDefault: Boolean = false
) {
    val isValid: Boolean get() = name.isNotBlank()

    fun toProfile(id: String, createdAt: Long) = BikeProfile(
        id = id,
        name = name.trim(),
        brand = brand.ifBlank { null },
        model = model.ifBlank { null },
        type = type,
        tireSize = tireSize.ifBlank { null },
        weightKg = weightKg.replace(',', '.').toDoubleOrNull(),
        color = color.ifBlank { null },
        modelYear = modelYear.filter { it.isDigit() }.toIntOrNull(),
        notes = notes.ifBlank { null },
        isDefault = isDefault,
        createdAt = createdAt
    )

    companion object {
        fun from(profile: BikeProfile) = BikeDraft(
            name = profile.name,
            brand = profile.brand.orEmpty(),
            model = profile.model.orEmpty(),
            type = profile.type,
            tireSize = profile.tireSize.orEmpty(),
            weightKg = profile.weightKg?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty(),
            color = profile.color.orEmpty(),
            modelYear = profile.modelYear?.toString().orEmpty(),
            notes = profile.notes.orEmpty(),
            isDefault = profile.isDefault
        )
    }
}

