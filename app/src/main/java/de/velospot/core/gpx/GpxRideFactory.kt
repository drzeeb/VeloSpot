package de.velospot.core.gpx

import de.velospot.core.navigation.GeoMath
import de.velospot.core.tracking.ElevationAccumulator
import de.velospot.core.tracking.RideTracker
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import java.util.UUID

/**
 * Turns a [ParsedTrack] (read from an imported GPX) into a [RecordedRide],
 * deriving the aggregate statistics from the track geometry and — when the GPX
 * carries `<time>`s — its timing.
 *
 * Deliberately **lenient** (unlike the live [de.velospot.core.tracking.RideTracker],
 * which aggressively gates noisy live GPS): imported tracks have no accuracy data
 * and may be sparsely time-stamped, so points are taken as-is. Pure and unit-testable.
 */
object GpxRideFactory {

    private const val MOVING_SPEED_THRESHOLD_MPS = 0.8

    /**
     * Physical plausibility ceiling for a GPX segment speed, shared with the live
     * [RideTracker] so both import and recording reject the same implausible
     * "teleport" outliers (a cycling ride faster than this is treated as a bad
     * timestamp/coordinate). GPX segment speed is geometry-derived, so this only
     * discards a garbage segment's contribution to the max/moving stats.
     */
    private val MAX_PLAUSIBLE_SPEED_MPS = RideTracker.MAX_PLAUSIBLE_SPEED_MPS
    private const val MIN_POINTS = 2
    private const val MIN_DISTANCE_METERS = 10.0

    /**
     * Builds a [RecordedRide] from [track], or `null` when it is too short to keep.
     * [name] overrides the track's own name when provided.
     */
    fun toRecordedRide(track: ParsedTrack, name: String? = track.name): RecordedRide? {
        val raw = track.points
        if (raw.size < MIN_POINTS) return null

        val hasTimes = raw.all { it.timestampMillis != null }
        val base = raw.firstOrNull { it.timestampMillis != null }?.timestampMillis
            ?: System.currentTimeMillis()

        var distance = 0.0
        var movingMillis = 0L
        var maxSpeed = 0.0
        // Cumulative gain/loss via the shared hysteresis integrator (imported GPX
        // carries no accuracy, so every sample is trusted).
        val elevation = ElevationAccumulator()

        val points = ArrayList<TrackPoint>(raw.size)
        var prev: ParsedTrackPoint? = null

        for (p in raw) {
            var segSpeed: Float? = null
            if (prev != null) {
                val segMeters = GeoMath.distanceMeters(prev.latitude, prev.longitude, p.latitude, p.longitude)
                distance += segMeters
                if (hasTimes) {
                    val dt = (p.timestampMillis ?: 0L) - (prev.timestampMillis ?: 0L)
                    if (dt in 1..600_000) {
                        val spd = segMeters / (dt / 1000.0)
                        if (spd in 0.0..MAX_PLAUSIBLE_SPEED_MPS) {
                            segSpeed = spd.toFloat()
                            maxSpeed = maxOf(maxSpeed, spd)
                            if (spd >= MOVING_SPEED_THRESHOLD_MPS) movingMillis += dt
                        }
                    }
                }
            }

            elevation.add(p.elevationMeters, accuracyMeters = null)

            points.add(
                TrackPoint(
                    latitude = p.latitude,
                    longitude = p.longitude,
                    timestamp = p.timestampMillis ?: base,
                    speedMps = segSpeed,
                    altitudeMeters = p.elevationMeters,
                    accuracyMeters = null
                )
            )
            prev = p
        }

        if (distance < MIN_DISTANCE_METERS) return null

        val startedAt = points.first().timestamp
        val endedAt = points.last().timestamp
        val elapsedSeconds = ((endedAt - startedAt) / 1000).coerceAtLeast(0)
        val movingSeconds = movingMillis / 1000
        val avgSpeed = if (movingSeconds > 0) distance / movingSeconds else 0.0

        return RecordedRide(
            id = UUID.randomUUID().toString(),
            startedAt = startedAt,
            endedAt = endedAt,
            distanceMeters = distance,
            elapsedSeconds = elapsedSeconds,
            movingSeconds = movingSeconds,
            avgSpeedMps = avgSpeed,
            maxSpeedMps = maxSpeed,
            elevationGainMeters = elevation.gain,
            elevationLossMeters = elevation.loss,
            points = points.toList(),
            name = name?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Maps **every** `<trk>` of an imported GPX to its own [RecordedRide], dropping
     * the tracks too short to keep (see [toRecordedRide]). A multi-track GPX therefore
     * yields one ride per track — both the direct-import and the preview paths use
     * this, so opening a multi-track file imports all of its tracks (and the preview
     * shows the first while keeping the rest ready to import). Pure and unit-testable.
     */
    fun toRecordedRides(tracks: List<ParsedTrack>): List<RecordedRide> =
        tracks.mapNotNull { toRecordedRide(it) }
}

