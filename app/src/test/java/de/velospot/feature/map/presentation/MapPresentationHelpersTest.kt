package de.velospot.feature.map.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.Modifier
import de.velospot.R
import de.velospot.core.sensors.SensorSnapshot
import de.velospot.domain.model.BikeParkingType
import de.velospot.domain.model.LiveRideStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.Duration
import java.util.Locale

/**
 * Pure-logic unit tests for the small, non-Composable helpers extracted from the
 * map overlay/HUD Composables (formatting, label mapping, elevation profile maths).
 */
class MapPresentationHelpersTest {

    // The formatting helpers use the default locale's decimal separator; pin US so
    // the "12.3"/"1.4 km" assertions are deterministic regardless of the host JVM.
    private val originalLocale = Locale.getDefault()

    @Before fun pinLocale() { Locale.setDefault(Locale.US) }
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }

    // ── WeatherChip.formatCelsius ─────────────────────────────────────────────
    @Test
    fun `formatCelsius rounds to a whole degree`() {
        assertEquals("12°", formatCelsius(12.3))
        assertEquals("13°", formatCelsius(12.6))
        assertEquals("-1°", formatCelsius(-0.6))
    }

    // ── MapTurnBanner.maneuverLabel / formatTurnDistance ──────────────────────
    @Test
    fun `maneuverLabel maps signed angle to the right localized turn`() {
        assertEquals(R.string.nav_turn_slight_left, maneuverLabel(-20.0))
        assertEquals(R.string.nav_turn_slight_right, maneuverLabel(20.0))
        assertEquals(R.string.nav_turn_left, maneuverLabel(-90.0))
        assertEquals(R.string.nav_turn_right, maneuverLabel(90.0))
        assertEquals(R.string.nav_turn_sharp_left, maneuverLabel(-160.0))
        assertEquals(R.string.nav_turn_sharp_right, maneuverLabel(160.0))
    }

    @Test
    fun `formatTurnDistance rounds metres and switches to km`() {
        assertEquals("120 m", formatTurnDistance(123.0))
        assertEquals("0 m", formatTurnDistance(4.0))
        assertEquals("1.4 km", formatTurnDistance(1_400.0))
    }

    // ── TripComputerHud.heroSpeedMps / formatEta / formatGrade ────────────────
    @Test
    fun `heroSpeedMps prefers wheel sensor over gps speed`() {
        val stats = LiveRideStats(currentSpeedMps = 3f)
        assertEquals(7f, heroSpeedMps(stats, SensorSnapshot(speedMps = 7f)))
        assertEquals(3f, heroSpeedMps(stats, SensorSnapshot(speedMps = null)))
        assertEquals(3f, heroSpeedMps(stats, null))
    }

    @Test
    fun `formatGrade shows a signed one-decimal percentage`() {
        assertEquals("+4.2 %", formatGrade(4.2f))
        assertEquals("-3.0 %", formatGrade(-3.0f))
    }

    @Test
    fun `formatEta produces a non-blank wall-clock string`() {
        assertTrue(formatEta(600.0).isNotBlank())
    }

    // ── SunAlertFab.formatCountdown ───────────────────────────────────────────
    @Test
    fun `formatCountdown formats mm ss and clamps negatives`() {
        assertEquals("01:05", formatCountdown(Duration.ofSeconds(65)))
        assertEquals("10:00", formatCountdown(Duration.ofMinutes(10)))
        assertEquals("00:00", formatCountdown(Duration.ofSeconds(-30)))
    }

    // ── MapActionsSpeedDial ───────────────────────────────────────────────────
    @Test
    fun `SpeedDialAction carries its label icon and callback`() {
        var clicked = false
        val action = SpeedDialAction(
            label = "Plan",
            icon = Icons.Default.Add,
            onClick = { clicked = true }
        )
        assertEquals("Plan", action.label)
        action.onClick()
        assertTrue(clicked)
        // Touch the file's layout constants so their initialisers are exercised.
        assertTrue(ITEM_SPACING.value > 0f)
        assertTrue(STACK_OFFSET.value > 0f)
        assertTrue(STACK_BOW.value > 0f)
    }

    // ── MapUiActionComponents ─────────────────────────────────────────────────
    @Test
    fun `bike parking type label resolves the localized string`() {
        val context = mock<android.content.Context> {
            on { getString(R.string.type_garage) } doReturn "Garage"
            on { getString(R.string.type_bike_rack) } doReturn "Bike rack"
            on { getString(R.string.type_unknown) } doReturn "Unknown"
        }
        assertEquals("Garage", BikeParkingType.GARAGE.label(context))
        assertEquals("Bike rack", BikeParkingType.BIKE_RACK.label(context))
        assertEquals("Unknown", BikeParkingType.UNKNOWN.label(context))
    }

    @Test
    fun `headingSemantics returns a non-empty modifier`() {
        assertTrue(Modifier.headingSemantics() != Modifier)
    }

    // ── MapOverlays ───────────────────────────────────────────────────────────
    @Test
    fun `formatMb converts bytes to megabytes`() {
        assertEquals("1.0", formatMb(1024L * 1024L))
        assertEquals("2.5", formatMb((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `map menu card state and actions can be built with defaults`() {
        val state = MapMenuCardState(
            favoritesCount = 3,
            isDarkTheme = true,
            currentLanguageFlag = "🇬🇧",
            isExpanded = false
        )
        assertEquals(3, state.favoritesCount)
        assertTrue(state.isDarkTheme)
        assertTrue(state.keepScreenOnEnabled)

        var expanded = false
        val actions = MapMenuCardActions(
            onExpand = { expanded = true },
            onDismiss = {},
            onOpenFavorites = {},
            onOpenLanguage = {},
            onToggleDarkMode = {}
        )
        actions.onExpand()
        assertTrue(expanded)
        // The defaulted no-op callbacks are still invokable.
        actions.onOpenLayers()
        actions.onOpenSensors()
    }

    // ── RouteElevationProfile.buildElevationProfile ───────────────────────────
    @Test
    fun `buildElevationProfile returns null with fewer than two elevations`() {
        assertEquals(
            null,
            buildElevationProfile(listOf(ElevSample(49.75, 6.64, 100.0)))
        )
        assertEquals(
            null,
            buildElevationProfile(
                listOf(
                    ElevSample(49.75, 6.64, null),
                    ElevSample(49.76, 6.65, null)
                )
            )
        )
    }

    @Test
    fun `buildElevationProfile computes distances min max and raw ascent`() {
        val profile = buildElevationProfile(
            listOf(
                ElevSample(49.750, 6.640, 100.0),
                ElevSample(49.755, 6.640, 130.0),
                ElevSample(49.760, 6.640, 110.0)
            )
        )
        assertNotNull(profile)
        profile!!
        assertEquals(3, profile.distances.size)
        assertEquals(0.0, profile.distances.first(), 0.0)
        assertTrue(profile.distances.last() > profile.distances.first())
        assertEquals(100.0, profile.minElev, 1e-9)
        assertEquals(130.0, profile.maxElev, 1e-9)
        assertEquals(30.0, profile.ascentMeters, 1e-9)  // 100→130
        assertEquals(20.0, profile.descentMeters, 1e-9) // 130→110
    }

    @Test
    fun `buildElevationProfile smooths noisy altitude when requested`() {
        val profile = buildElevationProfile(
            listOf(
                ElevSample(49.750, 6.640, 100.0),
                ElevSample(49.755, 6.640, 200.0),
                ElevSample(49.760, 6.640, 100.0)
            ),
            smooth = true
        )
        assertNotNull(profile)
        // Smoothing dampens the 100 m spike so the plotted max stays below the raw peak.
        assertTrue(profile!!.maxElev < 200.0)
    }
}

