package de.velospot.core.analysis

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RouteAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLeaderboardTest {

    private fun attempt(
        id: String,
        elapsed: Long,
        reversed: Boolean = false,
        recordedAt: Long = 0L
    ) = RouteAttempt(
        id = id,
        routeId = "route",
        reversed = reversed,
        recordedAt = recordedAt,
        elapsedSeconds = elapsed,
        movingSeconds = elapsed,
        distanceMeters = 1000.0,
        avgSpeedMps = 1000.0 / elapsed,
        maxSpeedMps = 10.0,
        elevationGainMeters = 20.0,
        rideId = "ride-$id"
    )

    private fun ride(
        id: String,
        elapsed: Long,
        isMock: Boolean = false
    ) = RecordedRide(
        id = id,
        startedAt = 0L,
        endedAt = elapsed * 1000L,
        distanceMeters = 5000.0,
        elapsedSeconds = elapsed,
        movingSeconds = elapsed - 10,
        avgSpeedMps = 5.0,
        maxSpeedMps = 9.0,
        elevationGainMeters = 42.0,
        elevationLossMeters = 40.0,
        points = emptyList(),
        isMock = isMock
    )

    @Test
    fun `rank orders by elapsed time ascending and flags the best`() {
        val ranked = RouteLeaderboard.rank(
            listOf(
                attempt("slow", elapsed = 600),
                attempt("fast", elapsed = 300),
                attempt("mid", elapsed = 450)
            )
        )
        assertEquals(listOf("fast", "mid", "slow"), ranked.map { it.attempt.id })
        assertEquals(listOf(1, 2, 3), ranked.map { it.rank })
        assertTrue(ranked.first().isBest)
        assertFalse(ranked[1].isBest)
    }

    @Test
    fun `equal times break ties by earlier recording`() {
        val ranked = RouteLeaderboard.rank(
            listOf(
                attempt("later", elapsed = 300, recordedAt = 200),
                attempt("earlier", elapsed = 300, recordedAt = 100)
            )
        )
        assertEquals(listOf("earlier", "later"), ranked.map { it.attempt.id })
    }

    @Test
    fun `split keeps forward and reverse attempts on separate boards`() {
        val (forward, reverse) = RouteLeaderboard.split(
            listOf(
                attempt("f1", elapsed = 300, reversed = false),
                attempt("r1", elapsed = 200, reversed = true),
                attempt("f2", elapsed = 250, reversed = false)
            )
        )
        assertEquals(listOf("f2", "f1"), forward.map { it.attempt.id })
        assertEquals(listOf("r1"), reverse.map { it.attempt.id })
        // Each board ranks from 1 independently.
        assertEquals(1, forward.first().rank)
        assertEquals(1, reverse.first().rank)
    }

    @Test
    fun `attemptFromRide carries the direction and ride stats`() {
        val result = RouteLeaderboard.attemptFromRide("route", reversed = true, ride = ride("r", 500))
        assertEquals("route", result?.routeId)
        assertTrue(result?.reversed == true)
        assertEquals(500L, result?.elapsedSeconds)
        assertEquals("r", result?.rideId)
    }

    @Test
    fun `attemptFromRide rejects mock and zero-length rides`() {
        assertNull(RouteLeaderboard.attemptFromRide("route", false, ride("m", 300, isMock = true)))
        assertNull(RouteLeaderboard.attemptFromRide("route", false, ride("z", 0)))
    }

    @Test
    fun `summarize reports best per direction, count and last ridden`() {
        val summary = RouteLeaderboard.summarize(
            listOf(
                attempt("f-slow", elapsed = 600, reversed = false, recordedAt = 100),
                attempt("f-fast", elapsed = 400, reversed = false, recordedAt = 300),
                attempt("r-only", elapsed = 500, reversed = true, recordedAt = 500)
            )
        )
        assertEquals("f-fast", summary.forward.best?.id)
        assertEquals("r-only", summary.reverse.best?.id)
        assertEquals(2, summary.forward.attemptCount)
        assertEquals(1, summary.reverse.attemptCount)
        assertEquals(3, summary.totalAttempts)
        assertEquals(500L, summary.lastRiddenAt)
        assertTrue(summary.hasAttempts)
    }

    @Test
    fun `summarize computes average, median, trend and best-vs-average`() {
        val summary = RouteLeaderboard.summarize(
            listOf(
                attempt("a", elapsed = 600, reversed = false, recordedAt = 100),
                attempt("b", elapsed = 500, reversed = false, recordedAt = 200),
                attempt("c", elapsed = 400, reversed = false, recordedAt = 300)
            )
        )
        val f = summary.forward
        assertEquals(500L, f.averageSeconds)          // (600+500+400)/3
        assertEquals(500L, f.medianSeconds)           // middle of {400,500,600}
        assertEquals(100L, f.bestVsAverageSeconds)    // 500 avg − 400 best
        // Trend is chronological (oldest → newest) and decreasing here → improving.
        assertEquals(listOf(600L, 500L, 400L), f.trend)
        assertTrue(f.isImproving)
    }

    @Test
    fun `summarize of an unridden route is empty`() {
        val summary = RouteLeaderboard.summarize(emptyList())
        assertNull(summary.forward.best)
        assertNull(summary.reverse.best)
        assertEquals(0, summary.totalAttempts)
        assertNull(summary.lastRiddenAt)
        assertFalse(summary.hasAttempts)
        assertFalse(summary.forward.hasAttempts)
    }
}

