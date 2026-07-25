package de.velospot.data.local

import de.velospot.data.local.dao.BikeParkingSpaceDao
import de.velospot.data.local.entity.BikeParkingSpaceEntity
import de.velospot.domain.model.BoundingBox
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.junit.Test

/**
 * Unit tests for [BikeParkingCacheDataSource] with a mocked [BikeParkingSpaceDao].
 * Covers the bounding-box/id read delegation with entity→domain mapping, the empty-id
 * short-circuit, the address write, and the defensive `runCatching` swallowing of DAO
 * failures (every read degrades to an empty list; the write is a silent no-op).
 */
class BikeParkingCacheDataSourceTest {

    private val dao = mock<BikeParkingSpaceDao>()
    private val source = BikeParkingCacheDataSource(dao)

    private fun entity(id: String) = BikeParkingSpaceEntity(
        id = id,
        name = "Rack $id",
        latitude = 49.75,
        longitude = 6.64,
        address = null,
        capacity = 8,
        isCovered = false,
        imageUrl = null,
        operator = null,
        type = "BIKE_RACK",
        sourceLayer = "parking",
    )

    @Test
    fun `readSpacesInBoundingBox delegates with bounds and maps to domain`() = runTest {
        val bbox = BoundingBox(minLat = 49.0, minLon = 6.0, maxLat = 50.0, maxLon = 7.0)
        whenever(dao.getSpacesInBoundingBox(eq(49.0), eq(50.0), eq(6.0), eq(7.0)))
            .thenReturn(listOf(entity("a"), entity("b")))

        val result = source.readSpacesInBoundingBox(bbox)

        assertEquals(listOf("a", "b"), result.map { it.id })
    }

    @Test
    fun `readSpacesInBoundingBox returns empty list when the dao throws`() = runTest {
        whenever(dao.getSpacesInBoundingBox(any(), any(), any(), any()))
            .thenThrow(RuntimeException("db closed"))

        assertTrue(source.readSpacesInBoundingBox(BoundingBox.DEFAULT).isEmpty())
    }

    @Test
    fun `readSpacesByIds short-circuits on an empty id list without touching the dao`() = runTest {
        assertTrue(source.readSpacesByIds(emptyList()).isEmpty())
        verifyBlocking(dao, never()) { getSpacesByIds(any()) }
    }

    @Test
    fun `readSpacesByIds delegates and maps to domain`() = runTest {
        whenever(dao.getSpacesByIds(listOf("a"))).thenReturn(listOf(entity("a")))

        assertEquals(listOf("a"), source.readSpacesByIds(listOf("a")).map { it.id })
    }

    @Test
    fun `readSpacesByIds returns empty list when the dao throws`() = runTest {
        whenever(dao.getSpacesByIds(any())).thenThrow(RuntimeException("boom"))

        assertTrue(source.readSpacesByIds(listOf("a")).isEmpty())
    }

    @Test
    fun `updateAddress delegates to the dao`() = runTest {
        source.updateAddress("id-1", "Hauptstraße 1")
        verifyBlocking(dao) { updateAddress(eq("id-1"), eq("Hauptstraße 1")) }
    }

    @Test
    fun `updateAddress swallows dao failures`() = runTest {
        whenever(dao.updateAddress(any(), any())).thenThrow(RuntimeException("gone"))
        // Must not throw.
        source.updateAddress("id-1", "Hauptstraße 1")
    }
}

