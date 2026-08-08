package de.velospot.feature.map.presentation.sheets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import de.velospot.R
import de.velospot.core.locale.LanguagePreferences
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.BikeType
import de.velospot.testsupport.fakeContextWithPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Pure-logic unit tests for the small non-Composable helpers in the map bottom
 * sheets: label/type mapping, byte/distance formatting, the language helpers and
 * the file-level constant tables (touched so their initialisers run under JVM units).
 */
class SheetHelpersTest {

    private val originalLocale = Locale.getDefault()

    @Before fun pinLocale() { Locale.setDefault(Locale.US) }
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }

    // ── BikeGarageSheet.bikeTypeLabelRes ──────────────────────────────────────
    @Test
    fun `bikeTypeLabelRes maps every BikeType to a distinct string res`() {
        val ids = BikeType.entries.map { bikeTypeLabelRes(it) }
        assertEquals(BikeType.entries.size, ids.toSet().size)
        assertEquals(R.string.bike_type_road, bikeTypeLabelRes(BikeType.ROAD))
        assertEquals(R.string.bike_type_ebike, bikeTypeLabelRes(BikeType.EBIKE))
    }

    // ── SelectedSpaceSheet.icon ───────────────────────────────────────────────
    @Test
    fun `parking type icon differs per type`() {
        val garage = BikeParkingType.GARAGE.icon()
        val rack = BikeParkingType.BIKE_RACK.icon()
        val unknown = BikeParkingType.UNKNOWN.icon()
        assertNotNull(garage)
        assertTrue(garage != rack)
        assertTrue(rack != unknown)
    }

    // ── OfflineRegionsSheet.formatSize ────────────────────────────────────────
    @Test
    fun `formatSize switches from MB to GB`() {
        assertEquals("12.0 MB", formatSize(12L * 1024 * 1024))
        assertEquals("1.5 GB", formatSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    // ── SettingsSheet.formatMb ────────────────────────────────────────────────
    @Test
    fun `settings formatMb converts bytes`() {
        assertEquals("3.0", formatMb(3L * 1024 * 1024))
    }

    // ── ParkedBikeSheet.formatDistance ────────────────────────────────────────
    @Test
    fun `parked bike formatDistance rounds metres and switches to km`() {
        assertEquals("350 m", formatDistance(350.0))
        assertEquals("1.2 km", formatDistance(1_200.0))
    }

    // ── LanguageSheet helpers ─────────────────────────────────────────────────
    @Test
    fun `languageFlagForCode returns the flag or a fallback`() {
        assertEquals("🇩🇪", languageFlagForCode("de"))
        assertEquals("🇬🇧", languageFlagForCode("en"))
        assertEquals("🏳️", languageFlagForCode("xx"))
    }

    @Test
    fun `resolveCurrentLanguageCode returns a non-blank code`() {
        val ctx = fakeContextWithPrefs()
        LanguagePreferences.saveLanguageCode(ctx, "it")
        // The app-locale global (AppCompatDelegate) is environment-dependent under
        // JVM units, so only assert the deterministic invariant: a non-blank code.
        assertTrue(resolveCurrentLanguageCode(ctx, "fr").isNotBlank())
    }

    @Test
    fun `applyLanguageSelection persists the chosen code`() {
        val ctx = fakeContextWithPrefs()
        applyLanguageSelection(ctx, "pt")
        assertEquals("pt", LanguagePreferences.getSavedLanguageCode(ctx))
    }

    // ── WelcomeOnboardingSheet.OnboardingPage ─────────────────────────────────
    @Test
    fun `onboarding page defaults have no offline CTA`() {
        val page = OnboardingPage(
            icon = Icons.Default.Add,
            titleRes = R.string.app_name,
            descRes = R.string.app_name
        )
        assertTrue(!page.showOfflineCta)
        assertEquals(R.string.app_name, page.titleRes)
    }

    // ── File-level constant tables (touch so their initialisers run) ──────────
    @Test
    fun `constant tables are populated`() {
        assertEquals(4, WHEEL_PRESETS_METERS.size)
        assertEquals(listOf(5, 10, 15, 20, 30, 40, 50), ROUND_TRIP_DISTANCES_KM)
        assertEquals(5, layerAccentColorCount)
        assertNotNull(IMPROVING_COLOR)
    }
}

