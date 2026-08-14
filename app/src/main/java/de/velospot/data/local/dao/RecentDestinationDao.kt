package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.velospot.data.local.entity.RecentDestinationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for recently-navigated destinations and the pinned
 * Home / Work shortcuts.
 */
@Dao
interface RecentDestinationDao {

    /** The most recent ordinary destinations, newest first. Updates reactively. */
    @Query("SELECT * FROM recent_destinations WHERE kind = 'RECENT' ORDER BY lastUsedAt DESC LIMIT :limit")
    fun recentsFlow(limit: Int): Flow<List<RecentDestinationEntity>>

    /** The pinned Home / Work shortcuts (at most one of each). Updates reactively. */
    @Query("SELECT * FROM recent_destinations WHERE kind IN ('HOME', 'WORK')")
    fun pinnedFlow(): Flow<List<RecentDestinationEntity>>

    @Query("SELECT * FROM recent_destinations WHERE id = :id")
    suspend fun getById(id: String): RecentDestinationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentDestinationEntity)

    /** Demotes any row currently holding [kind] back to an ordinary recent. */
    @Query("UPDATE recent_destinations SET kind = 'RECENT' WHERE kind = :kind")
    suspend fun demoteKind(kind: String)

    @Query("UPDATE recent_destinations SET kind = :kind WHERE id = :id")
    suspend fun setKind(id: String, kind: String)

    @Query("DELETE FROM recent_destinations WHERE id = :id")
    suspend fun delete(id: String)

    /** Every recent/pinned destination (for the local backup export). */
    @Query("SELECT * FROM recent_destinations")
    suspend fun getAll(): List<RecentDestinationEntity>

    /** Clears the whole table (a REPLACE-all restore wipes then re-inserts). */
    @Query("DELETE FROM recent_destinations")
    suspend fun deleteAll()

    /** Trims the ordinary recents to the newest [keep], so the history can't grow unbounded. */
    @Query(
        """
        DELETE FROM recent_destinations
        WHERE kind = 'RECENT' AND id NOT IN (
            SELECT id FROM recent_destinations
            WHERE kind = 'RECENT'
            ORDER BY lastUsedAt DESC
            LIMIT :keep
        )
        """
    )
    suspend fun trimRecents(keep: Int)
}
