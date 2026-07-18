package de.velospot.data.brouter

import org.junit.Assert.assertEquals
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
}

