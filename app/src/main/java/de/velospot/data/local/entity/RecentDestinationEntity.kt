package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a recently-navigated destination (and the pinned Home / Work
 * shortcuts). Stored in a dedicated database, independent of the asset-seeded
 * parking database and the other user stores.
 *
 * [kind] holds the [de.velospot.domain.model.DestinationKind] name
 * (`RECENT` / `HOME` / `WORK`); [id] is a rounded-coordinate key so navigating to
 * the same place again updates the existing row instead of piling up duplicates.
 */
@Entity(tableName = "recent_destinations")
data class RecentDestinationEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val lastUsedAt: Long,
    val kind: String
)

