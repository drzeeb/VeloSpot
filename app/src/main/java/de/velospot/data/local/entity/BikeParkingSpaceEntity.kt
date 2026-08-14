package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room database entity for storing bike parking spaces locally.
 * Maps to the "bike_parking_spaces" table in SQLite.
 */
@Entity(
    tableName = "bike_parking_spaces",
    indices = [Index(name = "idx_parking_lat_lon", value = ["latitude", "longitude"])]
)
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
    val lastUpdated: Long = System.currentTimeMillis(),
    // --- Enriched OSM attributes (v5). All nullable so sparsely-tagged OSM nodes
    // degrade gracefully; "unknown" (null) must never be interpreted as "bad".
    val access: String? = null,
    val fee: Boolean? = null,
    val lit: Boolean? = null,
    val surveillance: Boolean? = null,
    val supervised: Boolean? = null,
    val cargoBike: Boolean? = null,
    val cargoBikeCapacity: Int? = null,
    val disabledCapacity: Int? = null,
    val chargingCapacity: Int? = null,
    val indoor: Boolean? = null,
    val maxstay: String? = null,
    val openingHours: String? = null,
    val website: String? = null,
    val network: String? = null,
    val brand: String? = null,
    val ref: String? = null,
    val checkDate: String? = null,
    val parkingSubtype: String? = null
)

