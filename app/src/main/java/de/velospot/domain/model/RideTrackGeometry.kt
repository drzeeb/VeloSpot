package de.velospot.domain.model

/**
 * Geometry-only projection of a recorded ride for the map overlays (the heatmap
 * and the "ridden tracks" layer).
 *
 * The overlays only ever draw a ride's **shape**: the heatmap counts grid cells
 * from the coordinates and the ridden-tracks layer simplifies them into a thin
 * polyline. Neither reads speed, altitude, timestamps or any aggregate statistic.
 * Loading full [RecordedRide]s for every ride (all raw per-second samples with
 * their boxed speed/altitude/accuracy fields, held in memory in a `StateFlow`)
 * is therefore wasteful. This holds just the ordered [points] (latitude/longitude
 * only) plus [isMock], so mock (route-simulator) rides can still be excluded.
 *
 * @property isMock `true` when the ride was recorded via the debug route
 *  simulator; such rides are filtered out of the overlays.
 * @property points The ride's GPS track as lat/lon-only [TrackPoint]s (all other
 *  fields left at their defaults — they are never parsed for the overlays).
 */
data class RideTrackGeometry(
    val isMock: Boolean,
    val points: List<TrackPoint>
)

