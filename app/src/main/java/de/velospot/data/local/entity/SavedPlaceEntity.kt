package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a user-saved custom place (a manually placed map pin saved as
 * a named favourite). Stored in a dedicated database, independent of the
 * asset-seeded bike parking database.
 */
@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val addedAt: Long
)

