package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a single leaderboard attempt (one completed ride of a planned
 * route). Indexed by `routeId` + `reversed` so a route's forward / reverse
 * leaderboards load quickly.
 */
@Entity(
    tableName = "route_attempts",
    indices = [Index(value = ["routeId", "reversed"])]
)
data class RouteAttemptEntity(
    @PrimaryKey
    val id: String,
    val routeId: String,
    /** `false` = ridden in the route's stored (forward) order, `true` = reversed. */
    val reversed: Boolean,
    val recordedAt: Long,
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val distanceMeters: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainMeters: Double,
    val rideId: String?
)

