package de.velospot.core.tracking

import kotlin.math.abs
import kotlin.math.sign

/**
 * A single altitude reading fed to [ElevationAccumulator], with the horizontal
 * accuracy of the underlying GPS fix when known (imported GPX has none → `null`).
 */
data class AltitudeSample(
    val altitudeMeters: Double?,
    val accuracyMeters: Float? = null
)

/** Result of a batch elevation computation. */
data class ElevationResult(
    val gainMeters: Double,
    val lossMeters: Double
)

/**
 * Pure, Android-free cumulative-elevation integrator shared by the live recorder
 * ([RideTracker]), GPX import ([de.velospot.core.gpx.GpxRideFactory]), the profile
 * chart and the one-off backfill. Unit-testable on the JVM.
 *
 * ## Why not the old EMA + dead-band accumulator
 * The previous logic kept a *single moving base* plus a lagging EMA (α = 0.3) and
 * only banked a step when the smoothed value moved ≥ 3 m from the base, resetting
 * the base on every step. That has two fatal flaws:
 *  - on a net-monotonic ride the ±3 m dead-band is only crossed in **one**
 *    direction, so the other direction collapses to exactly `0.0`; and
 *  - dragging the base plus heavy EMA smoothing swallowed intermediate rolls,
 *    grossly under-counting the real gain/loss.
 *
 * ## Algorithm — hysteresis peak/valley integrator
 * Altitude is lightly low-pass filtered (EMA, [ALT_SMOOTHING_ALPHA]) to tame GPS
 * jitter, then integrated with a *reversal hysteresis*:
 *  - `refExtreme` is the altitude of the last **confirmed** turning point;
 *  - `runExtreme` is the most extreme altitude reached since then in the active
 *    direction (`trend`);
 *  - a monotonic run is only **banked** when the series reverses from `runExtreme`
 *    by more than [HYSTERESIS_METERS]. We never reset a base on every threshold
 *    crossing, so intermediate rolls are captured and neither direction can zero
 *    out on a net-monotonic ride.
 *
 * The still-open run is added to [gain]/[loss] on read, so the live values are
 * correct without an explicit `finish()`.
 *
 * Not thread-safe: feed from a single coroutine (like [RideTracker]).
 */
class ElevationAccumulator {

    /** Altitude that has already been committed to gain/loss (closed runs). */
    private var bankedGain = 0.0
    private var bankedLoss = 0.0

    /** Low-pass-filtered altitude; `null` before the first (re)seed. */
    private var smoothed: Double? = null

    /** Altitude of the last confirmed reversal (valley/peak). */
    private var refExtreme = 0.0

    /** Most extreme smoothed altitude reached in the current direction. */
    private var runExtreme = 0.0

    /** +1 climbing, -1 descending, 0 direction not yet established. */
    private var trend = 0

    /** Cumulative ascent including the still-open run. */
    val gain: Double
        get() = bankedGain + if (trend > 0) (runExtreme - refExtreme).coerceAtLeast(0.0) else 0.0

    /** Cumulative descent including the still-open run. */
    val loss: Double
        get() = bankedLoss + if (trend < 0) (refExtreme - runExtreme).coerceAtLeast(0.0) else 0.0

    /**
     * Feeds one altitude reading. A `null` altitude is ignored (the fix carried no
     * height). Fixes with a reported accuracy worse than [MAX_ACCURACY_METERS] are
     * dropped; a `null` accuracy is given the benefit of the doubt (imported GPX).
     */
    fun add(altitudeMeters: Double?, accuracyMeters: Float? = null) {
        if (altitudeMeters == null) return
        // Accuracy gate: drop low-quality fixes; null accuracy passes.
        if (accuracyMeters != null && accuracyMeters > MAX_ACCURACY_METERS) return

        val prev = smoothed
        if (prev == null) {
            seed(altitudeMeters)
            return
        }

        // Spike gate: GPS-only altitude can jump ±15–60 m between fixes while the
        // bike barely moved. Such a sample must not add a phantom step — but it also
        // must not *freeze* the accounting (the old code skipped the fix without
        // advancing the baseline, so one step-change killed all further tracking).
        // We commit the open run, then re-seed the baseline/extremes on the outlier
        // so a persistent step-change resumes immediately and a one-off outlier is
        // re-seeded away on the next good fix. The spike itself contributes nothing.
        if (abs(altitudeMeters - prev) > MAX_ALTITUDE_STEP_METERS) {
            commitPending()
            seed(altitudeMeters)
            return
        }

        val s = prev + ALT_SMOOTHING_ALPHA * (altitudeMeters - prev)
        smoothed = s
        integrate(s)
    }

    /** Feeds a batch of samples in order (GPX import, backfill, profile chart). */
    fun addAll(samples: Iterable<AltitudeSample>) {
        for (sample in samples) add(sample.altitudeMeters, sample.accuracyMeters)
    }

    /**
     * Breaks smoothing/extreme continuity across a pause without discarding the
     * accumulated gain/loss. The open run is committed and the filter is reset, so
     * the first fix after a resume re-seeds cleanly and no phantom step is banked
     * across the gap. Mirrors [RideTracker.resume].
     */
    fun breakSegment() {
        commitPending()
        smoothed = null
    }

    /** Fully resets the accumulator, including the accumulated gain/loss. */
    fun reset() {
        bankedGain = 0.0
        bankedLoss = 0.0
        smoothed = null
        refExtreme = 0.0
        runExtreme = 0.0
        trend = 0
    }

    /** (Re)seeds the filter and extremes on [alt]; starts a fresh, direction-less run. */
    private fun seed(alt: Double) {
        smoothed = alt
        refExtreme = alt
        runExtreme = alt
        trend = 0
    }

    /** Commits the currently open monotonic run into the banked totals. */
    private fun commitPending() {
        when {
            trend > 0 -> bankedGain += (runExtreme - refExtreme).coerceAtLeast(0.0)
            trend < 0 -> bankedLoss += (refExtreme - runExtreme).coerceAtLeast(0.0)
        }
        refExtreme = runExtreme
        trend = 0
    }

    /** Peak/valley hysteresis integration of the smoothed altitude [s]. */
    private fun integrate(s: Double) {
        when {
            trend == 0 -> {
                // Establish a direction only once the move clears the hysteresis, so
                // stationary noise below the threshold never opens a run.
                val delta = s - refExtreme
                if (abs(delta) >= HYSTERESIS_METERS) {
                    trend = sign(delta).toInt()
                    runExtreme = s
                }
            }
            trend > 0 -> {
                if (s > runExtreme) {
                    runExtreme = s // extend the climb
                } else if (runExtreme - s >= HYSTERESIS_METERS) {
                    // Confirmed reversal: bank the whole climb, flip to descending.
                    bankedGain += (runExtreme - refExtreme).coerceAtLeast(0.0)
                    refExtreme = runExtreme
                    trend = -1
                    runExtreme = s
                }
            }
            else -> {
                if (s < runExtreme) {
                    runExtreme = s // extend the descent
                } else if (s - runExtreme >= HYSTERESIS_METERS) {
                    bankedLoss += (refExtreme - runExtreme).coerceAtLeast(0.0)
                    refExtreme = runExtreme
                    trend = 1
                    runExtreme = s
                }
            }
        }
    }

    companion object {
        /**
         * EMA factor for the noisy GPS altitude (0..1). Higher than the old 0.3 so
         * the filter is **less lossy**: at 0.3 the smoothing lagged so far behind
         * that (combined with the 3 m dead-band) it swallowed real rolls. 0.5 keeps
         * the filter responsive to genuine elevation change while still averaging
         * out single-fix jitter.
         */
        const val ALT_SMOOTHING_ALPHA = 0.5

        /**
         * Minimum reversal (from the running extreme) that confirms a turning point
         * and banks the run. Smaller than the old 3 m dead-band because the base is
         * no longer dragged on every crossing, so intermediate rolls are preserved;
         * 2 m still sits above the residual smoothed GPS-altitude noise so a parked
         * bike accumulates nothing.
         */
        const val HYSTERESIS_METERS = 2.0

        /**
         * Horizontal-accuracy ceiling for a fix to feed the elevation accounting.
         * Matches the recorder's track-quality gate; `null` accuracy (imported GPX)
         * is always accepted.
         */
        const val MAX_ACCURACY_METERS = 25.0

        /**
         * Maximum plausible altitude change between a fix and the current smoothed
         * baseline. GPS-only altitude routinely spikes 15–60 m between consecutive
         * fixes; beyond this the sample is treated as an outlier/step-change: it
         * contributes nothing but re-seeds the baseline so accounting never freezes.
         */
        const val MAX_ALTITUDE_STEP_METERS = 12.0

        /** Batch convenience: gain/loss over a whole track in one pass. */
        fun compute(samples: Iterable<AltitudeSample>): ElevationResult {
            val acc = ElevationAccumulator()
            acc.addAll(samples)
            return ElevationResult(acc.gain, acc.loss)
        }
    }
}

