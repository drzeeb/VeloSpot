package de.velospot.domain.repository

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import kotlinx.coroutines.flow.Flow

/**
 * Repository for completed, user-recorded rides (the "My rides" timeline).
 */
interface RecordedRidesRepository {

    /**
     * All recorded rides as lightweight, **track-free** [RecordedRideSummary]s,
     * newest first. This is the reactive source for the timeline and its history
     * statistics: it never deserialises a GPS track, so it stays cheap even with a
     * large ride history and re-emits without jank on every change.
     */
    fun getRideSummariesFlow(): Flow<List<RecordedRideSummary>>

    /**
     * All recorded rides **with** their full GPS tracks, newest first. Heavier
     * (every track is deserialised, off the main thread) — only for consumers that
     * genuinely need the geometry of *every* ride, e.g. the map heatmap / ridden-
     * tracks overlays and the cross-ride analysis context.
     */
    fun getRidesWithTracksFlow(): Flow<List<RecordedRide>>

    /** Loads a single ride **with** its full GPS track, or `null` if it's gone. */
    suspend fun getRide(id: String): RecordedRide?

    /** Loads the full rides (tracks included) for the given [ids] (e.g. for export). */
    suspend fun getRides(ids: List<String>): List<RecordedRide>

    /** Inserts or updates a recorded ride. */
    suspend fun saveRide(ride: RecordedRide)

    /** Renames a ride (or clears its name when [name] is null/blank). */
    suspend fun updateRideName(id: String, name: String?)

    /** Archives a ride (hides it from the active timeline) or restores it. */
    suspend fun setRideArchived(id: String, archived: Boolean)

    /**
     * (Re)assigns a ride to a bike ([bikeProfileId]), or clears the assignment when
     * `null`. Default is a no-op so in-memory test fakes needn't override it.
     */
    suspend fun setRideBikeProfile(id: String, bikeProfileId: String?) {}

    /**
     * Tags a ride with the saved planned route it was recorded while riding, or
     * clears it when `null`. Default is a no-op so in-memory test fakes needn't
     * override it.
     */
    suspend fun setSourceRoute(id: String, routeId: String?) {}

    /**
     * Detaches every ride from [bikeProfileId] (called when its bike is deleted so
     * no ride keeps a dangling reference). Default is a no-op for test fakes.
     */
    suspend fun clearBikeProfileFromRides(bikeProfileId: String) {}

    /**
     * Total ridden distance (metres) tagged to [bikeProfileId] (real rides only).
     * Default returns `0.0` so in-memory test fakes needn't override it.
     */
    suspend fun totalDistanceForBike(bikeProfileId: String): Double = 0.0

    /** Removes a recorded ride by its id. */
    suspend fun removeRide(id: String)

    /** Removes every recorded ride. */
    suspend fun clearAll()
}

