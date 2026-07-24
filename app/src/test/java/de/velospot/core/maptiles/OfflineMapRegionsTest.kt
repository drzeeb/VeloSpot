package de.velospot.core.maptiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMapRegionsTest {

    @Test
    fun `boundsAround centres the box on the given point`() {
        val lat = 49.75
        val lon = 6.64
        val b = OfflineMapRegions.boundsAround(lat, lon, radiusKm = 40.0)

        // The point sits inside the box, and the box is centred on it in latitude.
        assertTrue(b.south < lat && lat < b.north)
        assertTrue(b.west < lon && lon < b.east)
        assertEquals(lat, (b.north + b.south) / 2, 1e-6)
        assertEquals(lon, (b.west + b.east) / 2, 1e-6)
    }

    @Test
    fun `boundsAround widens longitude span to stay square on the ground`() {
        val b = OfflineMapRegions.boundsAround(50.0, 8.0, radiusKm = 40.0)
        val latSpan = b.north - b.south
        val lonSpan = b.east - b.west
        // At 50°N, cos(lat) ~0.64, so the longitude span must be wider than latitude.
        assertTrue("lon span ($lonSpan) should exceed lat span ($latSpan)", lonSpan > latSpan)
    }

    @Test
    fun `larger radius yields a larger box`() {
        val small = OfflineMapRegions.boundsAround(50.0, 8.0, radiusKm = 20.0)
        val large = OfflineMapRegions.boundsAround(50.0, 8.0, radiusKm = 60.0)
        assertTrue((large.north - large.south) > (small.north - small.south))
    }

    @Test
    fun `country bounds cover Germany France and Luxembourg reference points`() {
        val points = mapOf(
            "Berlin" to (52.52 to 13.40),
            "Munich" to (48.14 to 11.58),
            "Paris" to (48.86 to 2.35),
            "Marseille" to (43.30 to 5.37),
            "Luxembourg City" to (49.61 to 6.13),
            "Ajaccio (Corsica)" to (41.93 to 8.74),
        )
        for ((name, p) in points) {
            val (lat, lon) = p
            val covered = OfflineMapRegions.COUNTRY_BOUNDS.any {
                lat in it.south..it.north && lon in it.west..it.east
            }
            assertTrue("$name should be covered by COUNTRY_BOUNDS", covered)
        }
    }

    @Test
    fun `progress fraction is indeterminate until the required count is known`() {
        assertEquals(-1f, OfflineMapRegions.progressFraction(0, 0), 0f)
        assertEquals(-1f, OfflineMapRegions.progressFraction(5, 0), 0f)
    }

    @Test
    fun `progress fraction is the completed ratio and clamps at one`() {
        assertEquals(0.5f, OfflineMapRegions.progressFraction(50, 100), 1e-6f)
        assertEquals(1f, OfflineMapRegions.progressFraction(120, 100), 0f)
    }

    @Test
    fun `country download stops at a lower max zoom than the region download`() {
        assertTrue(OfflineMapRegions.COUNTRY_MAX_ZOOM < OfflineMapRegions.REGION_MAX_ZOOM)
    }
}

