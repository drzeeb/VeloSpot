package de.velospot.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.velospot.data.local.dao.BikeProfileDao
import de.velospot.data.local.entity.BikeProfileEntity
import de.velospot.domain.model.BikeProfile
import de.velospot.domain.model.BikeType
import de.velospot.domain.repository.BikeProfilesRepository
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore holding the small "active bike" selection (a single id). */
private val Context.bikeProfileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "velospot_bike_profiles"
)

/**
 * Room- + DataStore-backed [BikeProfilesRepository].
 *
 * The bikes themselves live in Room ([BikeProfileDao]); the tiny, frequently-read
 * "active bike" selection is a single DataStore preference so a pre-ride switch is
 * cheap and reactive. Deleting a bike also detaches it from any rides that
 * referenced it (via [RecordedRidesRepository.clearBikeProfileFromRides]) so no
 * ride keeps a dangling id, and clears the active selection if it pointed at it.
 */
@Singleton
class BikeProfilesRepositoryImpl @Inject constructor(
    private val context: Context,
    private val bikeProfileDao: BikeProfileDao,
    private val recordedRidesRepository: RecordedRidesRepository
) : BikeProfilesRepository {

    override fun bikeProfilesFlow(): Flow<List<BikeProfile>> =
        bikeProfileDao.getAllFlow().map { entities -> entities.map { it.toDomain() } }

    override val activeBikeProfileId: Flow<String?> =
        context.bikeProfileDataStore.data.map { it[KEY_ACTIVE_BIKE] }

    override suspend fun upsert(profile: BikeProfile) {
        bikeProfileDao.upsert(profile.toEntity())
        // Keep the "at most one default" invariant even if the caller flipped the flag.
        if (profile.isDefault) bikeProfileDao.setDefault(profile.id)
    }

    override suspend fun delete(id: String) {
        recordedRidesRepository.clearBikeProfileFromRides(id)
        bikeProfileDao.delete(id)
        if (activeBikeProfileId.first() == id) setActive(null)
    }

    override suspend fun setDefault(id: String) = bikeProfileDao.setDefault(id)

    override suspend fun setActive(id: String?) {
        context.bikeProfileDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_BIKE) else prefs[KEY_ACTIVE_BIKE] = id
        }
    }

    override suspend fun resolveActiveProfileId(): String? =
        activeBikeProfileId.first() ?: bikeProfileDao.getDefaultId()

    private fun BikeProfileEntity.toDomain() = BikeProfile(
        id = id,
        name = name,
        brand = brand,
        model = model,
        type = runCatching { BikeType.valueOf(type) }.getOrDefault(BikeType.OTHER),
        tireSize = tireSize,
        weightKg = weightKg,
        color = color,
        modelYear = modelYear,
        notes = notes,
        isDefault = isDefault,
        createdAt = createdAt
    )

    private fun BikeProfile.toEntity() = BikeProfileEntity(
        id = id,
        name = name.trim(),
        brand = brand?.trim()?.takeIf { it.isNotBlank() },
        model = model?.trim()?.takeIf { it.isNotBlank() },
        type = type.name,
        tireSize = tireSize?.trim()?.takeIf { it.isNotBlank() },
        weightKg = weightKg,
        color = color?.trim()?.takeIf { it.isNotBlank() },
        modelYear = modelYear,
        notes = notes?.trim()?.takeIf { it.isNotBlank() },
        isDefault = isDefault,
        createdAt = createdAt
    )

    private companion object {
        val KEY_ACTIVE_BIKE = stringPreferencesKey("active_bike_profile_id")
    }
}

