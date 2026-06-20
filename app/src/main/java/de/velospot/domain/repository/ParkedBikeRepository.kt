package de.velospot.domain.repository

import de.velospot.domain.model.ParkedBike
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the user's parked-bike location.
 *
 * Holds at most a single [ParkedBike] at a time, persisted across app restarts
 * until the user picks the bike up again ([clear]).
 */
interface ParkedBikeRepository {

    /** The currently parked bike (or `null` when none is parked) as a reactive flow. */
    fun getParkedBikeFlow(): Flow<ParkedBike?>

    /** Stores [bike] as the current parked-bike location, replacing any previous one. */
    suspend fun park(bike: ParkedBike)

    /** Clears the parked-bike location (the user collected their bike). */
    suspend fun clear()
}

