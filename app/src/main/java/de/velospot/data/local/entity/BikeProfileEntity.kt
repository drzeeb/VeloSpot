package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a bike in the rider's garage.
 *
 * Lives in the same [de.velospot.data.local.database.RidesDatabase] as the
 * recorded rides so a ride can reference its bike ([RecordedRideEntity.bikeProfileId])
 * within one store, keeping per-bike statistics a cheap single-database query.
 *
 * The [type] is persisted as the [de.velospot.domain.model.BikeType] enum name so
 * new disciplines can be added without a migration.
 */
@Entity(
    tableName = "bike_profiles",
    indices = [
        // The garage is ordered oldest-first (creation order); indexing keeps that
        // a cheap scan. `isDefault` is filtered when resolving the default bike.
        Index(name = "idx_bike_profiles_created_at", value = ["createdAt"]),
        Index(name = "idx_bike_profiles_is_default", value = ["isDefault"])
    ]
)
data class BikeProfileEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val brand: String? = null,
    val model: String? = null,
    /** [de.velospot.domain.model.BikeType] name. */
    val type: String,
    val tireSize: String? = null,
    val weightKg: Double? = null,
    val color: String? = null,
    val modelYear: Int? = null,
    val notes: String? = null,
    val isDefault: Boolean = false,
    val createdAt: Long,
    /** Km between shop services, or `null`/`0` when reminders are off. */
    val serviceIntervalKm: Int? = null,
    /** Highest service milestone (km) the rider was already notified about. */
    val lastServiceNotifiedKm: Int = 0
)

