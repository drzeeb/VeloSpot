package de.velospot.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Unit tests for the pure, injectable-`now` golden-hour visibility rule.
 *
 * The FAB is visible only inside `[event - leadTime, event]` and hides the moment
 * the event has passed.
 */
class SunAlertWindowTest {

    private val sunrise: Instant = Instant.parse("2023-03-20T06:00:00Z")
    private val sunset: Instant = Instant.parse("2023-03-20T18:00:00Z")
    private val lead: Duration = Duration.ofMinutes(30)

    @Test
    fun `now exactly at event is visible (inclusive upper bound)`() {
        val events = SunTimes.SunEvents(sunrise = sunrise, sunset = null)
        val state = activeSunAlert(sunrise, events, lead)
        assertEquals(SunEventKind.SUNRISE, state?.kind)
        assertEquals(sunrise, state?.eventTime)
    }

    @Test
    fun `now at event minus leadTime is visible (inclusive lower bound)`() {
        val events = SunTimes.SunEvents(sunrise = sunrise, sunset = null)
        val now = sunrise.minus(lead)
        val state = activeSunAlert(now, events, lead)
        assertEquals(SunEventKind.SUNRISE, state?.kind)
    }

    @Test
    fun `now before the window (event minus 31 min) is hidden`() {
        val events = SunTimes.SunEvents(sunrise = sunrise, sunset = null)
        val now = sunrise.minus(Duration.ofMinutes(31))
        assertNull(activeSunAlert(now, events, lead))
    }

    @Test
    fun `now just after the event is hidden`() {
        // Key requirement: the FAB disappears once the event has passed.
        val events = SunTimes.SunEvents(sunrise = sunrise, sunset = null)
        val now = sunrise.plus(Duration.ofMinutes(1))
        assertNull(activeSunAlert(now, events, lead))
    }

    @Test
    fun `sunset window returns SUNSET`() {
        val events = SunTimes.SunEvents(sunrise = null, sunset = sunset)
        val now = sunset.minus(Duration.ofMinutes(10))
        val state = activeSunAlert(now, events, lead)
        assertEquals(SunEventKind.SUNSET, state?.kind)
        assertEquals(sunset, state?.eventTime)
    }

    @Test
    fun `both events null (polar) returns null`() {
        val events = SunTimes.SunEvents(sunrise = null, sunset = null)
        assertNull(activeSunAlert(Instant.parse("2023-06-21T12:00:00Z"), events, lead))
    }

    @Test
    fun `when both windows overlap the nearer event wins`() {
        // Two events only 20 minutes apart, both within the 30-minute lead window of now.
        val nearEvent = Instant.parse("2023-03-20T06:10:00Z")
        val farEvent = Instant.parse("2023-03-20T06:25:00Z")
        val now = Instant.parse("2023-03-20T06:00:00Z")
        // sunrise is the nearer event here.
        val events = SunTimes.SunEvents(sunrise = nearEvent, sunset = farEvent)
        val state = activeSunAlert(now, events, lead)
        assertEquals(SunEventKind.SUNRISE, state?.kind)
        assertEquals(nearEvent, state?.eventTime)
    }
}

