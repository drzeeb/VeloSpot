package de.velospot.feature.map.presentation

import de.velospot.core.location.hasLocationPermission
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLocationPermissionTest {

    @Test
    fun `hasLocationPermission returns true when fine permission is granted`() {
        assertTrue(hasLocationPermission(fineGranted = true, coarseGranted = false))
    }

    @Test
    fun `hasLocationPermission returns true when coarse permission is granted`() {
        assertTrue(hasLocationPermission(fineGranted = false, coarseGranted = true))
    }

    @Test
    fun `hasLocationPermission returns false when no permission is granted`() {
        assertFalse(hasLocationPermission(fineGranted = false, coarseGranted = false))
    }

    @Test
    fun `requestLocationAccessIfNeeded invokes onPermissionGranted when permission is available`() {
        var grantedCalls = 0
        var requestedCalls = 0

        requestLocationAccessIfNeeded(
            hasPermission = true,
            onPermissionGranted = { grantedCalls += 1 },
            requestPermissions = { requestedCalls += 1 },
            permissions = arrayOf("fine", "coarse")
        )

        assertEquals(1, grantedCalls)
        assertEquals(0, requestedCalls)
    }

    @Test
    fun `requestLocationAccessIfNeeded requests permissions when permission is missing`() {
        var grantedCalls = 0
        var requestedCalls = 0
        var requestedPermissions: Array<String>? = null

        requestLocationAccessIfNeeded(
            hasPermission = false,
            onPermissionGranted = { grantedCalls += 1 },
            requestPermissions = {
                requestedCalls += 1
                requestedPermissions = it
            },
            permissions = arrayOf("fine", "coarse")
        )

        assertEquals(0, grantedCalls)
        assertEquals(1, requestedCalls)
        assertArrayEquals(arrayOf("fine", "coarse"), requestedPermissions)
    }
}

