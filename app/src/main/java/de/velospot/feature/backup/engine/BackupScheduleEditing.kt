package de.velospot.feature.backup.engine

import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule

/**
 * Pure, Android-free reducers that turn a single UI edit into a new
 * [BackupSchedule], plus the "can this schedule be enabled?" rule. Factored out of
 * the Compose layer so every interval / day / time edit — including its value
 * clamping — and the enable gate are covered by fast JVM unit tests without a
 * `Context` or a Composable. Mirrors the "VeloSpot Wrapped" schedule editing.
 */
object BackupScheduleEdits {

    /** Enables or disables the automatic schedule. */
    fun withEnabled(schedule: BackupSchedule, enabled: Boolean): BackupSchedule =
        schedule.copy(enabled = enabled)

    /** Switches the cadence (daily / weekly / monthly). */
    fun withInterval(schedule: BackupSchedule, interval: BackupInterval): BackupSchedule =
        schedule.copy(interval = interval)

    /** Sets the weekly day, clamped to the `Calendar.SUNDAY..SATURDAY` (1–7) range. */
    fun withDayOfWeek(schedule: BackupSchedule, dayOfWeek: Int): BackupSchedule =
        schedule.copy(dayOfWeek = dayOfWeek.coerceIn(1, 7))

    /** Sets the monthly day, clamped to 1–31 (the maths further clamps per month). */
    fun withDayOfMonth(schedule: BackupSchedule, dayOfMonth: Int): BackupSchedule =
        schedule.copy(dayOfMonth = dayOfMonth.coerceIn(1, 31))

    /** Sets the fire time, clamped to a valid `00:00..23:59` wall clock. */
    fun withTime(schedule: BackupSchedule, hour: Int, minute: Int): BackupSchedule =
        schedule.copy(hour = hour.coerceIn(0, 23), minute = minute.coerceIn(0, 59))

    /**
     * Whether the automatic backup may be turned **on**: it needs both a chosen
     * destination folder and a stored passphrase (the unattended worker cannot
     * prompt for either). Pure, so it is unit-tested directly.
     */
    fun canEnable(hasDestination: Boolean, hasPassphrase: Boolean): Boolean =
        hasDestination && hasPassphrase
}

