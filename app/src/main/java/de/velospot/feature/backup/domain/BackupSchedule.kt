package de.velospot.feature.backup.domain

import java.util.Calendar

/** How often an automatic VeloSpot backup is created. */
enum class BackupInterval { DAILY, WEEKLY, MONTHLY }

/**
 * User preference describing when an automatic backup should run.
 *
 * Mirrors the "VeloSpot Wrapped" schedule design: times are interpreted in the
 * device's local time zone with **Monday**-based weeks. The default cadence shipped
 * is daily (applied at the persistence boundary), even though this unopinionated
 * data class defaults to WEEKLY.
 *
 * @property dayOfWeek used by [BackupInterval.WEEKLY]; a `Calendar.*` day constant.
 * @property dayOfMonth used by [BackupInterval.MONTHLY]; clamped to the month length.
 */
data class BackupSchedule(
    val enabled: Boolean = false,
    val interval: BackupInterval = BackupInterval.WEEKLY,
    val dayOfWeek: Int = Calendar.SUNDAY,
    val dayOfMonth: Int = 1,
    val hour: Int = 20,
    val minute: Int = 0
)

