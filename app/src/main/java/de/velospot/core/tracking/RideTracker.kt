package de.velospot.core.tracking

import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.LiveRideStats
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import java.util.UUID
import kotlin.math.abs

/**
 * Accumulates GPS fixes during an active ride recording and derives the live and
 * final statistics (distance, duration, moving time, speeds, elevation).
 *
 * Deliberately free of any Android / MapLibre dependency (reuses the pure
 * [GeoMath]) so the whole accumulation logic can be unit-tested on the JVM.
 *
 * Not thread-safe: feed fixes from a single coroutine (the ViewModel scope).
 */
class RideTracker {

    private val points = ArrayList<TrackPoint>()
    private var startedAt: Long = 0L
    private var distanceMeters = 0.0
    private var movingMillis = 0L
    private var maxSpeedMps = 0.0
    private var elevationGain = 0.0
    private var elevationLoss = 0.0
    /** Low-pass-filtered altitude, to tame the heavy noise of GPS-only altitude. */
    private var smoothedAltitude: Double? = null
    /** Smoothed altitude at the last point where a gain/loss step was recorded. */
    private var lastRegisteredAltitude: Double? = null

    /** Whether a recording is currently in progress. */
    var isRecording: Boolean = false
        private set

    /** The captured points so far (for drawing the live track on the map). */
    val trackPoints: List<TrackPoint> get() = points

    /** Begins a fresh recording, discarding any previous state. */
    fun start(startTimestamp: Long) {
        points.clear()
        startedAt = startTimestamp
        distanceMeters = 0.0
        movingMillis = 0L
        maxSpeedMps = 0.0
        elevationGain = 0.0
        elevationLoss = 0.0
        smoothedAltitude = null
        lastRegisteredAltitude = null
        isRecording = true
    }

    /**
     * Adds a GPS sample and returns the updated [LiveRideStats]. Filters out
     * implausible jumps (teleporting fixes) and sub-metre jitter so a bike left
     * standing does not silently accumulate distance.
     */
    fun addPoint(
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        speedMps: Float?,
        altitudeMeters: Double?
    ): LiveRideStats {
        if (!isRecording) return currentStats()

        val previous = points.lastOrNull()
        var pointSpeed = speedMps

        if (previous != null) {
            val dtMillis = timestamp - previous.timestamp
            if (dtMillis in 1..MAX_GAP_MILLIS) {
                val segMeters = GeoMath.distanceMeters(
                    previous.latitude, previous.longitude, latitude, longitude
                )
                val segSpeed = segMeters / (dtMillis / 1000.0)
                // Reject obviously bogus fixes (e.g. > 30 m/s ≈ 108 km/h for a bike).
                if (segSpeed <= MAX_PLAUSIBLE_SPEED_MPS) {
                    if (segMeters >= MIN_SEGMENT_METERS) {
                        distanceMeters += segMeters
                    }
                    if (segSpeed >= MOVING_SPEED_THRESHOLD_MPS) {
                        movingMillis += dtMillis
                    }
                    if (pointSpeed == null) pointSpeed = segSpeed.toFloat()
                }
            }
        }

        pointSpeed?.let { sp ->
            if (sp <= MAX_PLAUSIBLE_SPEED_MPS) maxSpeedMps = maxOf(maxSpeedMps, sp.toDouble())
        }

        // Elevation gain/loss. GPS-only altitude is extremely noisy (it can swing
        // ±10 m between fixes even while standing still), so we first low-pass
        // filter it and only register a step once the *smoothed* value has moved
        // by a clear margin — otherwise a parked bike would rack up phantom metres.
        if (altitudeMeters != null) {
            val prevSmoothed = smoothedAltitude
            val smoothed = if (prevSmoothed == null) altitudeMeters
                           else prevSmoothed + ALT_SMOOTHING_ALPHA * (altitudeMeters - prevSmoothed)
            smoothedAltitude = smoothed
            val base = lastRegisteredAltitude
            if (base == null) {
                lastRegisteredAltitude = smoothed
            } else {
                val delta = smoothed - base
                if (abs(delta) >= ELEVATION_THRESHOLD_METERS) {
                    if (delta > 0) elevationGain += delta else elevationLoss += -delta
                    lastRegisteredAltitude = smoothed
                }
            }
        }

        points.add(
            TrackPoint(
                latitude = latitude,
                longitude = longitude,
                timestamp = timestamp,
                speedMps = pointSpeed,
                altitudeMeters = altitudeMeters
            )
        )
        return currentStats()
    }

    /** Current live statistics snapshot. */
    fun currentStats(): LiveRideStats {
        val elapsedMillis = (points.lastOrNull()?.timestamp ?: startedAt) - startedAt
        val movingSecs = movingMillis / 1000
        val avg = if (movingSecs > 0) distanceMeters / movingSecs else 0.0
        return LiveRideStats(
            elapsedSeconds = (elapsedMillis / 1000).coerceAtLeast(0),
            movingSeconds = movingSecs,
            distanceMeters = distanceMeters,
            currentSpeedMps = points.lastOrNull()?.speedMps ?: 0f,
            avgSpeedMps = avg,
            maxSpeedMps = maxSpeedMps,
            elevationGainMeters = elevationGain,
            elevationLossMeters = elevationLoss,
            pointCount = points.size
        )
    }

    /**
     * Finalises the recording into a [RecordedRide]. Returns `null` when the ride
     * is too short to be worth keeping (fewer than [MIN_POINTS] points or below
     * [MIN_DISTANCE_METERS]). Resets the tracker either way.
     */
    fun stop(endTimestamp: Long): RecordedRide? {
        isRecording = false
        if (points.size < MIN_POINTS || distanceMeters < MIN_DISTANCE_METERS) {
            return null
        }
        val elapsedMillis = (points.last().timestamp - startedAt).coerceAtLeast(0)
        val movingSecs = movingMillis / 1000
        val avg = if (movingSecs > 0) distanceMeters / movingSecs else 0.0
        return RecordedRide(
            id = UUID.randomUUID().toString(),
            startedAt = startedAt,
            endedAt = endTimestamp,
            distanceMeters = distanceMeters,
            elapsedSeconds = (elapsedMillis / 1000),
            movingSeconds = movingSecs,
            avgSpeedMps = avg,
            maxSpeedMps = maxSpeedMps,
            elevationGainMeters = elevationGain,
            elevationLossMeters = elevationLoss,
            points = points.toList()
        )
    }

    /** Aborts the recording without producing a ride. */
    fun discard() {
        isRecording = false
        points.clear()
    }

    companion object {
        /** Below this segment length a fix is treated as standstill jitter. */
        private const val MIN_SEGMENT_METERS = 1.5
        /** Speed above which the rider counts as "moving" (~2.9 km/h). */
        private const val MOVING_SPEED_THRESHOLD_MPS = 0.8
        /** Reject fixes implying a faster-than-plausible bike speed (~108 km/h). */
        private const val MAX_PLAUSIBLE_SPEED_MPS = 30.0
        /** Ignore fixes separated by more than this (GPS dropout) for distance. */
        private const val MAX_GAP_MILLIS = 60_000L
        /** Exponential smoothing factor applied to the noisy GPS altitude (0..1). */
        private const val ALT_SMOOTHING_ALPHA = 0.3
        /** Minimum *smoothed* altitude change counted as real ascent/descent. */
        private const val ELEVATION_THRESHOLD_METERS = 3.0

        private const val MIN_POINTS = 2
        private const val MIN_DISTANCE_METERS = 20.0
    }
}





