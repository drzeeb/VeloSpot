package de.velospot.domain.repository

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.model.RideTrackGeometry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    /**
     * Every recorded ride as a **geometry-only** [RideTrackGeometry] (lat/lon
     * points + `isMock`), newest first — the source for the map heatmap and
     * ridden-tracks overlays. Far lighter than [getRidesWithTracksFlow]: speeds,
     * altitudes and every aggregate column are never parsed or held, and the flow
     * only re-emits when the **track set** changes (a ride added/removed/replaced),
     * not on a rename / archive / bike-reassign. Default returns an empty flow so
     * in-memory test fakes needn't override it.
     */
    fun getRideTrackGeometriesFlow(): Flow<List<RideTrackGeometry>> = flowOf(emptyList())

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

    /**
     * Merges the rides identified by [ids] into a single new ride (id [newId], name
     * [name] or the earliest source's name when blank) and persists it, then
     * **archives** the source rides (reversible) rather than deleting them. Sources
     * are loaded through the chunked [getRides] read path (never `SELECT *` on the
     * heavy `pointsJson`) and stitched by the pure `RideMerger` off the main thread.
     *
     * Returns the saved merged ride, or `null` when the selection can't be merged
     * (fewer than two rides, or a mock/real mix). Default is a no-op returning
     * `null` so in-memory test fakes needn't override it.
     */
    suspend fun mergeRides(ids: List<String>, newId: String, name: String?): RecordedRide? = null

    /**
     * One-off maintenance pass that recomputes each stored ride's cumulative
     * gain/loss from its raw GPS track (with the shared elevation integrator) and
     * writes the corrected aggregate columns back. Fixes historical rides whose
     * denormalised elevation was under-counted by the old accumulator. Idempotent
     * (deterministic recompute); rides without altitude points stay at `0`. Default
     * is a no-op so in-memory test fakes needn't override it.
     */
    suspend fun recomputeStoredElevation() {}

    /**
     * One-off maintenance pass that recomputes each stored ride's `maxSpeedMps`
     * from its per-point Doppler `speedMps` and writes the corrected aggregate
     * column back. Fixes historical rides whose peak speed was understated by the
     * now-removed "corroboration gate" in the recorder (real 60+ km/h descents
     * were pinned to the noisier position-derived speed). The raw stored Doppler
     * speeds are trusted (they already passed the recorder's gates when recorded);
     * the aggregate is simply the max over all points whose `speedMps` is non-null
     * and within `[0, MAX_PLAUSIBLE_SPEED_MPS]`. Rides without any in-range speed
     * sample are left unchanged. Idempotent (deterministic recompute). Default is a
     * no-op so in-memory test fakes needn't override it.
     */
    suspend fun recomputeStoredMaxSpeed() {}

    /** Removes every recorded ride. */
    suspend fun clearAll()
}

