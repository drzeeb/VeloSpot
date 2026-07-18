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
 * A compact, at-a-glance digest of a route's leaderboard, shown on the route
 * **preview** (before riding) so the rider can see how they've done so far:
 *
 * @property forwardBest the fastest **forward** attempt, or `null` if never ridden
 *  forward.
 * @property reverseBest the fastest **reverse** attempt, or `null` if never ridden
 *  reversed.
 * @property totalAttempts how many times the route has been ridden (both
 *  directions).
 * @property lastRiddenAt wall-clock time of the most recent attempt, or `null`
 *  when the route has never been ridden.
 */
data class RouteLeaderboardSummary(
    val forwardBest: RouteAttempt?,
    val reverseBest: RouteAttempt?,
    val totalAttempts: Int,
    val lastRiddenAt: Long?
) {
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
     * pre-ride preview: the fastest forward and reverse times, how often the route
     * has been ridden, and when it was last ridden.
     */
    fun summarize(attempts: List<RouteAttempt>): RouteLeaderboardSummary =
        RouteLeaderboardSummary(
            forwardBest = rank(attempts.filterNot { it.reversed }).firstOrNull()?.attempt,
            reverseBest = rank(attempts.filter { it.reversed }).firstOrNull()?.attempt,
            totalAttempts = attempts.size,
            lastRiddenAt = attempts.maxOfOrNull { it.recordedAt }
        )

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

