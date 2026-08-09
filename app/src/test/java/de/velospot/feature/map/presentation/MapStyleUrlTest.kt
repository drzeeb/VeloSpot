package de.velospot.feature.map.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for [mapStyleUrl] — the single source of truth for map style
 * resolution. AMOLED is a dark-mode sub-option: it only takes effect while the
 * theme is dark and is ignored in light mode.
 */
class MapStyleUrlTest {

    @Test
    fun `light theme resolves to the light style`() {
        assertEquals(MAP_STYLE_URL_LIGHT, mapStyleUrl(isDarkTheme = false, amoled = false))
    }

    @Test
    fun `dark theme without amoled resolves to the dark style`() {
        assertEquals(MAP_STYLE_URL_DARK, mapStyleUrl(isDarkTheme = true, amoled = false))
    }

    @Test
    fun `dark theme with amoled resolves to the amoled style`() {
        assertEquals(MAP_STYLE_URL_AMOLED, mapStyleUrl(isDarkTheme = true, amoled = true))
    }

    @Test
    fun `amoled is ignored in light mode`() {
        assertEquals(MAP_STYLE_URL_LIGHT, mapStyleUrl(isDarkTheme = false, amoled = true))
    }

    @Test
    fun `amoled defaults to off`() {
        assertEquals(MAP_STYLE_URL_DARK, mapStyleUrl(isDarkTheme = true))
        assertEquals(MAP_STYLE_URL_LIGHT, mapStyleUrl(isDarkTheme = false))
    }
}

