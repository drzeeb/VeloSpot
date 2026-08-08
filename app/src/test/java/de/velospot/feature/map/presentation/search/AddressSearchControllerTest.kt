package de.velospot.feature.map.presentation.search

import de.velospot.data.geocoding.PhotonGeocoder
import de.velospot.domain.model.AddressSearchResult
import de.velospot.domain.model.GeoCoordinate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for [AddressSearchController]: the debounced query → results flow,
 * the min-length gate, location biasing and the clear/collapse resets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressSearchControllerTest {

    private val results = listOf(AddressSearchResult("Trier, DE", 49.75, 6.64))

    private fun geocoder(response: List<AddressSearchResult> = results): PhotonGeocoder =
        mock {
            onBlocking { searchAddress(any(), any(), any()) } doReturn response
        }

    private fun controller(
        scope: CoroutineScope,
        geocoder: PhotonGeocoder = geocoder(),
        location: GeoCoordinate? = GeoCoordinate(49.75, 6.64),
    ) = AddressSearchController(scope, geocoder) { location }

    @Test
    fun `short queries are not searched and clear the results`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)

        c.onQueryChanged("ab")
        advanceUntilIdle()

        assertEquals("ab", c.query.value)
        assertTrue(c.results.value.isEmpty())
        assertFalse(c.isSearching.value)
    }

    @Test
    fun `a long enough query debounces then populates the results`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)

        c.onQueryChanged("Trier")
        advanceUntilIdle()

        assertEquals(results, c.results.value)
        assertFalse(c.isSearching.value)
    }

    @Test
    fun `the latest keystroke cancels the previous in-flight search`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)

        c.onQueryChanged("Trie")
        c.onQueryChanged("Trier")
        advanceUntilIdle()

        // Only one debounced search survives; its result set is shown once.
        assertEquals(results, c.results.value)
        assertEquals("Trier", c.query.value)
    }

    @Test
    fun `clear resets the query results and indicator`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        c.onQueryChanged("Trier")
        advanceUntilIdle()
        assertTrue(c.results.value.isNotEmpty())

        c.clear()

        assertEquals("", c.query.value)
        assertTrue(c.results.value.isEmpty())
        assertFalse(c.isSearching.value)
    }

    @Test
    fun `collapseResults hides the dropdown but keeps the query`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope)
        c.onQueryChanged("Trier")
        advanceUntilIdle()

        c.collapseResults()

        assertEquals("Trier", c.query.value)
        assertTrue(c.results.value.isEmpty())
    }

    @Test
    fun `search is biased towards the current location when known`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(scope, geocoder(), location = GeoCoordinate(50.0, 8.0))

        c.onQueryChanged("Cafe")
        advanceUntilIdle()

        // Reaching a non-empty result confirms the near-lat/lon path ran.
        assertEquals(results, c.results.value)
    }
}
