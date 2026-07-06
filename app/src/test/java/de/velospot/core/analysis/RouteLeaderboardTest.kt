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
}

