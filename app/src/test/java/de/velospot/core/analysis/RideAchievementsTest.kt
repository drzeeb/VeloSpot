package de.velospot.core.analysis

import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideAchievementsTest {

    private fun ride(
        id: String = "r",
        distanceMeters: Double = 12_000.0,
        movingSeconds: Long = 2_400,
        avgSpeedMps: Double = 5.0,
        maxSpeedMps: Double = 8.0,
        elevationGainMeters: Double = 100.0,
        isMock: Boolean = false,
        startedAt: Long = dayTimeMillis(hour = 12)
    ): RecordedRide = RecordedRide(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + movingSeconds * 1000,
        distanceMeters = distanceMeters,
        elapsedSeconds = movingSeconds,
        movingSeconds = movingSeconds,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = 0.0,
        points = listOf(
            TrackPoint(0.0, 8.0, startedAt, 5f, 100.0, 5f),
            TrackPoint(0.001, 8.0, startedAt + 1000, 5f, 110.0, 5f)
        ),
        isMock = isMock
    )

    /** Fixed local time today at the given hour, to drive early-bird / night-owl. */
    private fun dayTimeMillis(hour: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, 0)
        return cal.timeInMillis
    }

    private fun analysisFor(r: RecordedRide) = analyzeRide(r)

    @Test
    fun `mock rides earn no achievements`() {
        val r = ride(distanceMeters = 120_000.0, isMock = true)
        assertTrue(evaluateAchievements(r, analysisFor(r), listOf(r)).isEmpty())
    }

    @Test
    fun `a century ride earns the century badge, not the half-century`() {
        val r = ride(distanceMeters = 105_000.0)
        val ids = evaluateAchievements(r, analysisFor(r), listOf(r)).map { it.id }
        assertTrue(AchievementId.CENTURY in ids)
        assertFalse(AchievementId.HALF_CENTURY in ids)
    }

    @Test
    fun `a 60 km ride earns the half-century badge`() {
        val r = ride(distanceMeters = 60_000.0)
        val ids = evaluateAchievements(r, analysisFor(r), listOf(r)).map { it.id }
        assertTrue(AchievementId.HALF_CENTURY in ids)
        assertFalse(AchievementId.CENTURY in ids)
    }

    @Test
    fun `the longest ride among several earns a distance personal record`() {
        val longRide = ride(id = "long", distanceMeters = 80_000.0)
        val others = listOf(
            ride(id = "a", distanceMeters = 20_000.0),
            ride(id = "b", distanceMeters = 50_000.0)
        )
        val all = others + longRide
        val earned = evaluateAchievements(longRide, analysisFor(longRide), all)
        val pr = earned.firstOrNull { it.id == AchievementId.PR_DISTANCE }
        assertTrue("expected a distance PR", pr != null)
        assertTrue("PR should be flagged", pr!!.isPersonalRecord)
    }

    @Test
    fun `not the longest ride earns no distance record`() {
        val shortRide = ride(id = "short", distanceMeters = 20_000.0)
        val all = listOf(shortRide, ride(id = "b", distanceMeters = 90_000.0))
        val earned = evaluateAchievements(shortRide, analysisFor(shortRide), all)
        assertFalse(earned.any { it.id == AchievementId.PR_DISTANCE })
    }

    @Test
    fun `no personal records are awarded for the very first ride`() {
        val first = ride(distanceMeters = 30_000.0)
        val earned = evaluateAchievements(first, analysisFor(first), listOf(first))
        assertFalse(earned.any { it.isPersonalRecord })
    }
}

