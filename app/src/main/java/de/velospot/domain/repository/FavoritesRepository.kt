package de.velospot.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing user's favorite bike parking spaces.
 */
interface FavoritesRepository {

    /**
     * Get all favorite parking spaces as a Flow.
     * Automatically updates when favorites change.
     *
     * @return Flow of favorite parking space IDs
     */
    fun getFavoritesFlow(): Flow<List<String>>

    /**
     * Check if a specific parking space is marked as favorite.
     *
     * @param parkingSpaceId The ID of the parking space
     * @return True if the space is favorited, false otherwise
     */
    suspend fun isFavorite(parkingSpaceId: String): Boolean

    /**
     * Add a parking space to favorites.
     *
     * @param parkingSpaceId The ID of the parking space to favorite
     */
    suspend fun addFavorite(parkingSpaceId: String)

    /**
     * Remove a parking space from favorites.
     *
     * @param parkingSpaceId The ID of the parking space to unfavorite
     */
    suspend fun removeFavorite(parkingSpaceId: String)

    /**
     * Atomically toggle a parking space's favourite state: unfavourite it when it
     * is already a favourite, otherwise favourite it. Runs in a single database
     * transaction so rapid double-taps can't race.
     *
     * @param parkingSpaceId The ID of the parking space to toggle
     */
    suspend fun toggleFavorite(parkingSpaceId: String)
}

