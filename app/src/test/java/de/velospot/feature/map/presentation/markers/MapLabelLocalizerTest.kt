package de.velospot.feature.map.presentation.markers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [shouldLocalizeLabelLayer] — the pure filter that decides which
 * base-map symbol layers get their `text-field` rewritten to the localized name.
 */
class MapLabelLocalizerTest {

    @Test
    fun `place label with a name is localized`() {
        assertTrue(
            shouldLocalizeLabelLayer(
                layerId = "label_city",
                hasTextField = true,
                hasIconImage = true, // the city dot icon
                sourceLayer = "place"
            )
        )
    }

    @Test
    fun `line-placed road name label is localized`() {
        assertTrue(
            shouldLocalizeLabelLayer(
                layerId = "highway-name-major",
                hasTextField = true,
                hasIconImage = false,
                sourceLayer = "transportation_name"
            )
        )
    }

    @Test
    fun `road route-number shield is skipped by id`() {
        assertFalse(
            shouldLocalizeLabelLayer(
                layerId = "highway-shield-non-us",
                hasTextField = true,
                hasIconImage = true,
                sourceLayer = "transportation_name"
            )
        )
    }

    @Test
    fun `transportation_name layer drawing an icon is skipped even without shield in id`() {
        assertFalse(
            shouldLocalizeLabelLayer(
                layerId = "road_ref",
                hasTextField = true,
                hasIconImage = true,
                sourceLayer = "transportation_name"
            )
        )
    }

    @Test
    fun `our own overlay layers are never touched`() {
        assertFalse(
            shouldLocalizeLabelLayer(
                layerId = "velospot-parking-cluster-count-layer",
                hasTextField = true,
                hasIconImage = false,
                sourceLayer = null
            )
        )
    }

    @Test
    fun `icon-only layers with no text are skipped`() {
        assertFalse(
            shouldLocalizeLabelLayer(
                layerId = "road_one_way_arrow",
                hasTextField = false,
                hasIconImage = true,
                sourceLayer = "transportation"
            )
        )
    }

    @Test
    fun `shield detection is case-insensitive`() {
        assertFalse(
            shouldLocalizeLabelLayer(
                layerId = "US-Interstate-SHIELD",
                hasTextField = true,
                hasIconImage = true,
                sourceLayer = "transportation_name"
            )
        )
    }
}

