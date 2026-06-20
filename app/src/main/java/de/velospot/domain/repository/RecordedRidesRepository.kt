package de.velospot.domain.repository

import de.velospot.domain.model.RecordedRide
import kotlinx.coroutines.flow.Flow

/**
 * Repository for completed, user-recorded rides (the "My rides" timeline).
 */
interface RecordedRidesRepository {

    /** All recorded rides as a reactive flow, newest first. */
    fun getRidesFlow(): Flow<List<RecordedRide>>

    /** Inserts or updates a recorded ride. */
    suspend fun saveRide(ride: RecordedRide)

    /** Removes a recorded ride by its id. */
    suspend fun removeRide(id: String)

    /** Removes every recorded ride. */
    suspend fun clearAll()
}

