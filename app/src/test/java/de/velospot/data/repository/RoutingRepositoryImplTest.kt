package de.velospot.data.repository

import android.content.Context
import de.velospot.core.routing.OfflineRoutingPreferences
import de.velospot.data.brouter.BRouterEngine
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.remote.api.OsrmApi
import de.velospot.data.remote.dto.OsrmGeometryDto
import de.velospot.data.remote.dto.OsrmRouteDto
import de.velospot.data.remote.dto.OsrmRouteResponseDto
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.EmptyRouteGeometryException
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.NoRouteFoundException
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RoutingFailedException
import de.velospot.domain.model.RoutingSource
import de.velospot.testsupport.fakeContextWithPrefs
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import retrofit2.Response

/**
 * Unit tests for [RoutingRepositoryImpl] and its [osrmFallbackRoute] helper.
 *
 * BRouter engine/segment manager and the OSRM API are mocked; the routing
 * preferences are exercised through a real [OfflineRoutingPreferences] backed by an
 * in-memory `SharedPreferences` ([fakeContextWithPrefs]). This covers the offline vs.
 * online decision tree, the on-demand download path and every OSRM error branch.
 */
class RoutingRepositoryImplTest {

    private val brouterEngine = mock<BRouterEngine>()
    private val segmentManager = mock<BRouterSegmentManager>()
    private val osrmApi = mock<OsrmApi>()

    private val from = GeoCoordinate(49.75, 6.64)
    private val to = GeoCoordinate(49.80, 6.70)

    private fun repo(ctx: Context) = RoutingRepositoryImpl(brouterEngine, segmentManager, osrmApi, ctx)

    private fun offlineCtx(): Context = fakeContextWithPrefs().also {
        OfflineRoutingPreferences.setOfflineRoutingEnabled(it, true)
    }

    private fun osrmOk(
        code: String = "Ok",
        coords: List<List<Double>> = listOf(listOf(6.64, 49.75), listOf(6.70, 49.80)),
        distance: Double = 1_000.0,
    ): Response<OsrmRouteResponseDto> = Response.success(
        OsrmRouteResponseDto(
            code = code,
            routes = listOf(OsrmRouteDto(distance = distance, duration = 500.0, geometry = OsrmGeometryDto(coords)))
        )
    )

    private fun brouterRoute() = BikeRoute(
        points = listOf(RoutePoint(49.75, 6.64, 120.0), RoutePoint(49.80, 6.70, 140.0)),
        distanceMeters = 8_000.0,
        durationSeconds = 1_800.0,
        source = RoutingSource.BROUTER_OFFLINE,
        energyJoules = 90_000.0,
    )

    // ── getBikeRoute: online / offline decision tree ───────────────────────────

    @Test
    fun `getBikeRoute uses OSRM when offline routing is disabled`() = runTest {
        val ctx = fakeContextWithPrefs() // offline disabled by default
        whenever(osrmApi.getBikeRoute(any())).thenReturn(osrmOk())

        val route = repo(ctx).getBikeRoute(from, to)

        assertEquals(RoutingSource.OSRM_ONLINE, route.source)
        assertEquals(2, route.points.size)
        assertEquals(1_000.0, route.distanceMeters, 0.0)
        assertEquals(1_000.0 / (15.0 / 3.6), route.durationSeconds, 1e-6)
        verifyBlocking(brouterEngine, never()) { calculateRoute(any(), any(), any(), any()) }
    }

    @Test
    fun `getBikeRoute uses BRouter when offline and segments are present`() = runTest {
        val ctx = offlineCtx()
        whenever(segmentManager.hasAllSegments(any(), any(), any(), any())).thenReturn(true)
        val expected = brouterRoute()
        whenever(brouterEngine.calculateRoute(any(), any(), any(), any())).thenReturn(expected)

        val route = repo(ctx).getBikeRoute(from, to)

        assertSame(expected, route)
        verifyBlocking(osrmApi, never()) { getBikeRoute(any()) }
    }

    @Test
    fun `getBikeRoute downloads on demand then routes offline`() = runTest {
        val ctx = offlineCtx() // on-demand defaults to enabled
        whenever(segmentManager.hasAllSegments(any(), any(), any(), any())).thenReturn(false, true)
        whenever(brouterEngine.calculateRoute(any(), any(), any(), any())).thenReturn(brouterRoute())

        val route = repo(ctx).getBikeRoute(from, to)

        assertEquals(RoutingSource.BROUTER_OFFLINE, route.source)
        verifyBlocking(osrmApi, never()) { getBikeRoute(any()) }
    }

    @Test
    fun `getBikeRoute falls back to OSRM when on-demand is disabled and segments missing`() = runTest {
        val ctx = offlineCtx()
        OfflineRoutingPreferences.setOnDemandDownloadEnabled(ctx, false)
        whenever(segmentManager.hasAllSegments(any(), any(), any(), any())).thenReturn(false)
        whenever(osrmApi.getBikeRoute(any())).thenReturn(osrmOk())

        val route = repo(ctx).getBikeRoute(from, to)

        assertEquals(RoutingSource.OSRM_ONLINE, route.source)
        verifyBlocking(brouterEngine, never()) { calculateRoute(any(), any(), any(), any()) }
    }

    @Test
    fun `getBikeRoute falls back to OSRM when the on-demand download fails`() = runTest {
        val ctx = offlineCtx()
        whenever(segmentManager.hasAllSegments(any(), any(), any(), any())).thenReturn(false)
        whenever(segmentManager.ensureSegments(any(), any(), any(), any(), any()))
            .thenThrow(RuntimeException("no network"))
        whenever(osrmApi.getBikeRoute(any())).thenReturn(osrmOk())

        val route = repo(ctx).getBikeRoute(from, to)

        assertEquals(RoutingSource.OSRM_ONLINE, route.source)
        verifyBlocking(brouterEngine, never()) { calculateRoute(any(), any(), any(), any()) }
    }

    // ── osrmFallbackRoute error branches (via offline-disabled getBikeRoute) ────

    @Test
    fun `OSRM http error throws RoutingFailedException with the http code`() = runTest {
        val ctx = fakeContextWithPrefs()
        whenever(osrmApi.getBikeRoute(any())).thenReturn(Response.error(503, "".toResponseBody(null)))

        val ex = runCatching { repo(ctx).getBikeRoute(from, to) }.exceptionOrNull()
        assertTrue(ex is RoutingFailedException)
        assertEquals("503", (ex as RoutingFailedException).code)
    }

    @Test
    fun `OSRM empty body throws NoRouteFoundException`() = runTest {
        val ctx = fakeContextWithPrefs()
        whenever(osrmApi.getBikeRoute(any())).thenReturn(Response.success(null))

        val ex = runCatching { repo(ctx).getBikeRoute(from, to) }.exceptionOrNull()
        assertTrue(ex is NoRouteFoundException)
    }

    @Test
    fun `OSRM non-Ok code throws RoutingFailedException with the body code`() = runTest {
        val ctx = fakeContextWithPrefs()
        whenever(osrmApi.getBikeRoute(any()))
            .thenReturn(Response.success(OsrmRouteResponseDto("NoRoute", emptyList())))

        val ex = runCatching { repo(ctx).getBikeRoute(from, to) }.exceptionOrNull()
        assertTrue(ex is RoutingFailedException)
        assertEquals("NoRoute", (ex as RoutingFailedException).code)
    }

    @Test
    fun `OSRM empty routes list throws NoRouteFoundException`() = runTest {
        val ctx = fakeContextWithPrefs()
        whenever(osrmApi.getBikeRoute(any()))
            .thenReturn(Response.success(OsrmRouteResponseDto("Ok", emptyList())))

        val ex = runCatching { repo(ctx).getBikeRoute(from, to) }.exceptionOrNull()
        assertTrue(ex is NoRouteFoundException)
    }

    @Test
    fun `OSRM geometry without usable points throws EmptyRouteGeometryException`() = runTest {
        val ctx = fakeContextWithPrefs()
        whenever(osrmApi.getBikeRoute(any())).thenReturn(osrmOk(coords = listOf(listOf(6.64)))) // < 2 → dropped

        val ex = runCatching { repo(ctx).getBikeRoute(from, to) }.exceptionOrNull()
        assertTrue(ex is EmptyRouteGeometryException)
    }

    // ── getBikeRouteVia ─────────────────────────────────────────────────────────

    @Test
    fun `getBikeRouteVia requires at least two waypoints`() = runTest {
        val ex = runCatching { repo(fakeContextWithPrefs()).getBikeRouteVia(listOf(from)) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `getBikeRouteVia chains legs and drops duplicated shared waypoints`() = runTest {
        val ctx = fakeContextWithPrefs() // offline disabled → OSRM per leg
        whenever(osrmApi.getBikeRoute(any())).thenReturn(osrmOk(distance = 1_000.0))

        val route = repo(ctx).getBikeRouteVia(listOf(from, to, GeoCoordinate(49.85, 6.75)))

        // 2 legs × 2 points, second leg drops its first (shared) point → 3 total.
        assertEquals(3, route.points.size)
        assertEquals(2_000.0, route.distanceMeters, 0.0)
        assertEquals(RoutingSource.OSRM_ONLINE, route.source)
    }

    // ── getRoundTrip ────────────────────────────────────────────────────────────

    @Test
    fun `getRoundTrip requires offline routing`() = runTest {
        val ctx = fakeContextWithPrefs() // offline disabled
        val ex = runCatching { repo(ctx).getRoundTrip(from, 10_000.0) }.exceptionOrNull()
        assertTrue(ex is RoutingFailedException)
        assertEquals("offline_required", (ex as RoutingFailedException).code)
    }

    @Test
    fun `getRoundTrip routes via BRouter when the start tile is present`() = runTest {
        val ctx = offlineCtx()
        whenever(segmentManager.hasAllSegments(any(), any(), any(), any())).thenReturn(true)
        val loop = brouterRoute()
        whenever(brouterEngine.calculateRoundTrip(any(), any(), any(), anyOrNull(), any())).thenReturn(loop)

        assertSame(loop, repo(ctx).getRoundTrip(from, 10_000.0))
    }

    @Test
    fun `getRoundTrip downloads the start tile on demand then routes`() = runTest {
        val ctx = offlineCtx()
        whenever(segmentManager.hasAllSegments(any(), any(), any(), any())).thenReturn(false, true)
        val loop = brouterRoute()
        whenever(brouterEngine.calculateRoundTrip(any(), any(), any(), anyOrNull(), any())).thenReturn(loop)

        assertSame(loop, repo(ctx).getRoundTrip(from, 10_000.0))
    }

    @Test
    fun `getRoundTrip throws when segments are missing and on-demand is disabled`() = runTest {
        val ctx = offlineCtx()
        OfflineRoutingPreferences.setOnDemandDownloadEnabled(ctx, false)
        whenever(segmentManager.hasAllSegments(any(), any(), any(), any())).thenReturn(false)

        val ex = runCatching { repo(ctx).getRoundTrip(from, 10_000.0) }.exceptionOrNull()
        assertTrue(ex is RoutingFailedException)
        assertEquals("segments_missing", (ex as RoutingFailedException).code)
        verifyBlocking(segmentManager, never()) { ensureSegments(any(), any(), any(), any(), any()) }
    }
}

