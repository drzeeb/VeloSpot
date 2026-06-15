package de.velospot.data.repository

import de.velospot.data.local.dao.SavedPlaceDao
import de.velospot.data.local.entity.SavedPlaceEntity
import de.velospot.domain.model.SavedPlace
import de.velospot.domain.repository.SavedPlacesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [SavedPlacesRepository].
 */
@Singleton
class SavedPlacesRepositoryImpl @Inject constructor(
    private val savedPlaceDao: SavedPlaceDao
) : SavedPlacesRepository {

    override fun getSavedPlacesFlow(): Flow<List<SavedPlace>> =
        savedPlaceDao.getAllFlow().map { entities -> entities.map { it.toDomain() } }

    override suspend fun savePlace(place: SavedPlace) =
        savedPlaceDao.upsert(place.toEntity())

    override suspend fun removePlace(id: String) =
        savedPlaceDao.delete(id)

    private fun SavedPlaceEntity.toDomain() = SavedPlace(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        address = address,
        addedAt = addedAt
    )

    private fun SavedPlace.toEntity() = SavedPlaceEntity(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        address = address,
        addedAt = addedAt
    )
}

