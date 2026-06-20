package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a user-favourited bike parking space, stored in the dedicated,
 * isolated [de.velospot.data.local.database.FavoritesDatabase].
 *
 * Unlike the legacy [FavoriteParkingSpaceEntity] (which lived in the asset-seeded
 * [de.velospot.data.local.database.BikeParkingDatabase] and carried a foreign key
 * to `bike_parking_spaces`), this entity is **standalone** — favourites only store
 * the referenced OSM parking-space id, so they can live in their own database that
 * a parking-data schema bump (and its destructive migration) can never wipe.
 *
 * Same table name and columns as the legacy entity, so the one-time data copy in
 * `FavoritesDatabase` is a plain `INSERT … SELECT`.
 */
@Entity(tableName = "favorite_parking_spaces")
data class FavoriteSpaceEntity(
    @PrimaryKey
    val parkingSpaceId: String,
    val addedAt: Long = System.currentTimeMillis(),
    val notes: String? = null
)

