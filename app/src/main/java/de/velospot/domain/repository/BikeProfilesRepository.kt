package de.velospot.domain.repository

import de.velospot.domain.model.BikeProfile
import de.velospot.domain.model.BikeServiceReminder
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the rider's bike garage.
 *
 * Owns both the persisted list of bikes ([bikeProfilesFlow]) and the small
 * "active bike" selection ([activeBikeProfileId]) — the bike the next recording
 * will be tagged with. The active selection is an *explicit override*; when it is
 * `null` the [resolveActiveProfileId] fallback uses the default bike.
 */
interface BikeProfilesRepository {

    /** All bikes, oldest first, updating reactively. */
    fun bikeProfilesFlow(): Flow<List<BikeProfile>>

    /**
     * The rider's explicit "ride this bike next" selection, or `null` when they
     * haven't overridden the default. Persisted so a quick pre-ride switch sticks.
     */
    val activeBikeProfileId: Flow<String?>

    /** Inserts or updates a bike. */
    suspend fun upsert(profile: BikeProfile)

    /** Deletes a bike and detaches it from any rides that referenced it. */
    suspend fun delete(id: String)

    /** Makes [id] the one and only default bike. */
    suspend fun setDefault(id: String)

    /** Sets (or clears, with `null`) the active "ride next" bike. */
    suspend fun setActive(id: String?)

    /**
     * The bike a new ride should be tagged with: the explicit active selection if
     * set, otherwise the default bike, otherwise `null`. Evaluated once at save time.
     */
    suspend fun resolveActiveProfileId(): String?

    /**
     * Checks whether tagging a finished ride pushed [bikeId]'s **total** ridden
     * distance past a new shop-service milestone (a multiple of its configured
     * service interval). Returns a [BikeServiceReminder] the first time each
     * milestone is crossed (and persists it so it never fires again), or `null`
     * when service reminders are off / no new milestone was reached.
     */
    suspend fun evaluateServiceDue(bikeId: String): BikeServiceReminder?
}

