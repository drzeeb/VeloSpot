package de.velospot.core.tracking

/**
 * Debounced movement / standstill classifier for the ride recorder.
 *
 * Feeds a single boolean — *is the rider standing still?* — to the
 * [de.velospot.core.location.LocationController] so the GPS radio can be dropped to
 * a power-saving cadence while the rider is stopped (traffic light, café, ferry /
 * train leg, or an explicitly paused recording) and restored to full fidelity the
 * instant they move again. This is the battery win for long tours.
 *
 * **Hysteresis (asymmetric on purpose):**
 *  - **Enter-stationary is slow.** The rider is only considered stationary after a
 *    *sustained* [dwellMillis] window of ground speed at or below
 *    [speedThresholdMps]. This prevents flapping at low speed / GPS jitter — a
 *    single near-zero fix while creeping through traffic must not idle the GPS.
 *  - **Exit-stationary is immediate.** The very first fix above the threshold flips
 *    back to *moving* at once, so track fidelity is restored the moment the rider
 *    pulls away — no fixes are lost re-arming high accuracy.
 *
 * Deliberately free of any Android dependency so the timing logic is deterministic
 * and JVM-unit-testable ((speed, timestamp) in → moving/stationary out).
 *
 * Not thread-safe: drive it from the single recorder feed like [RideTracker].
 */
class StandstillDetector(
    private val speedThresholdMps: Float = MOVING_SPEED_THRESHOLD_MPS,
    private val dwellMillis: Long = STATIONARY_DWELL_MILLIS,
) {
    private var stationary = false
    /** Timestamp of the first low-speed fix of the current low-speed streak, or null. */
    private var lowSpeedSince: Long? = null
    /** While `true` (recording paused) the rider is treated as stationary outright. */
    private var paused = false

    /** Whether the rider is currently classified as standing still (idle-GPS). */
    val isStationary: Boolean get() = stationary

    /**
     * Feeds one GPS fix and returns whether the rider is now classified as
     * **stationary** (`true`) or **moving** (`false`).
     *
     * @param speedMps ground speed of the fix in metres per second (>= 0).
     * @param timestampMillis monotonic-ish wall-clock time of the fix, used to
     *  measure the sustained low-speed dwell.
     */
    fun onFix(speedMps: Float, timestampMillis: Long): Boolean {
        // A paused recording is held stationary regardless of incoming fixes; the
        // paused tracker discards them anyway, so idle the GPS for the battery.
        if (paused) {
            lowSpeedSince = null
            stationary = true
            return true
        }
        if (speedMps > speedThresholdMps) {
            // Moving: exit standstill immediately (asymmetric hysteresis).
            lowSpeedSince = null
            stationary = false
        } else {
            // Low speed: start / continue the dwell window and only flip to
            // stationary once it has been sustained for the full [dwellMillis].
            val since = lowSpeedSince ?: timestampMillis.also { lowSpeedSince = it }
            if (!stationary && timestampMillis - since >= dwellMillis) {
                stationary = true
            }
        }
        return stationary
    }

    /**
     * Reflects the recorder's pause state. A paused recording is treated as
     * stationary (power-save) at once; resuming clears the standstill so the rider
     * pulling away is served full-accuracy fixes immediately (the dwell then
     * re-arms from the next low-speed fix). Returns the resulting classification.
     */
    fun setPaused(paused: Boolean): Boolean {
        this.paused = paused
        lowSpeedSince = null
        stationary = paused
        return stationary
    }

    /** Resets to the moving/not-paused state for a fresh recording. */
    fun reset() {
        stationary = false
        lowSpeedSince = null
        paused = false
    }

    companion object {
        /**
         * Ground speed (m/s) at or below which a fix counts as "not moving".
         * ~0.5 m/s ≈ 1.8 km/h — below a slow walking pace, so it captures a stopped
         * bike (and its GPS jitter) without tripping while creeping in traffic.
         */
        const val MOVING_SPEED_THRESHOLD_MPS = 0.5f

        /**
         * Sustained low-speed duration (ms) before the GPS is idled. ~60 s is long
         * enough to ride out a traffic light or a brief queue without churning the
         * GNSS power state, while still saving substantial battery across the longer
         * café / ferry / train stops of a full-day tour.
         */
        const val STATIONARY_DWELL_MILLIS = 60_000L
    }
}

