package de.velospot.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in the auto-naming decision for saved recordings (problem #2): an unnamed
 * recording is named after its reverse-geocoded start place, but an explicit name
 * (typed by the rider, or a navigation / round-trip label) is never overridden.
 */
class RideAutoNameTest {

    @Test
    fun `keeps the explicit user name over the geocoded place`() {
        assertEquals(
            "My commute",
            RideRecordingManager.resolveRideName(existingName = "My commute", geocodedPlace = "Trier")
        )
    }

    @Test
    fun `falls back to the geocoded place when unnamed`() {
        assertEquals(
            "Trier",
            RideRecordingManager.resolveRideName(existingName = null, geocodedPlace = "Trier")
        )
    }

    @Test
    fun `treats a blank existing name as absent and uses the place`() {
        assertEquals(
            "Trier",
            RideRecordingManager.resolveRideName(existingName = "   ", geocodedPlace = "Trier")
        )
    }

    @Test
    fun `stays unnamed when neither a name nor a place is available`() {
        assertNull(RideRecordingManager.resolveRideName(existingName = null, geocodedPlace = null))
        assertNull(RideRecordingManager.resolveRideName(existingName = "", geocodedPlace = "  "))
    }
}

