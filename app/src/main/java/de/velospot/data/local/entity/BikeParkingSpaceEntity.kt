package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room database entity for storing bike parking spaces locally.
 * Maps to the "bike_parking_spaces" table in SQLite.
 */
@Entity(tableName = "bike_parking_spaces")
data class BikeParkingSpaceEntity(
    @PrimaryKey
    val id: String,
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val capacity: Int?,
    val isCovered: Boolean?,
    val imageUrl: String?,
    val operator: String?,
    val type: String,  // Stored as string, converted from BikeParkingType enum
    val sourceLayer: String,
    val lastUpdated: Long = System.currentTimeMillis()
)

