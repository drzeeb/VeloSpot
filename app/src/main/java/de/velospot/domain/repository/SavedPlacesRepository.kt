package de.velospot.domain.repository

import de.velospot.domain.model.SavedPlace
import kotlinx.coroutines.flow.Flow

/**
 * Repository for user-saved custom places (manually placed pins saved as named favourites).
 */
interface SavedPlacesRepository {

    /** All saved places as a reactive flow, newest first. */
    fun getSavedPlacesFlow(): Flow<List<SavedPlace>>

    /** Inserts or updates a saved place. */
    suspend fun savePlace(place: SavedPlace)

    /** Removes a saved place by its id. */
    suspend fun removePlace(id: String)
}

