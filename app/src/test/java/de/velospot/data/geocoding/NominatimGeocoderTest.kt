package de.velospot.data.geocoding

import android.util.Log
import de.velospot.data.remote.api.NominatimApi
import de.velospot.data.remote.dto.NominatimAddressDto
import de.velospot.data.remote.dto.NominatimReverseDto
import de.velospot.data.remote.dto.NominatimSearchResultDto
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.Response

/**
 * Unit tests for [NominatimGeocoder] with a mocked [NominatimApi]. The address
 * formatting fallbacks, the "no result"/error/exception paths and the DTO→domain
 * mapping of the search endpoint are covered end to end.
 *
 * `android.util.Log` is statically mocked so the DEBUG log branches don't hit the
 * unmocked `android.jar` stub (there is no Robolectric on the unit-test classpath).
 */
class NominatimGeocoderTest {

    private val api = mock<NominatimApi>()
    private val geocoder = NominatimGeocoder(api)

    private fun address(
        road: String? = null,
        houseNumber: String? = null,
        suburb: String? = null,
        city: String? = null,
        town: String? = null,
        village: String? = null,
        hamlet: String? = null,
        postcode: String? = null,
    ) = NominatimAddressDto(road, houseNumber, suburb, city, town, village, hamlet, postcode)

    private fun reverseOk(address: NominatimAddressDto?) =
        Response.success(NominatimReverseDto(displayName = "display", address = address))

    private fun <T> httpError(code: Int): Response<T> =
        Response.error(code, "err".toResponseBody(null))

    private fun searchResult(display: String, lat: String, lon: String) =
        NominatimSearchResultDto(1L, display, lat, lon, "node", 0.5)

    /** Runs [block] with a static Log mock so DEBUG log calls are inert. */
    private fun withLog(block: suspend () -> Unit) =
        Mockito.mockStatic(Log::class.java).use { runTest { block() } }

    // ── reverseGeocode ───────────────────────────────────────────────────────

    @Test
    fun `reverseGeocode formats street and city`() = runTest {
        whenever(api.reverseGeocode(any(), any(), any(), any(), any()))
            .thenReturn(reverseOk(address(road = "Hauptstraße", houseNumber = "12", postcode = "54290", city = "Trier")))
        assertEquals("Hauptstraße 12, 54290 Trier", geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode falls back to road only and city only`() = runTest {
        whenever(api.reverseGeocode(any(), any(), any(), any(), any()))
            .thenReturn(reverseOk(address(road = "Hauptstraße", town = "Trier")))
        // No house number and no postcode → "road, city".
        assertEquals("Hauptstraße, Trier", geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode returns null when address is empty`() = runTest {
        whenever(api.reverseGeocode(any(), any(), any(), any(), any()))
            .thenReturn(reverseOk(address()))
        assertNull(geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode returns null when body is missing`() = runTest {
        whenever(api.reverseGeocode(any(), any(), any(), any(), any()))
            .thenReturn(Response.success(null))
        assertNull(geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode returns null on http error`() = withLog {
        whenever(api.reverseGeocode(any(), any(), any(), any(), any()))
            .thenReturn(httpError(500))
        assertNull(geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode returns null when the call throws`() = withLog {
        whenever(api.reverseGeocode(any(), any(), any(), any(), any()))
            .thenThrow(RuntimeException("boom"))
        assertNull(geocoder.reverseGeocode(49.75, 6.64))
    }

    // ── reverseGeocodePlace ────────────────────────────────────────────────────

    @Test
    fun `reverseGeocodePlace prefers the resolved city`() = runTest {
        whenever(api.reverseGeocode(any(), any(), any(), any(), any()))
            .thenReturn(reverseOk(address(city = "Trier", suburb = "Nord")))
        assertEquals("Trier", geocoder.reverseGeocodePlace(49.75, 6.64))
    }

    @Test
    fun `reverseGeocodePlace falls back to suburb`() = runTest {
        whenever(api.reverseGeocode(any(), any(), any(), any(), any()))
            .thenReturn(reverseOk(address(suburb = "Nord")))
        assertEquals("Nord", geocoder.reverseGeocodePlace(49.75, 6.64))
    }

    @Test
    fun `reverseGeocodePlace returns null when nothing usable`() = runTest {
        whenever(api.reverseGeocode(any(), any(), any(), any(), any()))
            .thenReturn(reverseOk(address()))
        assertNull(geocoder.reverseGeocodePlace(49.75, 6.64))
    }

    @Test
    fun `reverseGeocodePlace returns null on http error`() = runTest {
        whenever(api.reverseGeocode(any(), any(), any(), any(), any()))
            .thenReturn(httpError(429))
        assertNull(geocoder.reverseGeocodePlace(49.75, 6.64))
    }

    // ── searchAddress ──────────────────────────────────────────────────────────

    @Test
    fun `searchAddress maps results and parses coordinates`() = runTest {
        whenever(api.search(any(), any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(
                Response.success(
                    listOf(
                        searchResult("Trier, DE", "49.75", "6.64"),
                        searchResult("Metz, FR", "49.12", "6.18"),
                    )
                )
            )
        val results = geocoder.searchAddress("query")
        assertEquals(listOf("Trier, DE", "Metz, FR"), results.map { it.displayName })
        assertEquals(49.75, results.first().latitude, 0.0)
        assertEquals(6.64, results.first().longitude, 0.0)
    }

    @Test
    fun `searchAddress works with a bias location (viewbox branch)`() = runTest {
        whenever(api.search(any(), any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Response.success(listOf(searchResult("Trier", "49.75", "6.64"))))
        val results = geocoder.searchAddress("query", nearLatitude = 49.75, nearLongitude = 6.64)
        assertEquals(1, results.size)
    }

    @Test
    fun `searchAddress returns empty list on http error`() = withLog {
        whenever(api.search(any(), any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(httpError(500))
        assertTrue(geocoder.searchAddress("query").isEmpty())
    }

    @Test
    fun `searchAddress returns empty list when the call throws`() = withLog {
        whenever(api.search(any(), any(), any(), any(), anyOrNull(), any(), any()))
            .thenThrow(RuntimeException("boom"))
        assertTrue(geocoder.searchAddress("query").isEmpty())
    }
}

