package de.velospot.feature.wrapped.domain

import java.util.Calendar

/** How often a scheduled "VeloSpot Wrapped" report is generated. */
internal enum class WrappedInterval { DAILY, WEEKLY, MONTHLY }

/**
 * User preference describing when a "VeloSpot Wrapped" report should fire.
 *
 * The design supports weekly and monthly cadences too, even though the default
 * cadence shipped is daily. Times are interpreted in the device's local time
 * zone with **Monday**-based weeks (matching the shared ride statistics).
 *
 * @property dayOfWeek used by [WrappedInterval.WEEKLY]; a `Calendar.*` day constant.
 * @property dayOfMonth used by [WrappedInterval.MONTHLY]; clamped to the month length.
 */
internal data class WrappedSchedule(
    val enabled: Boolean = false,
    val interval: WrappedInterval = WrappedInterval.WEEKLY,
    val dayOfWeek: Int = Calendar.SUNDAY,
    val dayOfMonth: Int = 1,
    val hour: Int = 20,
    val minute: Int = 0
)

