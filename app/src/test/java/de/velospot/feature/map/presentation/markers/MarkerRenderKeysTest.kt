package de.velospot.feature.map.presentation.markers

import de.velospot.core.map.LayerVisibility
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for the pure diff-gate keys that decide when the marker renderer must
 * re-serialise a GeoJSON source. The contract:
 *  - same inputs → equal key (skip the rebuild),
 *  - a changed spot / favourite / visibility / mode → different key (rebuild),
 *  - the bulk key is INSENSITIVE to the selection (selecting a spot only touches
 *    the cheap highlight source), while the highlight key IS sensitive to it.
 */
class MarkerRenderKeysTest {

    private fun space(id: String, lat: Double = 50.0, lon: Double = 6.0) = BikeParkingSpace(
        id = id, latitude = lat, longitude = lon,
        type = BikeParkingType.BIKE_RACK, capacity = null, name = null,
        address = null, isCovered = null, imageUrl = null, operator = null,
        sourceLayer = "test"
    )

    private val spaces = listOf(space("a"), space("b"), space("c"))
    private val vis = LayerVisibility()

    private fun state(selected: String? = null, nav: String? = null, favorites: List<String> = emptyList()) =
        MarkerRenderState(
            favoriteIds = favorites,
            selectedSpaceId = selected,
            activeNavigationSpaceId = nav,
            userLocation = null
        )

    // ── Bulk key ──────────────────────────────────────────────────────────────

    @Test
    fun `bulk key is stable for identical inputs`() {
        val k1 = parkingBulkKey(spaces, emptyList(), null, vis, minimalNavMode = false, parkedBike = null)
        val k2 = parkingBulkKey(spaces, emptyList(), null, vis, minimalNavMode = false, parkedBike = null)
        assertEquals(k1, k2)
    }

    @Test
    fun `bulk key does NOT change when only the selection changes`() {
        val noSelection = parkingBulkKey(spaces, emptyList(), null, vis, false, null)
        // Selection lives only in the highlight key, so the bulk key must be identical
        // whether or not a spot is selected.
        val withSelection = parkingBulkKey(spaces, emptyList(), null, vis, false, null)
        assertEquals(noSelection, withSelection)
    }

    @Test
    fun `bulk key changes when a spot id is added or removed`() {
        val full = parkingBulkKey(spaces, emptyList(), null, vis, false, null)
        val fewer = parkingBulkKey(spaces.dropLast(1), emptyList(), null, vis, false, null)
        assertNotEquals(full, fewer)
    }

    @Test
    fun `bulk key changes when a spot becomes a favourite`() {
        val plain = parkingBulkKey(spaces, emptyList(), null, vis, false, null)
        val fav = parkingBulkKey(spaces, listOf("a"), null, vis, false, null)
        assertNotEquals(plain, fav)
    }

    @Test
    fun `bulk key changes when parking layer is toggled off`() {
        val on = parkingBulkKey(spaces, emptyList(), null, vis, false, null)
        val off = parkingBulkKey(spaces, emptyList(), null, vis.copy(showParking = false), false, null)
        assertNotEquals(on, off)
    }

    @Test
    fun `bulk key changes entering and leaving minimal nav mode`() {
        val normal = parkingBulkKey(spaces, emptyList(), null, vis, minimalNavMode = false, parkedBike = null)
        val minimal = parkingBulkKey(spaces, emptyList(), null, vis, minimalNavMode = true, parkedBike = null)
        assertNotEquals(normal, minimal)
    }

    @Test
    fun `bulk key changes when active navigation destination changes (muting)`() {
        val idle = parkingBulkKey(spaces, emptyList(), null, vis, false, null)
        val navigating = parkingBulkKey(spaces, emptyList(), "a", vis, false, null)
        assertNotEquals(idle, navigating)
    }

    // ── Highlight key ───────────────────────────────────────────────────────────

    @Test
    fun `highlight key is empty with no selection or nav`() {
        assertEquals("", parkingHighlightKey(spaces, state(), parkedBike = null))
    }

    @Test
    fun `highlight key changes when the selection changes`() {
        val selA = parkingHighlightKey(spaces, state(selected = "a"), null)
        val selB = parkingHighlightKey(spaces, state(selected = "b"), null)
        assertNotEquals(selA, selB)
        assertNotEquals("", selA)
    }

    @Test
    fun `highlight key stable for identical selection`() {
        val k1 = parkingHighlightKey(spaces, state(selected = "a"), null)
        val k2 = parkingHighlightKey(spaces, state(selected = "a"), null)
        assertEquals(k1, k2)
    }

    // ── Saved places key ──────────────────────────────────────────────────────

    private fun savedPlace(id: String, lat: Double = 50.0, lon: Double = 6.0) =
        SavedPlace(id = id, name = id, latitude = lat, longitude = lon, address = null, addedAt = 0L)

    @Test
    fun `saved places key is empty when hidden`() {
        val places = listOf(savedPlace("p1"))
        assertEquals("", savedPlacesKey(places, visible = false))
    }

    @Test
    fun `saved places key changes when a place is added`() {
        val one = listOf(savedPlace("p1"))
        val two = one + savedPlace("p2", 51.0, 7.0)
        assertNotEquals(savedPlacesKey(one, true), savedPlacesKey(two, true))
    }
}

