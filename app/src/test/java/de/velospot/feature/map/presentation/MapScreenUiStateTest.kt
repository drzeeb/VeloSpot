package de.velospot.feature.map.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MapScreenUiState] — the plain (non-Composable) sheet/menu
 * visibility state holder. Exercises every open/close transition and the
 * "opening a sheet dismisses the menu" invariant.
 */
class MapScreenUiStateTest {

    @Test
    fun `menu expands and collapses`() {
        val s = MapScreenUiState()
        assertFalse(s.isMenuExpanded)
        assertFalse(s.isSettingsSheetVisible)

        s.expandMenu()
        assertTrue(s.isMenuExpanded)
        assertTrue(s.isSettingsSheetVisible)

        s.dismissMenu()
        assertFalse(s.isMenuExpanded)
        assertFalse(s.isSettingsSheetVisible)
    }

    @Test
    fun `opening a sheet from the menu dismisses the menu`() {
        val openers: List<Pair<(MapScreenUiState) -> Unit, (MapScreenUiState) -> Boolean>> = listOf(
            Pair({ s -> s.openFavorites() }, { s -> s.isFavoritesSheetVisible }),
            Pair({ s -> s.openLanguage() }, { s -> s.isLanguageSheetVisible }),
            Pair({ s -> s.openLayers() }, { s -> s.isLayersSheetVisible }),
            Pair({ s -> s.openNavigationView() }, { s -> s.isNavigationViewSheetVisible }),
            Pair({ s -> s.openAbout() }, { s -> s.isAboutSheetVisible }),
            Pair({ s -> s.openRides() }, { s -> s.isRidesSheetVisible }),
            Pair({ s -> s.openRoundTrip() }, { s -> s.isRoundTripSheetVisible }),
            Pair({ s -> s.openPlannedRoutes() }, { s -> s.isPlannedRoutesSheetVisible }),
            Pair({ s -> s.openBikeGarage() }, { s -> s.isBikeGarageSheetVisible }),
        )

        openers.forEach { (open, isVisible) ->
            val s = MapScreenUiState()
            s.expandMenu()
            open(s)
            assertTrue("sheet should be visible after opening", isVisible(s))
            assertFalse("menu should be dismissed when a sheet opens", s.isMenuExpanded)
            assertFalse(s.isSettingsSheetVisible)
        }
    }

    @Test
    fun `each sheet closes independently`() {
        val toggles: List<Triple<(MapScreenUiState) -> Unit, (MapScreenUiState) -> Unit, (MapScreenUiState) -> Boolean>> =
            listOf(
                Triple({ s -> s.openFavorites() }, { s -> s.closeFavorites() }, { s -> s.isFavoritesSheetVisible }),
                Triple({ s -> s.openLanguage() }, { s -> s.closeLanguage() }, { s -> s.isLanguageSheetVisible }),
                Triple({ s -> s.openLayers() }, { s -> s.closeLayers() }, { s -> s.isLayersSheetVisible }),
                Triple({ s -> s.openNavigationView() }, { s -> s.closeNavigationView() }, { s -> s.isNavigationViewSheetVisible }),
                Triple({ s -> s.openAbout() }, { s -> s.closeAbout() }, { s -> s.isAboutSheetVisible }),
                Triple({ s -> s.openRides() }, { s -> s.closeRides() }, { s -> s.isRidesSheetVisible }),
                Triple({ s -> s.openRoundTrip() }, { s -> s.closeRoundTrip() }, { s -> s.isRoundTripSheetVisible }),
                Triple({ s -> s.openPlannedRoutes() }, { s -> s.closePlannedRoutes() }, { s -> s.isPlannedRoutesSheetVisible }),
                Triple({ s -> s.openBikeGarage() }, { s -> s.closeBikeGarage() }, { s -> s.isBikeGarageSheetVisible }),
            )

        toggles.forEach { (open, close, isVisible) ->
            val s = MapScreenUiState()
            open(s)
            assertTrue(isVisible(s))
            close(s)
            assertFalse(isVisible(s))
        }
    }

    @Test
    fun `settings sub-sheets open and close without touching the menu`() {
        val s = MapScreenUiState()

        s.openDisplaySettings()
        assertTrue(s.isDisplaySettingsSheetVisible)
        s.closeDisplaySettings()
        assertFalse(s.isDisplaySettingsSheetVisible)

        s.openNavRouting()
        assertTrue(s.isNavRoutingSheetVisible)
        s.closeNavRouting()
        assertFalse(s.isNavRoutingSheetVisible)
    }
}

