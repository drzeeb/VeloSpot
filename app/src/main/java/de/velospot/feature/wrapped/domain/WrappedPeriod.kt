package de.velospot.feature.wrapped.domain

import java.util.Calendar

/** The kind of calendar window a [WrappedPeriod] covers. */
internal enum class WrappedPeriodType { DAY, WEEK, MONTH, YEAR, CUSTOM }

/**
 * A half-open epoch-millis range `[startInclusive, endExclusive)` describing the
 * window a "VeloSpot Wrapped" report is computed over.
 *
 * All builders operate in the device's **local** time zone and treat **Monday**
 * as the first day of the week, matching the existing `startOfWeek` used by the
 * shared ride statistics. Everything here is pure (no Android, only `java.util`).
 */
internal data class WrappedPeriod(
    val type: WrappedPeriodType,
    val startInclusive: Long,
    val endExclusive: Long
) {
    /** Length of the window in milliseconds (used to shift CUSTOM windows). */
    val lengthMillis: Long get() = endExclusive - startInclusive

    companion object {
        private fun calendar(now: Long): Calendar =
            Calendar.getInstance().apply {
                firstDayOfWeek = Calendar.MONDAY
                timeInMillis = now
            }

        private fun Calendar.clearTime() {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        /** The full calendar day (00:00–24:00 local) containing [now]. */
        fun day(now: Long): WrappedPeriod {
            val start = calendar(now).apply { clearTime() }
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            return WrappedPeriod(WrappedPeriodType.DAY, start.timeInMillis, end.timeInMillis)
        }

        /** The Monday–Sunday week (Monday 00:00 → next Monday 00:00) containing [now]. */
        fun week(now: Long): WrappedPeriod {
            val start = calendar(now).apply {
                clearTime()
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, 1) }
            return WrappedPeriod(WrappedPeriodType.WEEK, start.timeInMillis, end.timeInMillis)
        }

        /** The calendar month (1st 00:00 → 1st of next month 00:00) containing [now]. */
        fun month(now: Long): WrappedPeriod {
            val start = calendar(now).apply {
                clearTime()
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            return WrappedPeriod(WrappedPeriodType.MONTH, start.timeInMillis, end.timeInMillis)
        }

        /** The calendar year (Jan 1 00:00 → next Jan 1 00:00) containing [now]. */
        fun year(now: Long): WrappedPeriod {
            val start = calendar(now).apply {
                clearTime()
                set(Calendar.DAY_OF_YEAR, 1)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
            return WrappedPeriod(WrappedPeriodType.YEAR, start.timeInMillis, end.timeInMillis)
        }

        /** An explicit custom window `[from, to)`. */
        fun custom(from: Long, to: Long): WrappedPeriod =
            WrappedPeriod(WrappedPeriodType.CUSTOM, from, to)

        /**
         * The immediately-preceding equal-length window before [period].
         *
         * For DAY/WEEK/MONTH/YEAR this is the natural previous calendar bucket
         * (computed via calendar fields so it stays correct across DST and months
         * of unequal length). For CUSTOM the window is shifted back by exactly its
         * own length (`end - start`).
         */
        fun previous(period: WrappedPeriod): WrappedPeriod = when (period.type) {
            WrappedPeriodType.DAY -> shiftBucket(period, Calendar.DAY_OF_MONTH)
            WrappedPeriodType.WEEK -> shiftBucket(period, Calendar.WEEK_OF_YEAR)
            WrappedPeriodType.MONTH -> shiftBucket(period, Calendar.MONTH)
            WrappedPeriodType.YEAR -> shiftBucket(period, Calendar.YEAR)
            WrappedPeriodType.CUSTOM -> {
                val len = period.lengthMillis
                WrappedPeriod(
                    WrappedPeriodType.CUSTOM,
                    period.startInclusive - len,
                    period.endExclusive - len
                )
            }
        }

        /** Steps both bounds back by one unit of [field], preserving the type. */
        private fun shiftBucket(period: WrappedPeriod, field: Int): WrappedPeriod {
            val start = Calendar.getInstance().apply {
                firstDayOfWeek = Calendar.MONDAY
                timeInMillis = period.startInclusive
                add(field, -1)
            }
            val end = Calendar.getInstance().apply {
                firstDayOfWeek = Calendar.MONDAY
                timeInMillis = period.endExclusive
                add(field, -1)
            }
            return WrappedPeriod(period.type, start.timeInMillis, end.timeInMillis)
        }
    }
}

