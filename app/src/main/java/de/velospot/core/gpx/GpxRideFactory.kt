package de.velospot.core.gpx

import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import java.util.UUID
import kotlin.math.abs

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
    private const val MAX_PLAUSIBLE_SPEED_MPS = 35.0
    private const val ALT_SMOOTHING_ALPHA = 0.3
    private const val ELEVATION_THRESHOLD_METERS = 3.0
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
        var elevationGain = 0.0
        var elevationLoss = 0.0
        var smoothedAlt: Double? = null
        var lastRegisteredAlt: Double? = null

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

            // Smoothed elevation gain/loss.
            p.elevationMeters?.let { alt ->
                val prevSmoothed = smoothedAlt
                val smoothed = if (prevSmoothed == null) alt
                               else prevSmoothed + ALT_SMOOTHING_ALPHA * (alt - prevSmoothed)
                smoothedAlt = smoothed
                val baseAlt = lastRegisteredAlt
                if (baseAlt == null) {
                    lastRegisteredAlt = smoothed
                } else {
                    val delta = smoothed - baseAlt
                    if (abs(delta) >= ELEVATION_THRESHOLD_METERS) {
                        if (delta > 0) elevationGain += delta else elevationLoss += -delta
                        lastRegisteredAlt = smoothed
                    }
                }
            }

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
            elevationGainMeters = elevationGain,
            elevationLossMeters = elevationLoss,
            points = points.toList(),
            name = name?.trim()?.takeIf { it.isNotBlank() }
        )
    }
}

