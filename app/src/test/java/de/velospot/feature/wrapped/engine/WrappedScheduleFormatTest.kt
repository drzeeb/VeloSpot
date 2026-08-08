package de.velospot.feature.wrapped.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * JVM coverage for the pure "Next Wrapped" preview formatting used by the Phase-5
 * settings UI. A fixed locale + time zone keep the assertion deterministic.
 */
class WrappedScheduleFormatTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** UTC epoch-millis for the given wall-clock fields. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `null next fire yields null (disabled schedule hides the preview)`() {
        assertNull(WrappedScheduleFormat.formatNextFire(null, Locale.ENGLISH, utc))
    }

    @Test
    fun `formats a full localized date with the weekday and time`() {
        // 15 June 2025 is a Sunday.
        val label = WrappedScheduleFormat.formatNextFire(
            at(2025, 6, 15, 20, 0), Locale.ENGLISH, utc
        )!!
        assertTrue(label, label.contains("Sunday"))
        assertTrue(label, label.contains("2025"))
    }

    @Test
    fun `honours the requested locale`() {
        val label = WrappedScheduleFormat.formatNextFire(
            at(2025, 6, 15, 20, 0), Locale.GERMAN, utc
        )!!
        assertTrue(label, label.contains("Sonntag"))
    }

    @Test
    fun `honours the requested time zone`() {
        // The same instant reads one hour later in CET (UTC+1) than in UTC, so the
        // localized strings must differ — proving the zone is applied.
        val instant = at(2025, 1, 10, 22, 30)
        val cet = TimeZone.getTimeZone("Europe/Berlin")
        val inUtc = WrappedScheduleFormat.formatNextFire(instant, Locale.ENGLISH, utc)
        val inCet = WrappedScheduleFormat.formatNextFire(instant, Locale.ENGLISH, cet)
        assertEquals(true, inUtc != inCet)
    }

    @Test
    fun `same instant in different zones formats differently`() {
        val instant = at(2025, 6, 15, 23, 30)
        val inUtc = WrappedScheduleFormat.formatNextFire(instant, Locale.ENGLISH, utc)
        val inTokyo = WrappedScheduleFormat.formatNextFire(
            instant, Locale.ENGLISH, TimeZone.getTimeZone("Asia/Tokyo")
        )
        // Tokyo is UTC+9, so the wall-clock date rolls over to the 16th.
        assertEquals(true, inUtc != inTokyo)
    }
}

