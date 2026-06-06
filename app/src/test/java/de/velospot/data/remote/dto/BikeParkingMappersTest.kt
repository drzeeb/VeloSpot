package de.velospot.data.remote.dto

import de.velospot.domain.model.BikeParkingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeParkingMappersTest {

    @Test
    fun `toDomain maps coordinates and layer type`() {
        val dto = GeoJsonFeatureDto(
            id = "feature-1",
            geometry = GeoJsonGeometryDto(
                type = "Point",
                coordinates = listOf(6.641, 49.756)
            ),
            properties = BikeParkingPropertiesDto(
                type = "Fahrradgarage",
                capacity = 20,
                covered = "ja"
            )
        )

        val domain = dto.toDomain(defaultLayer = "fahrradgaragen")

        assertTrue(domain != null)
        assertEquals("feature-1", domain?.id)
        assertEquals(49.756, domain?.latitude)
        assertEquals(6.641, domain?.longitude)
        assertEquals(20, domain?.capacity)
        assertEquals(BikeParkingType.GARAGE, domain?.type)
        assertEquals(null, domain?.isCovered)
        assertEquals(null, domain?.imageUrl)
    }
}

