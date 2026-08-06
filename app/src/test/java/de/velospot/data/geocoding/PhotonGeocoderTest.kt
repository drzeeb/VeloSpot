package de.velospot.data.geocoding

import android.util.Log
import de.velospot.data.remote.api.PhotonApi
import de.velospot.data.remote.dto.PhotonFeatureDto
import de.velospot.data.remote.dto.PhotonGeometryDto
import de.velospot.data.remote.dto.PhotonPropertiesDto
import de.velospot.data.remote.dto.PhotonResponseDto
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
 * Unit tests for [PhotonGeocoder] with a mocked [PhotonApi]. The address formatting
 * fallbacks, the "no result"/error/exception paths, the DE/FR/LU country filter and
 * the GeoJSON→domain mapping of the search endpoint are covered end to end.
 *
 * `android.util.Log` is statically mocked so the DEBUG log branches don't hit the
 * unmocked `android.jar` stub (there is no Robolectric on the unit-test classpath).
 */
class PhotonGeocoderTest {

    private val api = mock<PhotonApi>()
    private val geocoder = PhotonGeocoder(api)

    private fun properties(
        name: String? = null,
        street: String? = null,
        housenumber: String? = null,
        postcode: String? = null,
        city: String? = null,
        district: String? = null,
        county: String? = null,
        state: String? = null,
        country: String? = null,
        countrycode: String? = null,
    ) = PhotonPropertiesDto(
        name = name,
        street = street,
        housenumber = housenumber,
        postcode = postcode,
        city = city,
        district = district,
        county = county,
        state = state,
        country = country,
        countrycode = countrycode,
        osmKey = null,
        osmValue = null,
        type = null,
    )

    /** Builds a single-feature [PhotonResponseDto]. Coordinates are stored as `[lon, lat]`. */
    private fun feature(
        properties: PhotonPropertiesDto?,
        lat: Double? = null,
        lon: Double? = null,
    ): PhotonFeatureDto {
        val geometry = if (lat != null && lon != null) {
            PhotonGeometryDto(listOf(lon, lat))
        } else {
            null
        }
        return PhotonFeatureDto(geometry = geometry, properties = properties)
    }

    private fun responseOf(vararg features: PhotonFeatureDto) =
        Response.success(PhotonResponseDto(features = features.toList()))

    private fun <T> httpError(code: Int): Response<T> =
        Response.error(code, "err".toResponseBody(null))

    /** Runs [block] with a static Log mock so DEBUG log calls are inert. */
    private fun withLog(block: suspend () -> Unit) =
        Mockito.mockStatic(Log::class.java).use { runTest { block() } }

    // ── reverseGeocode ───────────────────────────────────────────────────────

    @Test
    fun `reverseGeocode formats street and city`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(responseOf(feature(properties(street = "Hauptstraße", housenumber = "12", postcode = "54290", city = "Trier"))))
        assertEquals("Hauptstraße 12, 54290 Trier", geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode falls back to street only and city only`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(responseOf(feature(properties(street = "Hauptstraße", city = "Trier"))))
        // No house number and no postcode → "street, city".
        assertEquals("Hauptstraße, Trier", geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode falls back to name when there is no street`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(responseOf(feature(properties(name = "Trierer Dom", city = "Trier"))))
        assertEquals("Trierer Dom, Trier", geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode returns null when properties are empty`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(responseOf(feature(properties())))
        assertNull(geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode returns null when there are no features`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(Response.success(PhotonResponseDto(features = emptyList())))
        assertNull(geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode returns null when body is missing`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(Response.success(null))
        assertNull(geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode returns null on http error`() = withLog {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(httpError(500))
        assertNull(geocoder.reverseGeocode(49.75, 6.64))
    }

    @Test
    fun `reverseGeocode returns null when the call throws`() = withLog {
        whenever(api.reverse(any(), any(), any()))
            .thenThrow(RuntimeException("boom"))
        assertNull(geocoder.reverseGeocode(49.75, 6.64))
    }

    // ── reverseGeocodePlace ────────────────────────────────────────────────────

    @Test
    fun `reverseGeocodePlace prefers the resolved city`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(responseOf(feature(properties(city = "Trier", district = "Nord"))))
        assertEquals("Trier", geocoder.reverseGeocodePlace(49.75, 6.64))
    }

    @Test
    fun `reverseGeocodePlace falls back to district`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(responseOf(feature(properties(district = "Nord"))))
        assertEquals("Nord", geocoder.reverseGeocodePlace(49.75, 6.64))
    }

    @Test
    fun `reverseGeocodePlace falls back to county`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(responseOf(feature(properties(county = "Trier-Saarburg"))))
        assertEquals("Trier-Saarburg", geocoder.reverseGeocodePlace(49.75, 6.64))
    }

    @Test
    fun `reverseGeocodePlace returns null when nothing usable`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(responseOf(feature(properties())))
        assertNull(geocoder.reverseGeocodePlace(49.75, 6.64))
    }

    @Test
    fun `reverseGeocodePlace returns null on http error`() = runTest {
        whenever(api.reverse(any(), any(), any()))
            .thenReturn(httpError(429))
        assertNull(geocoder.reverseGeocodePlace(49.75, 6.64))
    }

    // ── searchAddress ──────────────────────────────────────────────────────────

    @Test
    fun `searchAddress maps results and parses coordinates`() = runTest {
        whenever(api.search(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(
                responseOf(
                    feature(properties(street = "Hauptstraße", housenumber = "12", postcode = "54290", city = "Trier", country = "Deutschland", countrycode = "DE"), lat = 49.75, lon = 6.64),
                    feature(properties(street = "Rue de Metz", postcode = "57000", city = "Metz", country = "France", countrycode = "FR"), lat = 49.12, lon = 6.18),
                )
            )
        val results = geocoder.searchAddress("query")
        assertEquals(
            listOf("Hauptstraße 12, 54290 Trier, Deutschland", "Rue de Metz, 57000 Metz, France"),
            results.map { it.displayName },
        )
        assertEquals(49.75, results.first().latitude, 0.0)
        assertEquals(6.64, results.first().longitude, 0.0)
    }

    @Test
    fun `searchAddress prefers the name for the display label`() = runTest {
        whenever(api.search(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(
                responseOf(
                    feature(properties(name = "Trierer Dom", postcode = "54290", city = "Trier", state = "Rheinland-Pfalz", country = "Deutschland", countrycode = "DE"), lat = 49.75, lon = 6.64),
                )
            )
        val results = geocoder.searchAddress("query")
        assertEquals("Trierer Dom, 54290 Trier, Rheinland-Pfalz, Deutschland", results.single().displayName)
    }

    @Test
    fun `searchAddress filters out unsupported countries`() = runTest {
        whenever(api.search(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(
                responseOf(
                    feature(properties(city = "Trier", country = "Deutschland", countrycode = "DE"), lat = 49.75, lon = 6.64),
                    feature(properties(city = "Bern", country = "Schweiz", countrycode = "CH"), lat = 46.95, lon = 7.44),
                    feature(properties(city = "Luxembourg", country = "Luxemburg", countrycode = "lu"), lat = 49.61, lon = 6.13),
                )
            )
        val results = geocoder.searchAddress("query")
        // CH is dropped, DE and (case-insensitive) LU are kept.
        assertEquals(listOf("Trier, Deutschland", "Luxembourg, Luxemburg"), results.map { it.displayName })
    }

    @Test
    fun `searchAddress skips features with missing or invalid coordinates`() = runTest {
        whenever(api.search(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(
                responseOf(
                    feature(properties(city = "Trier", country = "Deutschland", countrycode = "DE")), // no geometry
                    PhotonFeatureDto(geometry = PhotonGeometryDto(listOf(6.64)), properties = properties(city = "Trier", countrycode = "DE")), // too few coords
                    feature(properties(city = "Metz", country = "France", countrycode = "FR"), lat = 49.12, lon = 6.18),
                )
            )
        val results = geocoder.searchAddress("query")
        assertEquals(listOf("Metz, France"), results.map { it.displayName })
    }

    @Test
    fun `searchAddress works with a bias location`() = runTest {
        whenever(api.search(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(responseOf(feature(properties(city = "Trier", countrycode = "DE"), lat = 49.75, lon = 6.64)))
        val results = geocoder.searchAddress("query", nearLatitude = 49.75, nearLongitude = 6.64)
        assertEquals(1, results.size)
    }

    @Test
    fun `searchAddress returns empty list on http error`() = withLog {
        whenever(api.search(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(httpError(500))
        assertTrue(geocoder.searchAddress("query").isEmpty())
    }

    @Test
    fun `searchAddress returns empty list when the call throws`() = withLog {
        whenever(api.search(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenThrow(RuntimeException("boom"))
        assertTrue(geocoder.searchAddress("query").isEmpty())
    }
}

