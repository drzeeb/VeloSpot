package de.velospot.data.brouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [BRouterProfile] enum's pure, resource-independent logic.
 */
class BRouterProfileTest {

    @Test
    fun `typicalSpeedMs is the km per h value divided by 3_6`() {
        BRouterProfile.entries.forEach { profile ->
            assertEquals(
                profile.typicalSpeedKmh / 3.6,
                profile.typicalSpeedMs,
                1e-9,
            )
        }
    }

    @Test
    fun `every profile has a non-blank file name and a positive speed`() {
        BRouterProfile.entries.forEach { profile ->
            assertTrue(profile.fileName.isNotBlank())
            assertTrue(profile.typicalSpeedKmh > 0.0)
        }
    }

    @Test
    fun `file names are unique across profiles`() {
        val names = BRouterProfile.entries.map { it.fileName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `known profile speeds are wired correctly`() {
        assertEquals(14.0, BRouterProfile.TREKKING.typicalSpeedKmh, 0.0)
        assertEquals(20.0, BRouterProfile.FASTBIKE.typicalSpeedKmh, 0.0)
        assertEquals("trekking", BRouterProfile.TREKKING.fileName)
    }

    @Test
    fun `SHORTEST is hidden from the user-selectable list but kept in the enum`() {
        // Plumbing must stay in place so the profile can be re-enabled trivially…
        assertTrue(BRouterProfile.entries.contains(BRouterProfile.SHORTEST))
        assertFalse(BRouterProfile.SHORTEST.userSelectable)
        // …but it must never be offered to the user (product decision #23).
        assertFalse(BRouterProfile.selectableEntries.contains(BRouterProfile.SHORTEST))
    }

    @Test
    fun `every selectable profile is bike-appropriate and the default is selectable`() {
        assertTrue(BRouterProfile.selectableEntries.isNotEmpty())
        assertTrue(BRouterProfile.selectableEntries.all { it.userSelectable })
        assertTrue(BRouterProfile.DEFAULT.userSelectable)
        assertEquals(BRouterProfile.TREKKING, BRouterProfile.DEFAULT)
    }
}

