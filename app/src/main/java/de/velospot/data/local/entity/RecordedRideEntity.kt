package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
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
@Entity(
    tableName = "recorded_rides",
    indices = [
        // The timeline always reads newest-first; an index makes that ordering a
        // cheap index scan instead of sorting the whole table on every emission.
        Index(name = "idx_recorded_rides_started_at", value = ["startedAt"]),
        // Archived rides are filtered out of the active timeline.
        Index(name = "idx_recorded_rides_archived_at", value = ["archivedAt"]),
        // Per-bike statistics filter rides by their bike profile.
        Index(name = "idx_recorded_rides_bike_profile_id", value = ["bikeProfileId"])
    ]
)
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
    val name: String? = null,
    /** `true` when recorded via the debug route simulator ("Mock tool"). */
    val isMock: Boolean = false,
    /** Wall-clock time the ride was archived, or `null` while still in the timeline. */
    val archivedAt: Long? = null,
    /** Id of the bike ([BikeProfileEntity]) this ride was recorded with, or `null`. */
    val bikeProfileId: String? = null
)

