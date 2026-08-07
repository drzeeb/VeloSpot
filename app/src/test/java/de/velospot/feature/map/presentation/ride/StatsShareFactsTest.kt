package de.velospot.feature.map.presentation.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure "bro flex" badge-qualification helpers. */
class StatsShareFactsTest {

    @Test
    fun everestRatio_isGainOverEverestHeight() {
        assertEquals(0.0, everestRatio(0.0), 0.0)
        assertEquals(1.0, everestRatio(EVEREST_HEIGHT_METERS), 1e-9)
        assertEquals(2.0, everestRatio(EVEREST_HEIGHT_METERS * 2), 1e-9)
    }

    @Test
    fun everestRatio_clampsNegativeGainToZero() {
        assertEquals(0.0, everestRatio(-500.0), 0.0)
    }

    @Test
    fun worldBadge_qualifiesOnlyWithDistance() {
        assertFalse(qualifiesWorldBadge(0.0))
        assertTrue(qualifiesWorldBadge(0.01))
    }

    @Test
    fun everestBadge_qualifiesAtFivePercent() {
        assertFalse(qualifiesEverestBadge(EVEREST_HEIGHT_METERS * 0.049))
        assertTrue(qualifiesEverestBadge(EVEREST_HEIGHT_METERS * 0.05))
    }

    @Test
    fun streakBadge_qualifiesFromTwoDays() {
        assertFalse(qualifiesStreakBadge(1))
        assertTrue(qualifiesStreakBadge(2))
    }
}

