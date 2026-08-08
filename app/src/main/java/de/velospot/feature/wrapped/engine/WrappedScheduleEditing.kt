package de.velospot.feature.wrapped.engine

import de.velospot.feature.wrapped.domain.WrappedInterval
import de.velospot.feature.wrapped.domain.WrappedSchedule
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure, Android-free reducers that turn a single UI edit into a new
 * [WrappedSchedule]. Factored out of the Compose layer so every interval / day /
 * time edit — including its value clamping — is covered by fast JVM unit tests
 * without touching a `Context` or a Composable.
 *
 * Clamping keeps a persisted schedule always valid regardless of the widget that
 * produced the value: `dayOfMonth` to 1–31 (the scheduler maths clamps further to
 * each month's length), `dayOfWeek` to the `Calendar` 1–7 range, and the wall
 * clock to a real 24 h time.
 */
internal object WrappedScheduleEdits {

    /** Enables or disables the automatic schedule. */
    fun withEnabled(schedule: WrappedSchedule, enabled: Boolean): WrappedSchedule =
        schedule.copy(enabled = enabled)

    /** Switches the cadence (daily / weekly / monthly). */
    fun withInterval(schedule: WrappedSchedule, interval: WrappedInterval): WrappedSchedule =
        schedule.copy(interval = interval)

    /** Sets the weekly day, clamped to the `Calendar.SUNDAY..SATURDAY` (1–7) range. */
    fun withDayOfWeek(schedule: WrappedSchedule, dayOfWeek: Int): WrappedSchedule =
        schedule.copy(dayOfWeek = dayOfWeek.coerceIn(1, 7))

    /** Sets the monthly day, clamped to 1–31 (the maths further clamps per month). */
    fun withDayOfMonth(schedule: WrappedSchedule, dayOfMonth: Int): WrappedSchedule =
        schedule.copy(dayOfMonth = dayOfMonth.coerceIn(1, 31))

    /** Sets the fire time, clamped to a valid `00:00..23:59` wall clock. */
    fun withTime(schedule: WrappedSchedule, hour: Int, minute: Int): WrappedSchedule =
        schedule.copy(hour = hour.coerceIn(0, 23), minute = minute.coerceIn(0, 59))
}

/**
 * Pure formatting of the "Next Wrapped" preview line. Uses the platform's
 * localized full date + short time so the weekday and date order match the
 * device locale without any hand-written per-language strings. Android-free (uses
 * only `java.text`), so it is exercised by a JVM unit test with a fixed locale and
 * time zone.
 */
internal object WrappedScheduleFormat {

    /**
     * Formats [nextFireMillis] as e.g. "Sunday, 15 June 2025 20:00" in [locale] and
     * [timeZone] (both default to the device's). Returns `null` when there is no
     * next fire (schedule disabled ⇒ caller passes `null`), so the preview hides.
     */
    fun formatNextFire(
        nextFireMillis: Long?,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String? {
        if (nextFireMillis == null) return null
        val format = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT, locale)
        format.timeZone = timeZone
        return format.format(Date(nextFireMillis))
    }
}

