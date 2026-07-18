package de.velospot.core.analysis

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RouteAttempt
import java.util.UUID

/**
 * A ranked leaderboard row: an [attempt] together with its 1-based [rank] and a
 * flag marking the single fastest attempt as the personal best.
 */
data class LeaderboardEntry(
    val rank: Int,
    val attempt: RouteAttempt,
    val isBest: Boolean
)

/**
 * Per-direction leaderboard statistics for a route (forward *or* reverse), shown
 * on the pre-ride preview.
 *
 * @property best the fastest attempt in this direction, or `null` when never
 *  ridden this way.
 * @property averageSeconds mean elapsed time over all attempts, or `null`.
 * @property medianSeconds median elapsed time (robust to one outlier run), or `null`.
 * @property attemptCount how many times the route was ridden in this direction.
 * @property trend elapsed times **oldest → newest**, for an improvement sparkline
 *  (empty when there are fewer than two attempts).
 */
data class RouteDirectionStats(
    val reversed: Boolean,
    val best: RouteAttempt?,
    val averageSeconds: Long?,
    val medianSeconds: Long?,
    val attemptCount: Int,
    val trend: List<Long>
) {
    /** `true` when the route has at least one attempt in this direction. */
    val hasAttempts: Boolean get() = attemptCount > 0

    /**
     * How much faster the personal best is than the average (seconds), or `null`
     * when there are too few attempts / no gap. Positive means the best beats the
     * average — the "2:15 faster than your average" figure.
     */
    val bestVsAverageSeconds: Long?
        get() = if (best != null && averageSeconds != null) {
            (averageSeconds - best.elapsedSeconds).takeIf { it > 0 }
        } else null

    /** `true` when the most recent time is faster than the oldest (trending better). */
    val isImproving: Boolean get() = trend.size >= 2 && trend.last() < trend.first()
}

/**
 * A compact, at-a-glance digest of a route's leaderboard, shown on the route
 * **preview** (before riding) so the rider can see how they've done so far —
 * split into the [forward] and [reverse] directions (reversing a route swaps its
 * climbs, so the two aren't comparable).
 */
data class RouteLeaderboardSummary(
    val forward: RouteDirectionStats,
    val reverse: RouteDirectionStats,
    val lastRiddenAt: Long?
) {
    /** Total recorded attempts across both directions. */
    val totalAttempts: Int get() = forward.attemptCount + reverse.attemptCount

    /** `true` when the route has at least one recorded attempt. */
    val hasAttempts: Boolean get() = totalAttempts > 0
}

/**
 * Pure, side-effect-free helpers powering a planned route's leaderboard.
 *
 * VeloSpot has no accounts or cloud, so a route's leaderboard is the **rider's
 * own** attempts ranked against each other (a personal best list). Forward and
 * reverse rides are ranked separately because reversing a route swaps its climbs
 * and descents, which changes the achievable time — mixing both directions would
 * be unfair.
 *
 * ## What is ranked besides time?
 * The primary ranking key is **elapsed time** (fastest wins) — that is what a
 * leaderboard is about. Everything else the geometry fixes per route (distance,
 * total ascent/descent are constant for a given direction), so those are shown as
 * context rather than ranked. The genuinely useful *secondary* figures kept per
 * attempt and surfaced next to the time are:
 *  - **average speed** (essentially the inverse of time, but the natural unit
 *    riders compare),
 *  - **moving time** (time excluding stops at lights — rewards a clean run),
 *  - **max speed** and **date**, plus how **often** the route has been ridden.
 */
object RouteLeaderboard {

    /**
     * Ranks the [attempts] of a single direction by elapsed time (ascending), the
     * fastest first, flagging the fastest as the personal best. [attempts] must all
     * belong to the same route and direction; callers filter by `reversed` first.
     */
    fun rank(attempts: List<RouteAttempt>): List<LeaderboardEntry> {
        val sorted = attempts.sortedWith(
            compareBy<RouteAttempt> { it.elapsedSeconds }.thenBy { it.recordedAt }
        )
        return sorted.mapIndexed { index, attempt ->
            LeaderboardEntry(rank = index + 1, attempt = attempt, isBest = index == 0)
        }
    }

    /** Splits ranked entries into the forward and reverse leaderboards. */
    fun split(attempts: List<RouteAttempt>): Pair<List<LeaderboardEntry>, List<LeaderboardEntry>> {
        val forward = rank(attempts.filterNot { it.reversed })
        val reverse = rank(attempts.filter { it.reversed })
        return forward to reverse
    }

    /**
     * Condenses a route's [attempts] into a [RouteLeaderboardSummary] for the
     * pre-ride preview: per-direction best / average / median times, an
     * improvement trend, how often the route has been ridden and when it was last
     * ridden.
     */
    fun summarize(attempts: List<RouteAttempt>): RouteLeaderboardSummary =
        RouteLeaderboardSummary(
            forward = directionStats(attempts.filterNot { it.reversed }, reversed = false),
            reverse = directionStats(attempts.filter { it.reversed }, reversed = true),
            lastRiddenAt = attempts.maxOfOrNull { it.recordedAt }
        )

    private fun directionStats(attempts: List<RouteAttempt>, reversed: Boolean): RouteDirectionStats {
        if (attempts.isEmpty()) {
            return RouteDirectionStats(reversed, null, null, null, 0, emptyList())
        }
        val times = attempts.map { it.elapsedSeconds }
        return RouteDirectionStats(
            reversed = reversed,
            best = attempts.minByOrNull { it.elapsedSeconds },
            averageSeconds = times.sum() / times.size,
            medianSeconds = medianOf(times),
            attemptCount = attempts.size,
            // Chronological order so a sparkline reads left (old) → right (new).
            trend = attempts.sortedBy { it.recordedAt }.map { it.elapsedSeconds }
        )
    }

    /** Median of [values] (mean of the two middle values for an even count). */
    private fun medianOf(values: List<Long>): Long {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /**
     * Derives a [RouteAttempt] from a finished [RecordedRide] of [routeId] ridden
     * in the given [reversed] direction. Returns `null` for a mock (simulator)
     * ride or a ride with no meaningful duration, so synthetic runs never pollute a
     * leaderboard.
     */
    fun attemptFromRide(
        routeId: String,
        reversed: Boolean,
        ride: RecordedRide
    ): RouteAttempt? {
        if (ride.isMock) return null
        if (ride.elapsedSeconds <= 0L) return null
        return RouteAttempt(
            id = UUID.randomUUID().toString(),
            routeId = routeId,
            reversed = reversed,
            recordedAt = ride.endedAt,
            elapsedSeconds = ride.elapsedSeconds,
            movingSeconds = ride.movingSeconds,
            distanceMeters = ride.distanceMeters,
            avgSpeedMps = ride.avgSpeedMps,
            maxSpeedMps = ride.maxSpeedMps,
            elevationGainMeters = ride.elevationGainMeters,
            rideId = ride.id
        )
    }
}

