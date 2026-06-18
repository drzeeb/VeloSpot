package de.velospot.core.navigation

import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RoutePoint
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimulatorTest {

    // ~330 m straight eastbound leg then a northbound leg.
    private val route = listOf(
        RoutePoint(49.0000, 6.0000),
        RoutePoint(49.0000, 6.0030),
        RoutePoint(49.0020, 6.0030)
    )

    @Test
    fun `emits the start point first and finishes at the route end`() = runTest {
        val fixes = mutableListOf<GeoCoordinate>()
        var finished = false

        val sim = RouteSimulator()
        sim.start(
            scope = this,
            route = route,
            speedMps = 20.0,      // fast so the virtual run completes quickly
            intervalMs = 1_000L,
            onFix = { fixes.add(it) },
            onFinished = { finished = true }
        )
        // runTest auto-advances virtual time through all delays.
        advanceUntilIdle()

        assertTrue("expected several fixes", fixes.size >= 2)
        assertTrue("simulation should have finished", finished)
        assertFalse(sim.isRunning)

        // First fix sits at the route start.
        assertEquals(49.0000, fixes.first().latitude, 1e-6)
        assertEquals(6.0000, fixes.first().longitude, 1e-6)

        // Every fix carries bearing + speed, like a real GPS fix.
        fixes.forEach {
            assertTrue(it.bearing != null)
            assertEquals(20.0f, it.speedMetersPerSecond)
        }

        // Last fix has reached the destination.
        assertEquals(49.0020, fixes.last().latitude, 1e-4)
        assertEquals(6.0030, fixes.last().longitude, 1e-4)
    }

    @Test
    fun `bearing starts pointing east along the first leg`() = runTest {
        val fixes = mutableListOf<GeoCoordinate>()
        RouteSimulator().start(
            scope = this,
            route = route,
            speedMps = 10.0,
            intervalMs = 1_000L,
            onFix = { fixes.add(it) }
        )
        // First leg runs due east → bearing ~90°.
        advanceUntilIdle()
        assertEquals(90.0, fixes.first().bearing!!.toDouble(), 1.0)
    }

    @Test
    fun `does nothing for a degenerate route`() = runTest {
        var emitted = false
        val sim = RouteSimulator()
        sim.start(this, listOf(RoutePoint(49.0, 6.0)), onFix = { emitted = true })
        assertFalse(emitted)
        assertFalse(sim.isRunning)
    }
}




