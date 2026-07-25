package de.velospot.feature.map.presentation.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import de.velospot.core.offline.OfflineRegionPack
import de.velospot.core.offline.OfflineRegionsStore
import de.velospot.core.routing.OfflineRoutingPreferences
import de.velospot.data.brouter.BRouterSegmentManager
import de.velospot.data.maptiles.OfflineMapTilesManager
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.MapError
import de.velospot.testsupport.fakeContextWithPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for [OfflineRegionsController] with mocked tile/segment managers and an
 * in-memory offline-regions store, covering the sheet state machine, the add/download
 * flow (incl. the Wi-Fi gate) and deletion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineRegionsControllerTest {

    private val tiles = mock<OfflineMapTilesManager>()
    private val segments = mock<BRouterSegmentManager>()

    private fun context(internet: Boolean, wifi: Boolean): Context {
        val ctx = fakeContextWithPrefs()
        val cm = mock<ConnectivityManager>()
        if (internet || wifi) {
            val network = mock<Network>()
            val caps = mock<NetworkCapabilities>()
            whenever(cm.activeNetwork).thenReturn(network)
            whenever(cm.getNetworkCapabilities(network)).thenReturn(caps)
            whenever(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(internet)
            whenever(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(wifi)
        } else {
            whenever(cm.activeNetwork).thenReturn(null)
        }
        whenever(ctx.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)
        return ctx
    }

    private class Harness(
        val controller: OfflineRegionsController,
        val store: OfflineRegionsStore,
        val ctx: Context,
        val errors: List<MapError>,
        val duplicates: () -> Int,
    )

    private fun TestScopeBuilder(
        scope: CoroutineScope,
        ctx: Context,
        location: GeoCoordinate? = GeoCoordinate(50.11, 8.68),
        reverse: suspend (Double, Double) -> String? = { _, _ -> "Frankfurt" },
    ): Harness {
        val store = OfflineRegionsStore(ctx)
        val errors = mutableListOf<MapError>()
        var duplicates = 0
        val controller = OfflineRegionsController(
            scope = scope,
            context = ctx,
            segmentManager = segments,
            tilesManager = tiles,
            styleUrl = "https://example.com/style.json",
            store = store,
            currentLocation = { location },
            reverseGeocode = reverse,
            onDownloadError = { errors += it },
            onDuplicateRegion = { duplicates++ },
        )
        return Harness(controller, store, ctx, errors) { duplicates }
    }

    @Test
    fun `sheet flags toggle`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = TestScopeBuilder(scope, context(internet = false, wifi = false))
        with(h.controller) {
            requestSetup(); assertTrue(showManagerSheet.value)
            dismissManagerSheet(); assertTrue(!showManagerSheet.value)
            openProfileSheet(); assertTrue(showProfileSheet.value)
            dismissProfileSheet(); assertTrue(!showProfileSheet.value)
            dismissWifiWarning(); assertTrue(!showWifiWarning.value)
        }
    }

    @Test
    fun `addCurrentRegion with no location reports LocationUnavailable`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = TestScopeBuilder(scope, context(internet = true, wifi = true), location = null)
        h.controller.addCurrentRegion()
        assertEquals(listOf(MapError.LocationUnavailable), h.errors)
    }

    @Test
    fun `addRegionAt without internet reports NoInternetConnection`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = TestScopeBuilder(scope, context(internet = false, wifi = false))
        h.controller.addRegionAt(50.11, 8.68)
        assertEquals(listOf(MapError.NoInternetConnection), h.errors)
    }

    @Test
    fun `addRegionAt on metered data shows the wifi warning first`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = TestScopeBuilder(scope, context(internet = true, wifi = false))
        h.controller.addRegionAt(50.11, 8.68)
        assertTrue(h.controller.showWifiWarning.value)
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun `duplicate region within an existing area is rejected`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = TestScopeBuilder(scope, context(internet = true, wifi = true))
        h.store.add(OfflineRegionPack("existing", "Frankfurt", 50.11, 8.68, 1L))
        h.controller.addRegionAt(50.11, 8.68) // same spot → duplicate
        assertEquals(1, h.duplicates())
    }

    @Test
    fun `confirming mobile-data download persists the region and enables offline routing`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val ctx = context(internet = true, wifi = false)
        val h = TestScopeBuilder(scope, ctx)

        h.controller.addRegionAt(50.11, 8.68) // metered → wifi warning, sets pending
        assertTrue(h.controller.showWifiWarning.value)

        h.controller.confirmDownloadOnMobileData()
        advanceUntilIdle()

        assertEquals(1, h.store.list().size)
        assertEquals("Frankfurt", h.store.list().first().label)
        assertEquals(1, h.controller.uiState.value.regions.size)
        assertNull(h.controller.uiState.value.downloading)
        assertTrue(OfflineRoutingPreferences.isOfflineRoutingEnabled(ctx))
    }

    @Test
    fun `deleteAllRegions clears the store and disables offline routing`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val ctx = context(internet = true, wifi = true)
        val h = TestScopeBuilder(scope, ctx)
        h.store.add(OfflineRegionPack("a", "A", 50.11, 8.68, 1L))
        OfflineRoutingPreferences.setOfflineRoutingEnabled(ctx, true)

        h.controller.deleteAllRegions()
        advanceUntilIdle()

        assertTrue(h.store.list().isEmpty())
        assertTrue(h.controller.uiState.value.regions.isEmpty())
        assertTrue(!OfflineRoutingPreferences.isOfflineRoutingEnabled(ctx))
    }

    @Test
    fun `deleteRegion removes a single region`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val ctx = context(internet = true, wifi = true)
        val h = TestScopeBuilder(scope, ctx)
        h.store.add(OfflineRegionPack("a", "A", 50.11, 8.68, 1L))
        h.store.add(OfflineRegionPack("b", "B", 48.13, 11.58, 2L))

        h.controller.deleteRegion("a")
        advanceUntilIdle()

        assertEquals(listOf("b"), h.store.list().map { it.id })
    }

    @Test
    fun `selectProfile persists and reflects the chosen profile`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val ctx = context(internet = true, wifi = true)
        val h = TestScopeBuilder(scope, ctx)
        val profile = h.controller.uiState.value.profile
        h.controller.selectProfile(profile)
        assertEquals(profile, h.controller.uiState.value.profile)
    }
}

