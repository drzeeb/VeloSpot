package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a completed, recorded ride.
 *
 * Stored in a dedicated database independent of the asset-seeded parking
 * database, so its schema can evolve without ever touching user parking data.
 * The full GPS track is persisted as a compact JSON array in [pointsJson]; the
 * aggregate statistics are denormalised into columns so the history list can be
 * rendered without parsing every track.
 */
@Entity(tableName = "recorded_rides")
data class RecordedRideEntity(
    @PrimaryKey
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val distanceMeters: Double,
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    /** JSON-serialised `List<TrackPoint>`. */
    val pointsJson: String,
    /** Optional user-facing name (destination / round-trip place / typed name). */
    val name: String? = null
)

