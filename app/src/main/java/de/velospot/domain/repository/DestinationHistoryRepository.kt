package de.velospot.domain.repository

import de.velospot.domain.model.DestinationKind
import de.velospot.domain.model.RecentDestination
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the rider's recently-navigated destinations and the pinned
 * Home / Work quick shortcuts.
 */
interface DestinationHistoryRepository {

    /** The [limit] most recent ordinary destinations, newest first, as a reactive flow. */
    fun recentDestinations(limit: Int): Flow<List<RecentDestination>>

    /** The pinned Home / Work shortcuts (at most one of each) as a reactive flow. */
    fun pinnedDestinations(): Flow<List<RecentDestination>>

    /**
     * Records a navigation to a destination: inserts it (or refreshes an existing
     * entry at the same coordinate) and bumps its last-used time. A destination that
     * is already pinned as Home / Work keeps that status.
     */
    suspend fun record(name: String, latitude: Double, longitude: Double, address: String?)

    /**
     * Pins the destination [id] as [kind] (HOME or WORK), demoting whatever was
     * previously pinned to that slot back to an ordinary recent.
     */
    suspend fun pin(id: String, kind: DestinationKind)

    /** Removes a destination (a recent or a pinned shortcut) entirely. */
    suspend fun remove(id: String)
}

