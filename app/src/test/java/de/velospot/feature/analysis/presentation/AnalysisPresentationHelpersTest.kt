package de.velospot.feature.analysis.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import de.velospot.core.analysis.AchievementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure (non-Composable) presentation helpers backing the ride
 * analysis screen: the achievement-badge visual/title mapping and the best-effort
 * duration-target label. These carry the branch logic that Kover counts for the
 * `analysis/presentation` package; the surrounding `@Composable`s are excluded.
 */
class AnalysisPresentationHelpersTest {

    @Test
    fun `every achievement id has a title string resource`() {
        AchievementId.entries.forEach { id ->
            assertNotEquals("missing title for $id", 0, titleResFor(id))
        }
    }

    @Test
    fun `every achievement id maps to a badge visual`() {
        AchievementId.entries.forEach { id ->
            val visual = visualFor(id)
            assertNotNull("missing visual for $id", visual.icon)
        }
    }

    @Test
    fun `personal-record achievements share the gold trophy visual`() {
        val prIds = listOf(
            AchievementId.PR_DISTANCE,
            AchievementId.PR_CLIMBING,
            AchievementId.PR_PACE,
            AchievementId.PR_TOP_SPEED,
        )
        prIds.forEach { id ->
            val visual = visualFor(id)
            assertEquals(GOLD, visual.color)
            assertEquals(Icons.Filled.EmojiEvents, visual.icon)
        }
    }

    @Test
    fun `distance and climbing achievements use distinct colours`() {
        // Guards the branch table against a copy-paste collapse of two categories.
        assertNotEquals(
            visualFor(AchievementId.HALF_CENTURY).color,
            visualFor(AchievementId.HILL_CLIMBER).color,
        )
    }

    @Test
    fun `duration targets under an hour are shown in minutes`() {
        assertEquals("1 min", formatDurationTarget(60))
        assertEquals("5 min", formatDurationTarget(300))
        assertEquals("20 min", formatDurationTarget(1_200))
        // Just under the hour boundary still reads in minutes.
        assertEquals("59 min", formatDurationTarget(3_599))
    }

    @Test
    fun `duration targets of an hour or more are shown in hours`() {
        assertEquals("1 h", formatDurationTarget(3_600))
        assertEquals("2 h", formatDurationTarget(7_200))
    }

    @Test
    fun `duration target formatting is monotonic across the boundary`() {
        assertTrue(formatDurationTarget(3_599).endsWith("min"))
        assertTrue(formatDurationTarget(3_600).endsWith("h"))
    }
}

