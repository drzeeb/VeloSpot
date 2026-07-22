package de.velospot.domain.model

import androidx.compose.runtime.Immutable

/**
 * A single GPS sample captured along a tracked ride.
 *
 * @property latitude  WGS84 latitude in degrees.
 * @property longitude WGS84 longitude in degrees.
 * @property timestamp Wall-clock time of the fix (`System.currentTimeMillis()`).
 * @property speedMps  Ground speed in m/s at this point (sensor value when
 *  available, otherwise derived from the segment), or `null` when unknown.
 * @property altitudeMeters Altitude in metres when the fix carried one, else `null`.
 * @property accuracyMeters Horizontal accuracy (1σ radius) of the fix in metres
 *  when known, else `null`. Kept so the track can be re-filtered or quality-scored.
 */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val speedMps: Float? = null,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null
)

/**
 * A completed, persisted ride — the full GPS track plus the aggregate statistics
 * shown on the "My rides" timeline.
 *
 * @property name Optional user-facing name for the ride (the navigation
 *  destination, a "Round trip – <place>" label, or a name the rider typed when
 *  finishing a recording). `null`/blank falls back to the date in the UI.
 * @property elapsedSeconds  Wall-clock duration from first to last fix.
 * @property movingSeconds   Time actually spent moving (standstills excluded).
 * @property avgSpeedMps      Average moving speed (distance / moving time).
 * @property maxSpeedMps      Peak speed reached.
 * @property elevationGainMeters Cumulative ascent estimated from altitude deltas.
 * @property elevationLossMeters Cumulative descent estimated from altitude deltas.
 * @property isMock `true` when the ride was recorded via the debug route
 *  simulator ("Mock tool") rather than from real GPS fixes. Such rides are
 *  flagged in the timeline and excluded from the aggregate statistics.
 * @property archivedAt Wall-clock time the ride was archived, or `null` when it
 *  is still in the active timeline. Archived rides are hidden from the main list
 *  but kept in storage so they can be restored.
 */
data class RecordedRide(
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
    val points: List<TrackPoint>,
    val name: String? = null,
    val isMock: Boolean = false,
    val archivedAt: Long? = null,
    /** Id of the [BikeProfile] this ride was recorded with, or `null` if untagged. */
    val bikeProfileId: String? = null,
    /**
     * Id of the saved [PlannedRoute] this ride was recorded while riding, or `null`
     * for a free ride. Set when a planned route is ridden so the detail screen can
     * hide "Save as route" (the route already exists).
     */
    val sourceRouteId: String? = null
) {
    /** Whether this ride is currently archived (hidden from the active timeline). */
    val isArchived: Boolean get() = archivedAt != null
}

/**
 * The lightweight, track-free view of a [RecordedRide]: only the denormalised
 * aggregate statistics needed to render the "My rides" timeline and its history
 * statistics dashboard.
 *
 * The full GPS track ([RecordedRide.points]) is deliberately excluded: it is by
 * far the heaviest part of a ride and is only needed when a single ride is opened
 * (detail sheet, analysis, export) or when a map overlay draws every track. The
 * timeline reads thousands of these without ever deserialising a track, and —
 * because every field is a stable primitive — Compose can skip recomposition of
 * unchanged list rows.
 */
@Immutable
data class RecordedRideSummary(
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
    val name: String? = null,
    val isMock: Boolean = false,
    val archivedAt: Long? = null,
    /** Id of the [BikeProfile] this ride was recorded with, or `null` if untagged. */
    val bikeProfileId: String? = null
) {
    /** Whether this ride is currently archived (hidden from the active timeline). */
    val isArchived: Boolean get() = archivedAt != null
}

/**
 * Live, in-progress statistics emitted by the ride tracker on every GPS fix while
 * a recording is running. Drives the on-map live stats card.
 */
data class LiveRideStats(
    val elapsedSeconds: Long = 0,
    val movingSeconds: Long = 0,
    val distanceMeters: Double = 0.0,
    val currentSpeedMps: Float = 0f,
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val elevationGainMeters: Double = 0.0,
    val elevationLossMeters: Double = 0.0,
    val pointCount: Int = 0
)

