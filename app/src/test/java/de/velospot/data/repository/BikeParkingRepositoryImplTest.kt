package de.velospot.data.repository

import de.velospot.data.geocoding.NominatimGeocoder
import de.velospot.data.local.BikeParkingLocalDataSource
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.BoundingBox
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * Unit tests for [BikeParkingRepositoryImpl] with a mocked local data source and a
 * mocked [NominatimGeocoder]. Covers the read delegation and the lazy address
 * resolution (cache hit, geocode-and-persist, and the "no address found" no-op).
 */
class BikeParkingRepositoryImplTest {

    private val dataSource = mock<BikeParkingLocalDataSource>()
    private val geocoder = mock<NominatimGeocoder>()
    private val repo = BikeParkingRepositoryImpl(dataSource, geocoder)

    private fun space(id: String = "s1", address: String? = null) = BikeParkingSpace(
        id = id,
        latitude = 49.75,
        longitude = 6.64,
        type = BikeParkingType.BIKE_RACK,
        capacity = 10,
        name = "Rack",
        address = address,
        isCovered = false,
        imageUrl = null,
        operator = null,
        sourceLayer = "osm",
    )

    @Test
    fun `getSpacesInBoundingBox delegates to the local data source`() = runTest {
        val bbox = BoundingBox.DEFAULT
        val expected = listOf(space("a"), space("b"))
        whenever(dataSource.readSpacesInBoundingBox(bbox)).thenReturn(expected)

        assertEquals(expected, repo.getSpacesInBoundingBox(bbox))
    }

    @Test
    fun `getSpacesByIds delegates to the local data source`() = runTest {
        val expected = listOf(space("a"))
        whenever(dataSource.readSpacesByIds(listOf("a"))).thenReturn(expected)

        assertEquals(expected, repo.getSpacesByIds(listOf("a")))
    }

    @Test
    fun `resolveAddress returns the space unchanged when it already has an address`() = runTest {
        val withAddress = space(address = "Hauptstraße 1, 54290 Trier")

        val result = repo.resolveAddress(withAddress)

        assertSame(withAddress, result)
        verifyBlocking(geocoder, never()) { reverseGeocode(any(), any()) }
        verifyBlocking(dataSource, never()) { updateAddress(any(), any()) }
    }

    @Test
    fun `resolveAddress geocodes, persists and returns the resolved copy`() = runTest {
        val space = space(id = "s1", address = null)
        whenever(geocoder.reverseGeocode(49.75, 6.64)).thenReturn("Hauptstraße 12, 54290 Trier")

        val result = repo.resolveAddress(space)

        assertEquals("Hauptstraße 12, 54290 Trier", result.address)
        assertEquals("s1", result.id)
        verifyBlocking(dataSource) { updateAddress(eq("s1"), eq("Hauptstraße 12, 54290 Trier")) }
    }

    @Test
    fun `resolveAddress is a no-op when geocoding yields nothing`() = runTest {
        val space = space(id = "s1", address = null)
        whenever(geocoder.reverseGeocode(49.75, 6.64)).thenReturn(null)

        val result = repo.resolveAddress(space)

        assertNull(result.address)
        assertSame(space, result)
        verifyBlocking(dataSource, never()) { updateAddress(any(), any()) }
    }
}

