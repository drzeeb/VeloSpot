package de.velospot.data.repository

import de.velospot.data.local.dao.FavoriteParkingSpaceDao
import de.velospot.data.local.entity.FavoriteParkingSpaceEntity
import de.velospot.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of FavoritesRepository using Room database.
 * Manages user's favorite bike parking spaces.
 */
@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val favoritesDao: FavoriteParkingSpaceDao
) : FavoritesRepository {

    override fun getFavoritesFlow(): Flow<List<String>> {
        return favoritesDao.getFavoritesFlow().map { favorites ->
            favorites.map { it.parkingSpaceId }
        }
    }

    override suspend fun isFavorite(parkingSpaceId: String): Boolean {
        return favoritesDao.isFavorite(parkingSpaceId)
    }

    override suspend fun addFavorite(parkingSpaceId: String) {
        favoritesDao.addFavorite(
            FavoriteParkingSpaceEntity(parkingSpaceId = parkingSpaceId)
        )
    }

    override suspend fun removeFavorite(parkingSpaceId: String) {
        favoritesDao.removeFavorite(parkingSpaceId)
    }

    override suspend fun getFavoritesCount(): Int {
        return favoritesDao.getFavoritesCount()
    }
}

