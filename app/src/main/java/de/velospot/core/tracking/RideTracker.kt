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
    /**
     * Shared, pure cumulative-elevation integrator (hysteresis peak/valley). Owns
     * all altitude smoothing, gain/loss, spike-gating and pause continuity so the
     * recorder, GPX import and profile chart agree exactly.
     */
    private val elevation = ElevationAccumulator()

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

    /**
     * Whether the recording is currently **paused** (e.g. the rider is on a train/
     * ferry leg of a commute). While paused, incoming fixes are ignored — they add
     * neither distance, moving time nor track geometry — and the elapsed timer is
     * frozen. The first fix accepted after [resume] begins a **new track segment**
     * ([TrackPoint.segmentStart]), so the paused stretch reads as a gap rather than
     * a straight line drawn across it.
     */
    var isPaused: Boolean = false
        private set

    /** Wall-clock time the current pause started (valid only while [isPaused]). */
    private var pauseStartedAt: Long = 0L
    /** Total time spent in **completed** pauses so far (excluded from elapsed). */
    private var pausedMillis: Long = 0L
    /** Set on [resume]; makes the next accepted fix start a new track segment. */
    private var pendingSegmentBreak: Boolean = false

    /** Total time (ms) spent in **completed** pauses — excluded from elapsed time. */
    val elapsedPausedMillis: Long get() = pausedMillis

    /** The captured points so far (for drawing the live track on the map). */
    val trackPoints: List<TrackPoint> get() = points

    /** Begins a fresh recording, discarding any previous state. */
    fun start(startTimestamp: Long) {
        points.clear()
        startedAt = startTimestamp
        distanceMeters = 0.0
        movingMillis = 0L
        maxSpeedMps = 0.0
        elevation.reset()
        windowLat.clear()
        windowLon.clear()
        lastRawLat = 0.0
        lastRawLon = 0.0
        hasRaw = false
        lastSegSpeedMps = 0.0
        hasSegSpeed = false
        isPaused = false
        pauseStartedAt = 0L
        pausedMillis = 0L
        pendingSegmentBreak = false
        isRecording = true
    }

    /**
     * Pauses the recording. Subsequent fixes are ignored (no distance/time/geometry)
     * and the elapsed timer freezes until [resume]. No-op when not recording or
     * already paused.
     */
    fun pause(now: Long) {
        if (!isRecording || isPaused) return
        isPaused = true
        pauseStartedAt = now
    }

    /**
     * Resumes a paused recording. The paused span is added to [pausedMillis] (so it
     * stays out of the elapsed time) and the next accepted fix starts a fresh track
     * segment, breaking the geometry/speed/altitude continuity so the gap is neither
     * drawn nor counted. No-op when not recording or not paused.
     */
    fun resume(now: Long) {
        if (!isRecording || !isPaused) return
        pausedMillis += (now - pauseStartedAt).coerceAtLeast(0L)
        isPaused = false
        pendingSegmentBreak = true
        // Break the continuity so the stretch across the pause is not measured or
        // drawn: the first resumed fix becomes a fresh anchor with no back-segment.
        hasRaw = false
        hasSegSpeed = false
        windowLat.clear()
        windowLon.clear()
        // Break altitude continuity across the gap without discarding accumulated
        // gain/loss, so the resumed ride doesn't bank a phantom step across it.
        elevation.breakSegment()
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

        // While paused (e.g. on a train/ferry leg), drop every fix: it must add no
        // distance, moving time or geometry, and the stretch across the pause stays
        // a gap. The elapsed timer is frozen by currentStats while paused.
        if (isPaused) return currentStats()

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

        // Whether this segment is a *reliable* baseline (long enough interval that
        // GPS jitter doesn't dominate the division). [segSpeedReliable] gates the
        // acceleration check and the moving/baseline bookkeeping below.
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
        // measurement. Any fix that reaches this line has already survived the
        // accuracy, burst, teleport and acceleration gates above, which reject
        // Doppler/position spikes *as a whole* (the fix is dropped, never reaching
        // here). The acceleration gate in particular is the primary anti-spike
        // defence. We therefore honour the raw Doppler speed of an accepted fix and
        // let it raise the max — this is what makes genuine fast descents (60+ km/h)
        // register instead of being pinned to the noisier position-derived speed.
        // Only two guards remain: a non-negative lower bound and the physical ceiling
        // [MAX_PLAUSIBLE_SPEED_MPS] (a real-descent-safe teleport guard).
        pointSpeed?.let { sp ->
            val spd = sp.toDouble()
            if (spd in 0.0..MAX_PLAUSIBLE_SPEED_MPS) {
                maxSpeedMps = maxOf(maxSpeedMps, spd)
            }
        }

        // Elevation gain/loss. GPS-only altitude is extremely noisy, so all the
        // smoothing, spike-gating and hysteresis integration is delegated to the
        // shared [ElevationAccumulator] (also fed by GPX import and the profile
        // chart). It reads the fix's horizontal accuracy to de-weight poor fixes.
        elevation.add(altitudeMeters, accuracyMeters)

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

        // The first fix accepted after a resume opens a new track segment, so the
        // paused stretch is stored/exported as a gap rather than a connecting line.
        val startsSegment = pendingSegmentBreak
        pendingSegmentBreak = false

        points.add(
            TrackPoint(
                latitude = smoothLat,
                longitude = smoothLon,
                timestamp = timestamp,
                speedMps = pointSpeed,
                altitudeMeters = altitudeMeters,
                accuracyMeters = accuracyMeters,
                segmentStart = startsSegment
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
        // Exclude paused time (completed pauses plus any in-progress one) so the
        // elapsed timer freezes while paused and resumes exactly where it left off.
        val pausedSoFar = pausedMillis + if (isPaused) (now - pauseStartedAt).coerceAtLeast(0L) else 0L
        val elapsedMillis = now - startedAt - pausedSoFar
        val movingSecs = movingMillis / 1000
        val avg = if (movingSecs > 0) distanceMeters / movingSecs else 0.0
        return LiveRideStats(
            elapsedSeconds = (elapsedMillis / 1000).coerceAtLeast(0),
            movingSeconds = movingSecs,
            distanceMeters = distanceMeters,
            currentSpeedMps = if (isPaused) 0f else points.lastOrNull()?.speedMps ?: 0f,
            avgSpeedMps = avg,
            maxSpeedMps = maxSpeedMps,
            elevationGainMeters = elevation.gain,
            elevationLossMeters = elevation.loss,
            pointCount = points.size,
            isPaused = isPaused
        )
    }

    /**
     * Finalises the recording into a [RecordedRide]. Returns `null` when the ride
     * is too short to be worth keeping (fewer than [MIN_POINTS] points or below
     * [MIN_DISTANCE_METERS]). Resets the tracker either way.
     */
    fun stop(endTimestamp: Long): RecordedRide? {
        isRecording = false
        isPaused = false
        if (points.size < MIN_POINTS || distanceMeters < MIN_DISTANCE_METERS) {
            return null
        }
        // Exclude completed pauses (train/ferry legs) from the wall-clock duration.
        val elapsedMillis = (points.last().timestamp - startedAt - pausedMillis).coerceAtLeast(0)
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
            elevationGainMeters = elevation.gain,
            elevationLossMeters = elevation.loss,
            points = points.toList()
        )
    }

    /** Aborts the recording without producing a ride. */
    fun discard() {
        isRecording = false
        isPaused = false
        points.clear()
    }

    companion object {
        /** Below this segment length a fix is treated as standstill jitter. */
        private const val MIN_SEGMENT_METERS = 1.5
        /** Speed above which the rider counts as "moving" (~2.9 km/h). */
        private const val MOVING_SPEED_THRESHOLD_MPS = 0.8
        /**
         * Physical speed ceiling (m/s, ~97 km/h) above which a fix is treated as an
         * implausible teleport. Cycling downhill (road / e-gravel) can legitimately
         * exceed 60 km/h, so this ceiling only guards against physically impossible
         * position jumps, never against real fast descents. Shared as the single
         * source of truth by both the teleport-reject and the max-speed candidate,
         * and read by the historical max-speed backfill in the rides repository.
         */
        internal const val MAX_PLAUSIBLE_SPEED_MPS = 27.0
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

        private const val MIN_POINTS = 2
        private const val MIN_DISTANCE_METERS = 20.0
    }
}





