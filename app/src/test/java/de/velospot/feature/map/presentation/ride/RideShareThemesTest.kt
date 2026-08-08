package de.velospot.feature.map.presentation.ride

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Pure-JVM unit tests for the shareable-card **data holders** and the theme
 * catalogue. The Canvas renderers themselves need a real graphics surface
 * (instrumented) and are excluded from coverage, but their plain-data inputs and
 * the colour themes are fully testable here.
 */
class RideShareThemesTest {

    @Test
    fun `theme catalogue exposes all six themes with a default`() {
        assertEquals(6, RideShareThemes.all.size)
        assertEquals(RideShareThemes.Aurora, RideShareThemes.default)
        // Every theme has a stable, unique id.
        assertEquals(RideShareThemes.all.size, RideShareThemes.all.map { it.id }.toSet().size)
    }

    @Test
    fun `gradient accessors expose the three stops`() {
        val t = RideShareThemes.Sunset
        assertEquals(t.gradient[0], t.gradientTop)
        assertEquals(t.gradient[1], t.gradientMid)
        assertEquals(t.gradient[2], t.gradientBottom)
    }

    @Test
    fun `equality and hashCode are keyed on the id`() {
        assertEquals(RideShareThemes.Ocean, RideShareThemes.Ocean)
        assertEquals(RideShareThemes.Ocean.hashCode(), RideShareThemes.Ocean.hashCode())
        assertNotEquals(RideShareThemes.Ocean, RideShareThemes.Forest)
    }

    @Test
    fun `ride share labels build without a weather chip`() {
        val labels = RideShareLabels(
            headline = "12 km",
            durationLabel = "45:00",
            avgSpeedLabel = "16 km/h",
            elevationLabel = "120 m",
            maxSpeedLabel = "38 km/h",
            footer = "Recorded with VeloSpot"
        )
        assertEquals("12 km", labels.headline)
        assertNull(labels.weather)
    }

    @Test
    fun `ride share weather label and map layer wrap their bitmaps`() {
        val bitmap = mock<Bitmap>()
        val weather = RideShareWeatherLabel(icon = bitmap, temperature = "12 °C")
        assertEquals("12 °C", weather.temperature)
        assertNull(weather.wind)

        val layer = RideMapLayer(mapBitmap = bitmap, routePointsImagePx = emptyList())
        assertTrue(layer.routePointsImagePx.isEmpty())
        assertNotNull(layer.mapBitmap)
    }

    @Test
    fun `stats share data holders carry their pre-formatted text`() {
        val cell = StatsShareCell(emoji = "🚴", value = "128", label = "rides")
        assertEquals("128", cell.value)
        val badge = StatsShareBadge(emoji = "🏔️", text = "1× Everest")
        assertEquals("1× Everest", badge.text)
        val labels = StatsShareLabels(
            headline = "TOTAL DISTANCE",
            subtitle = "128 rides",
            cells = listOf(cell),
            badges = listOf(badge),
            footer = "Recorded with VeloSpot"
        )
        assertEquals(1, labels.cells.size)
        assertEquals(1, labels.badges.size)
    }

    private fun assertNull(value: Any?) = assertEquals(null, value)
}

