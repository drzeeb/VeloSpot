package de.velospot.core.navigation

import de.velospot.domain.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RouteEtaModel] — the pure ETA math that makes the remaining
 * navigation time reflect the modelled effort of the portion still ahead instead
 * of a flat `distance ÷ average`.
 */
class RouteEtaModelTest {

    // Straight eastbound route, five evenly-spaced nodes (four equal segments).
    private fun straightRoute(elevations: List<Double?>): List<RoutePoint> =
        elevations.mapIndexed { i, e ->
            RoutePoint(latitude = 49.0, longitude = 6.0 + i * 0.001, elevationMeters = e)
        }

    private val total = 600.0

    // ── Preferred: per-node times ─────────────────────────────────────────────

    @Test
    fun `per-node times are used and rescaled to the source total`() {
        val points = straightRoute(listOf(null, null, null, null, null))
        val perNode = listOf(0.0, 200.0, 400.0, 1000.0, 1200.0) // last != total
        val cum = RouteEtaModel.buildCumulativeTimes(points, perNode, total)!!
        // Rescaled so the last node equals the source total exactly.
        assertEquals(total, cum.last(), 1e-6)
        // Shape preserved: node 3 was 1000/1200 of the way through.
        assertEquals(total * 1000.0 / 1200.0, cum[3], 1e-6)
    }

    @Test
    fun `whole-route modelled time matches the source total at the start`() {
        val points = straightRoute(listOf(null, null, null, null, null))
        val perNode = listOf(0.0, 150.0, 300.0, 450.0, 600.0)
        val cum = RouteEtaModel.buildCumulativeTimes(points, perNode, total)!!
        // At the very start (segment 0, t=0) the remaining time == the whole total.
        assertEquals(total, RouteEtaModel.remainingSeconds(cum, 0, 0.0), 1e-6)
    }

    @Test
    fun `interpolation within a segment is monotonic`() {
        val points = straightRoute(listOf(null, null, null, null, null))
        val perNode = listOf(0.0, 150.0, 300.0, 450.0, 600.0)
        val cum = RouteEtaModel.buildCumulativeTimes(points, perNode, total)!!
        val atStart = RouteEtaModel.remainingSeconds(cum, 1, 0.0)
        val atMid = RouteEtaModel.remainingSeconds(cum, 1, 0.5)
        val atEnd = RouteEtaModel.remainingSeconds(cum, 1, 1.0)
        assertTrue("remaining must strictly decrease across a segment", atStart > atMid)
        assertTrue("remaining must strictly decrease across a segment", atMid > atEnd)
        // t=1 of segment 1 equals t=0 of segment 2 (continuous).
        assertEquals(RouteEtaModel.remainingSeconds(cum, 2, 0.0), atEnd, 1e-9)
    }

    // ── Fallback: gradient-weighted geometry ──────────────────────────────────

    @Test
    fun `a climb ahead yields a larger remaining-time than the flat estimate`() {
        // Flat first half, steep climb second half.
        val points = straightRoute(listOf(0.0, 0.0, 0.0, 20.0, 40.0))
        val cum = RouteEtaModel.buildCumulativeTimes(points, perNodeTimes = null, totalSeconds = total)!!
        // At the midpoint node (start of the climb) the modelled remaining time…
        val remaining = RouteEtaModel.remainingSeconds(cum, 2, 0.0)
        // …exceeds the flat estimate, which — halfway along equal segments — is
        // simply half of the total.
        val flatRemaining = total / 2.0
        assertTrue("climb ahead must read slower than flat avg", remaining > flatRemaining)
    }

    @Test
    fun `a descent ahead yields a smaller remaining-time than the flat estimate`() {
        // Flat first half, descent second half.
        val points = straightRoute(listOf(0.0, 0.0, 0.0, -20.0, -40.0))
        val cum = RouteEtaModel.buildCumulativeTimes(points, perNodeTimes = null, totalSeconds = total)!!
        val remaining = RouteEtaModel.remainingSeconds(cum, 2, 0.0)
        val flatRemaining = total / 2.0
        assertTrue("descent ahead must read faster than flat avg", remaining < flatRemaining)
    }

    @Test
    fun `gradient model still sums to the source total at the start`() {
        val points = straightRoute(listOf(0.0, 0.0, 0.0, 20.0, 40.0))
        val cum = RouteEtaModel.buildCumulativeTimes(points, perNodeTimes = null, totalSeconds = total)!!
        assertEquals(total, RouteEtaModel.remainingSeconds(cum, 0, 0.0), 1e-6)
        assertEquals(total, cum.last(), 1e-6)
    }

    // ── No usable data: caller keeps its flat estimate ────────────────────────

    @Test
    fun `returns null when neither per-node times nor elevation are available`() {
        val points = straightRoute(listOf(null, null, null, null, null))
        assertNull(RouteEtaModel.buildCumulativeTimes(points, perNodeTimes = null, totalSeconds = total))
    }

    @Test
    fun `returns null for a non-positive total`() {
        val points = straightRoute(listOf(0.0, 10.0, 20.0, 30.0, 40.0))
        assertNull(RouteEtaModel.buildCumulativeTimes(points, perNodeTimes = null, totalSeconds = 0.0))
    }

    // ── Flat blended fallback ─────────────────────────────────────────────────

    @Test
    fun `blended flat falls back to the route average without a live speed`() {
        // 1000 m at 5 m/s average → 200 s.
        assertEquals(200.0, RouteEtaModel.blendedFlatSeconds(1000.0, 5.0, liveSpeedMps = null), 1e-9)
    }

    @Test
    fun `blended flat reacts to a faster live speed`() {
        val avgOnly = RouteEtaModel.blendedFlatSeconds(1000.0, 5.0, liveSpeedMps = null)
        val withFast = RouteEtaModel.blendedFlatSeconds(1000.0, 5.0, liveSpeedMps = 10.0)
        assertTrue("a faster live speed must shorten the ETA", withFast < avgOnly)
    }
}

