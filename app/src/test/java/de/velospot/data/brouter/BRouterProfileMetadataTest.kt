package de.velospot.data.brouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [BRouterProfile]'s pure metadata: the derived m/s speed, the
 * default fallback and the user-selectable filtering (hidden profiles excluded).
 */
class BRouterProfileMetadataTest {

    @Test
    fun `typicalSpeedMs is derived from kmh`() {
        assertEquals(14.0 / 3.6, BRouterProfile.TREKKING.typicalSpeedMs, 1e-9)
        assertEquals(20.0 / 3.6, BRouterProfile.FASTBIKE.typicalSpeedMs, 1e-9)
    }

    @Test
    fun `default profile is trekking`() {
        assertEquals(BRouterProfile.TREKKING, BRouterProfile.DEFAULT)
    }

    @Test
    fun `selectable entries exclude the hidden shortest profile`() {
        val selectable = BRouterProfile.selectableEntries
        assertTrue(BRouterProfile.TREKKING in selectable)
        assertTrue(BRouterProfile.GRAVEL in selectable)
        assertFalse(BRouterProfile.SHORTEST in selectable)
        assertTrue(selectable.all { it.userSelectable })
    }

    @Test
    fun `every profile carries a non-blank file name`() {
        assertTrue(BRouterProfile.entries.all { it.fileName.isNotBlank() })
    }
}

