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

    /**
     * Sliding window of the most recent **accepted raw** positions, used to compute
     * the moving-average (smoothed) coordinate that is actually stored and drawn.
     * Smoothing only affects the displayed/stored geometry — distance, speed and
     * moving time are still derived from the raw GPS deltas (see [lastRawLat]/
     * [lastRawLon]) so the totals stay accurate.
     */
    private val windowLat = ArrayDeque<Double>(SMOOTHING_WINDOW)
    private val windowLon = ArrayDeque<Double>(SMOOTHING_WINDOW)
    /** Last **raw** accepted position — the honest basis for distance/speed. */
    private var lastRawLat = 0.0
    private var lastRawLon = 0.0
    private var hasRaw = false

    /**
     * Last **reliable** position-derived segment speed (m/s), used as the baseline
     * for the acceleration-plausibility gate. Only updated from segments whose
     * interval is long enough ([MIN_SPEED_BASELINE_MILLIS]) that the division
     * isn't dominated by GPS jitter.
     */
    private var lastSegSpeedMps = 0.0
    private var hasSegSpeed = false

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
        windowLat.clear()
        windowLon.clear()
        lastRawLat = 0.0
        lastRawLon = 0.0
        hasRaw = false
        lastSegSpeedMps = 0.0
        hasSegSpeed = false
        isRecording = true
    }

    /**
     * Adds a GPS sample and returns the updated [LiveRideStats]. Implausible fixes
     * are **rejected outright** (not appended to the track, not drawn, not counted)
     * so a single drifting fix can no longer add a spike to the polyline or inflate
     * the max speed. A fix is rejected when:
     *  - its reported horizontal accuracy is worse than [MAX_ACCURACY_METERS]
     *    (typical of GPS multipath in urban canyons / 3D building shadow), or
     *  - the implied segment speed from the previous accepted fix exceeds
     *    [MAX_PLAUSIBLE_SPEED_MPS] (a "teleport" outlier).
     *
     * Accepted fixes still go through the sub-metre jitter dead-band so a bike left
     * standing does not silently accumulate distance.
     */
    fun addPoint(
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        speedMps: Float?,
        altitudeMeters: Double?,
        accuracyMeters: Float? = null
    ): LiveRideStats {
        if (!isRecording) return currentStats()

        // Accuracy gate: drop low-quality fixes before they can pollute the track.
        // A fix without a reported accuracy is given the benefit of the doubt.
        if (accuracyMeters != null && accuracyMeters > MAX_ACCURACY_METERS) {
            return currentStats()
        }

        // Burst / duplicate gate: the receiver occasionally emits two fixes only
        // milliseconds apart (or a synthetic same-timestamp fix at ride end). The
        // near-zero interval makes the position-derived speed explode (a few metres
        // over a few ms → hundreds of m/s), so such fixes carry no new information
        // and only inject artefacts (seen as a 290–315 m/s spike on the last point
        // of real recordings). Drop anything arriving faster than the minimum
        // plausible fix interval — including non-monotonic (dt ≤ 0) timestamps.
        val lastFix = points.lastOrNull()
        if (lastFix != null && timestamp - lastFix.timestamp < MIN_FIX_INTERVAL_MILLIS) {
            return currentStats()
        }

        val previous = points.lastOrNull()
        var pointSpeed = speedMps

        // Position-derived speed of this segment and whether it is a *reliable*
        // baseline for cross-checking the reported speed (long enough interval that
        // GPS jitter doesn't dominate the division). Both feed the max-speed gate.
        var segSpeedMps = 0.0
        var segSpeedReliable = false

        if (previous != null && hasRaw) {
            val dtMillis = timestamp - previous.timestamp
            if (dtMillis in 1..MAX_GAP_MILLIS) {
                // Distance/speed are measured between the *raw* fixes (not the
                // smoothed coordinates) so the moving-average never shortens totals.
                val segMeters = GeoMath.distanceMeters(
                    lastRawLat, lastRawLon, latitude, longitude
                )
                val segSpeed = segMeters / (dtMillis / 1000.0)
                // Reject obviously bogus fixes outright (teleport outliers): don't
                // append them, so they can't show up as a spike on the map either.
                if (segSpeed > MAX_PLAUSIBLE_SPEED_MPS) {
                    return currentStats()
                }
                segSpeedReliable = dtMillis >= MIN_SPEED_BASELINE_MILLIS
                // Acceleration-plausibility gate: even a fix whose *absolute* speed
                // is within bounds can be a drift spike if it implies a physically
                // impossible change in speed (e.g. 0 → 40 km/h in 1 s while standing
                // still / under poor reception). We reject such jumps, but only when
                // both this and the previous segment are reliable baselines (long
                // enough interval) so GPS jitter on tiny segments can't trip it.
                if (segSpeedReliable && hasSegSpeed) {
                    val accelMps2 = abs(segSpeed - lastSegSpeedMps) / (dtMillis / 1000.0)
                    if (accelMps2 > MAX_PLAUSIBLE_ACCEL_MPS2) {
                        return currentStats()
                    }
                }
                segSpeedMps = segSpeed
                if (segMeters >= MIN_SEGMENT_METERS) {
                    distanceMeters += segMeters
                }
                if (segSpeed >= MOVING_SPEED_THRESHOLD_MPS) {
                    movingMillis += dtMillis
                }
                if (pointSpeed == null) pointSpeed = segSpeed.toFloat()
                // Remember this segment's speed as the next acceleration baseline,
                // but only when it's a reliable (long-enough) measurement.
                if (segSpeedReliable) {
                    lastSegSpeedMps = segSpeed
                    hasSegSpeed = true
                }
            }
        }

        // Max speed: the device-reported (GPS Doppler) speed is the most direct
        // measurement, but it can briefly **spike** to wildly wrong values (seen at
        // ~50–70 km/h on a bike) while the position barely moved — a sensor glitch,
        // typically on a low-accuracy fix. So a sample may raise the max only when
        // **corroborated by the geometry**: there must be a reliable position-derived
        // baseline and the speed may not exceed it by more than [SPEED_CORROBORATION_FACTOR].
        // This rejects the Doppler spikes while still honouring genuine fast (e.g.
        // downhill) stretches where both the sensor and the track agree.
        pointSpeed?.let { sp ->
            val spd = sp.toDouble()
            if (spd in 0.0..MAX_PLAUSIBLE_SPEED_MPS &&
                segSpeedReliable && spd <= segSpeedMps * SPEED_CORROBORATION_FACTOR
            ) {
                maxSpeedMps = maxOf(maxSpeedMps, spd)
            }
        }

        // Elevation gain/loss. GPS-only altitude is extremely noisy (it can swing
        // ±10 m between fixes even while standing still), so we first low-pass
        // filter it and only register a step once the *smoothed* value has moved
        // by a clear margin — otherwise a parked bike would rack up phantom metres.
        if (altitudeMeters != null) {
            val prevSmoothed = smoothedAltitude
            // Altitude outlier gate: GPS-only altitude can jump ±60 m between two
            // fixes while the bike barely moved (a vertical velocity of ~20 m/s —
            // physically impossible). Feeding such a spike into the low-pass filter
            // still injects a large phantom step (e.g. +60 m × α = +18 m, far past
            // the dead-band), which is what inflated recorded ascents to ~400 m on
            // rides whose real gain was single-digit metres. Skip the altitude
            // accounting for a fix that jumps more than a plausible step from the
            // current smoothed baseline; the raw value is still kept on the point.
            if (prevSmoothed != null &&
                abs(altitudeMeters - prevSmoothed) > MAX_ALTITUDE_STEP_METERS
            ) {
                // Implausible altitude spike — ignore for gain/loss and smoothing.
            } else {
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
        }

        // ── Moving-average position smoothing ────────────────────────────────
        // The fix is accepted: record it as the new raw anchor (the distance/speed
        // basis), then push it into the sliding window and store the window average
        // as the displayed/persisted coordinate. This tames the residual side-to-
        // side jitter of otherwise in-spec fixes (the zig-zag seen even with good
        // accuracy) while the raw anchor keeps the totals honest.
        lastRawLat = latitude
        lastRawLon = longitude
        hasRaw = true
        windowLat.addLast(latitude)
        windowLon.addLast(longitude)
        while (windowLat.size > SMOOTHING_WINDOW) {
            windowLat.removeFirst()
            windowLon.removeFirst()
        }
        val smoothLat = windowLat.average()
        val smoothLon = windowLon.average()

        points.add(
            TrackPoint(
                latitude = smoothLat,
                longitude = smoothLon,
                timestamp = timestamp,
                speedMps = pointSpeed,
                altitudeMeters = altitudeMeters,
                accuracyMeters = accuracyMeters
            )
        )
        return currentStats()
    }

    /**
     * Current live statistics snapshot.
     *
     * @param now wall-clock timestamp used to derive the elapsed time, so the
     *  on-screen timer ticks continuously between GPS fixes. Defaults to the last
     *  accepted fix's timestamp (the behaviour used from [addPoint]).
     */
    fun currentStats(now: Long = points.lastOrNull()?.timestamp ?: startedAt): LiveRideStats {
        val elapsedMillis = now - startedAt
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
        /** Reject fixes implying a faster-than-plausible bike speed (~79 km/h). */
        private const val MAX_PLAUSIBLE_SPEED_MPS = 22.0
        /**
         * Reject fixes implying a physically impossible change of speed between two
         * reliable segments. 4 m/s² is ~0.4 g — still well beyond a cyclist's real
         * sprint or hard braking (real rides measure a 95th-percentile |accel| of
         * ~0.8–1.6 m/s²), so it passes genuine fast descents and stops while catching
         * the abrupt "0 → 40 km/h in 1 s" GPS-drift jumps that stay under the absolute
         * [MAX_PLAUSIBLE_SPEED_MPS] gate.
         */
        private const val MAX_PLAUSIBLE_ACCEL_MPS2 = 4.0
        /**
         * Minimum interval between two fixes for their position-derived speed to be
         * trusted as a *baseline* when validating the reported speed. Below ~1 s the
         * division is dominated by GPS jitter, so such tiny segments are not used to
         * corroborate (or reject) a peak-speed sample.
         */
        private const val MIN_SPEED_BASELINE_MILLIS = 1_000L
        /**
         * Minimum interval below which an incoming fix is discarded as a GPS burst /
         * duplicate. Real fixes arrive at roughly a 1–3 s cadence; two samples only a
         * few milliseconds apart (or with a non-monotonic timestamp) blow up the
         * position-derived speed (hundreds of m/s) and were seen polluting the last
         * point of real rides. Anything faster than this carries no new information.
         */
        private const val MIN_FIX_INTERVAL_MILLIS = 250L
        /**
         * How far the reported (GPS Doppler) speed may exceed the corroborating
         * position-derived speed before it's treated as a sensor spike and ignored
         * for the max-speed statistic. 1.5× tolerates honest Doppler lead on a fix
         * while still discarding the gross 2–5× glitches.
         */
        private const val SPEED_CORROBORATION_FACTOR = 1.5
        /**
         * Reject fixes whose reported horizontal accuracy (1σ radius) is worse than
         * this. GPS multipath in urban canyons / under 3D building shadow — exactly
         * where the drift spikes appear — produces fixes with tens of metres of
         * error; dropping them is the single biggest win against drift. Real rides
         * cluster around ~4 m with a thin 20–28 m multipath tail, so 25 m trims that
         * tail while barely touching the honestly-weak fixes.
         */
        private const val MAX_ACCURACY_METERS = 25.0
        /** Ignore fixes separated by more than this (GPS dropout) for distance. */
        private const val MAX_GAP_MILLIS = 60_000L
        /**
         * Length of the moving-average window applied to the stored/displayed
         * positions. A small window (3) clearly smooths the residual side-to-side
         * jitter of in-spec fixes without adding noticeable lag at the ~3 s GPS
         * cadence. Only affects geometry — distance/speed use the raw fixes.
         */
        private const val SMOOTHING_WINDOW = 3
        /** Exponential smoothing factor applied to the noisy GPS altitude (0..1). */
        private const val ALT_SMOOTHING_ALPHA = 0.3
        /** Minimum *smoothed* altitude change counted as real ascent/descent. */
        private const val ELEVATION_THRESHOLD_METERS = 3.0
        /**
         * Maximum altitude change between a fix and the current smoothed baseline
         * that is still treated as real. GPS-only altitude routinely spikes by
         * 15–60 m between consecutive fixes; anything beyond this is discarded from
         * the elevation accounting (a bike cannot climb/descend ~12 m in one ~3 s
         * fix). Without this gate the ±60 m spikes inflated recorded ascents by an
         * order of magnitude (≈400 m instead of the real ≈5–10 m).
         */
        private const val MAX_ALTITUDE_STEP_METERS = 12.0

        private const val MIN_POINTS = 2
        private const val MIN_DISTANCE_METERS = 20.0
    }
}





