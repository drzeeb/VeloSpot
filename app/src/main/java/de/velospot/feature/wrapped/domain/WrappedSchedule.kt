package de.velospot.feature.wrapped.domain

import java.util.Calendar

/** How often a scheduled "VeloSpot Wrapped" report is generated. */
internal enum class WrappedInterval { DAILY, WEEKLY, MONTHLY }

/**
 * Which time window a scheduled "VeloSpot Wrapped" report summarises, relative to
 * the fire instant. The concrete window depends on the [WrappedInterval] it is
 * combined with — see [de.velospot.feature.wrapped.engine.WrappedScheduleMath.periodForFire]:
 *
 * * [CALENDAR_CURRENT] — the current, still-running calendar bucket: **today** /
 *   the current Monday–Sunday week / the current calendar month. This is the
 *   shipped default and preserves the historical behaviour.
 * * [CALENDAR_PREVIOUS] — the previous, fully-completed calendar bucket:
 *   **yesterday** / last calendar week / last calendar month. Backs the daily
 *   "yesterday" choice.
 * * [ROLLING] — a rolling window ending with today, sized to the interval:
 *   the **last 7 days** for weekly and the **last 28/29/30/31 days** (matching the
 *   fire month's length) for monthly. For daily this coincides with today.
 */
internal enum class WrappedPeriodMode { CALENDAR_CURRENT, CALENDAR_PREVIOUS, ROLLING }

/**
 * User preference describing when a "VeloSpot Wrapped" report should fire.
 *
 * The design supports weekly and monthly cadences too, even though the default
 * cadence shipped is daily. Times are interpreted in the device's local time
 * zone with **Monday**-based weeks (matching the shared ride statistics).
 *
 * @property dayOfWeek used by [WrappedInterval.WEEKLY]; a `Calendar.*` day constant.
 * @property dayOfMonth used by [WrappedInterval.MONTHLY]; clamped to the month length.
 * @property periodMode which window the report covers relative to the fire instant
 *   (see [WrappedPeriodMode]). Defaults to [WrappedPeriodMode.CALENDAR_CURRENT] so
 *   the historical "current running bucket" behaviour is preserved.
 * @property notifyOnGenerate whether a notification is posted when a report is
 *   generated. The report is always saved regardless; this only gates the alert.
 */
internal data class WrappedSchedule(
    val enabled: Boolean = false,
    val interval: WrappedInterval = WrappedInterval.WEEKLY,
    val dayOfWeek: Int = Calendar.SUNDAY,
    val dayOfMonth: Int = 1,
    val hour: Int = 20,
    val minute: Int = 0,
    val periodMode: WrappedPeriodMode = WrappedPeriodMode.CALENDAR_CURRENT,
    val notifyOnGenerate: Boolean = true
)

