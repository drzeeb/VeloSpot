package de.velospot.domain.model

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
    val archivedAt: Long? = null
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

