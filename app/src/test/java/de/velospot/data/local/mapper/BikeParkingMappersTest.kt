package de.velospot.data.local.mapper

import de.velospot.data.local.entity.BikeParkingSpaceEntity
import de.velospot.domain.model.BikeParkingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure entity ⇄ domain mapping functions.
 */
class BikeParkingMappersTest {

    private fun entity(
        id: String = "n1",
        type: String = "BIKE_RACK",
    ) = BikeParkingSpaceEntity(
        id = id,
        name = "Rack",
        latitude = 49.75,
        longitude = 6.64,
        address = "Hauptstr. 1",
        capacity = 12,
        isCovered = true,
        imageUrl = null,
        operator = "City",
        type = type,
        sourceLayer = "parking",
    )

    @Test
    fun `toDomainModel copies every field`() {
        val domain = entity().toDomainModel()

        assertEquals("n1", domain.id)
        assertEquals("Rack", domain.name)
        assertEquals(49.75, domain.latitude, 0.0)
        assertEquals(6.64, domain.longitude, 0.0)
        assertEquals("Hauptstr. 1", domain.address)
        assertEquals(12, domain.capacity)
        assertEquals(true, domain.isCovered)
        assertNull(domain.imageUrl)
        assertEquals("City", domain.operator)
        assertEquals(BikeParkingType.BIKE_RACK, domain.type)
        assertEquals("parking", domain.sourceLayer)
    }

    @Test
    fun `toDomainModel maps every known parking type`() {
        BikeParkingType.entries.forEach { type ->
            val domain = entity(type = type.name).toDomainModel()
            assertEquals(type, domain.type)
        }
    }

    @Test
    fun `toDomainModels maps a whole list preserving order`() {
        val domains = listOf(entity(id = "a"), entity(id = "b")).toDomainModels()

        assertEquals(2, domains.size)
        assertEquals("a", domains[0].id)
        assertEquals("b", domains[1].id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toDomainModel rejects an unknown type string`() {
        entity(type = "NOT_A_TYPE").toDomainModel()
    }
}

