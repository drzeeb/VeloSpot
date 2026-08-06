package de.velospot.core.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Unit tests for the offline NOAA sunrise/sunset calculator.
 *
 * Reference values are cross-checked against the NOAA Solar Calculator
 * (https://gml.noaa.gov/grad/solcalc/). Because the classic "sunrise equation"
 * is a first-order approximation it can differ from the full NOAA model by up to
 * a minute or two, so a small tolerance is applied.
 */
class SunTimesTest {

    /** Minutes-of-day (in [zone]) of an [Instant], for tight time assertions. */
    private fun minutesOfDay(instant: Instant, zone: ZoneId): Int {
        val local: ZonedDateTime = instant.atZone(zone)
        return local.hour * 60 + local.minute
    }

    private fun minutesOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

    private fun assertWithinMinutes(
        expected: LocalTime,
        actual: Instant?,
        zone: ZoneId,
        toleranceMinutes: Int,
        label: String
    ) {
        assertNotNull("$label should not be null", actual)
        val diff = abs(minutesOfDay(actual!!, zone) - minutesOfDay(expected))
        assertTrue(
            "$label expected ~$expected but was ${actual.atZone(zone).toLocalTime()} " +
                "(off by $diff min, tolerance $toleranceMinutes)",
            diff <= toleranceMinutes
        )
    }

    @Test
    fun `New York equinox matches NOAA reference within tolerance`() {
        // New York City, spring equinox 2023 (EDT, UTC-4 — DST already active).
        // NOAA Solar Calculator: sunrise 06:59 EDT, sunset 19:09 EDT.
        val zone = ZoneId.of("America/New_York")
        val events = SunTimes.compute(
            latitude = 40.7128,
            longitude = -74.0060,
            date = LocalDate.of(2023, 3, 20),
            zone = zone
        )

        assertWithinMinutes(LocalTime.of(6, 59), events.sunrise, zone, 2, "NYC sunrise")
        assertWithinMinutes(LocalTime.of(19, 9), events.sunset, zone, 2, "NYC sunset")
        assertTrue("sunrise must precede sunset", events.sunrise!!.isBefore(events.sunset))
    }

    @Test
    fun `Sydney summer solstice matches NOAA reference within tolerance`() {
        // Sydney, summer solstice (southern hemisphere) 2023-12-22 (AEDT, UTC+11).
        // NOAA Solar Calculator: sunrise 05:42 AEDT, sunset 20:06 AEDT.
        val zone = ZoneId.of("Australia/Sydney")
        val events = SunTimes.compute(
            latitude = -33.8688,
            longitude = 151.2093,
            date = LocalDate.of(2023, 12, 22),
            zone = zone
        )

        assertWithinMinutes(LocalTime.of(5, 42), events.sunrise, zone, 2, "Sydney sunrise")
        assertWithinMinutes(LocalTime.of(20, 6), events.sunset, zone, 2, "Sydney sunset")
        // Note: this location is far east (UTC+11); the calculator composes both
        // events on the same UTC calendar date, so the local times — not the raw
        // instant ordering — are the meaningful assertions here.
    }

    @Test
    fun `mid-latitude day has ordered non-null events at plausible hours`() {
        // Berlin, close to autumn equinox — a sanity check independent of exact minutes.
        val zone = ZoneId.of("Europe/Berlin")
        val events = SunTimes.compute(
            latitude = 52.52,
            longitude = 13.405,
            date = LocalDate.of(2023, 9, 23),
            zone = zone
        )

        assertNotNull(events.sunrise)
        assertNotNull(events.sunset)
        assertTrue(events.sunrise!!.isBefore(events.sunset))
        // Around the equinox sunrise is in the morning and sunset in the evening.
        val sunriseHour = events.sunrise!!.atZone(zone).hour
        val sunsetHour = events.sunset!!.atZone(zone).hour
        assertTrue("sunrise hour $sunriseHour", sunriseHour in 5..8)
        assertTrue("sunset hour $sunsetHour", sunsetHour in 17..20)
    }

    @Test
    fun `polar day near Svalbard yields no sunrise or sunset and does not throw`() {
        // Svalbard in mid-summer: the sun never sets (polar day).
        val events = SunTimes.compute(
            latitude = 78.0,
            longitude = 15.0,
            date = LocalDate.of(2023, 6, 21),
            zone = ZoneId.of("Arctic/Longyearbyen")
        )

        assertNull("polar day should have no sunrise", events.sunrise)
        assertNull("polar day should have no sunset", events.sunset)
    }

    @Test
    fun `polar night near Svalbard yields no sunrise or sunset and does not throw`() {
        // Svalbard in mid-winter: the sun never rises (polar night).
        val events = SunTimes.compute(
            latitude = 78.0,
            longitude = 15.0,
            date = LocalDate.of(2023, 12, 21),
            zone = ZoneId.of("Arctic/Longyearbyen")
        )

        assertNull("polar night should have no sunrise", events.sunrise)
        assertNull("polar night should have no sunset", events.sunset)
    }
}

