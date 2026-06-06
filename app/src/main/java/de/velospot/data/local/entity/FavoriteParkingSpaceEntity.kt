package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Room database entity for storing user favorite bike parking spaces.
 * Links to bike_parking_spaces via foreign key relationship.
 */
@Entity(
    tableName = "favorite_parking_spaces",
    foreignKeys = [
        ForeignKey(
            entity = BikeParkingSpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["parkingSpaceId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FavoriteParkingSpaceEntity(
    @PrimaryKey
    val parkingSpaceId: String,
    val addedAt: Long = System.currentTimeMillis(),
    val notes: String? = null  // User can add personal notes about this parking space
)

