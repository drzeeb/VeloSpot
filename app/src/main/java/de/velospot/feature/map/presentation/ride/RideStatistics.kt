package de.velospot.feature.map.presentation.ride

import de.velospot.domain.model.RecordedRide
import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Aggregate, "stats-nerd" analytics computed over the user's whole ride history.
 *
 * Everything here is derived purely from the persisted [RecordedRide]s — no extra
 * storage is required. The numbers feed the statistics dashboard shown at the top
 * of the "My rides" sheet.
 */
internal data class RideStatistics(
    // ── Totals ────────────────────────────────────────────────────────────────
    val rideCount: Int,
    val totalDistanceMeters: Double,
    val totalElapsedSeconds: Long,
    val totalMovingSeconds: Long,
    val totalElevationGainMeters: Double,
    val totalElevationLossMeters: Double,

    // ── Averages ──────────────────────────────────────────────────────────────
    val avgDistanceMeters: Double,
    val avgElapsedSeconds: Long,
    val avgMovingSpeedMps: Double,
    val avgElevationGainMeters: Double,

    // ── Records / personal bests ────────────────────────────────────────────────
    val longestRideMeters: Double,
    val longestRideDurationSeconds: Long,
    val topSpeedMps: Double,
    val bestAvgSpeedMps: Double,
    val biggestClimbMeters: Double,

    // ── Activity ────────────────────────────────────────────────────────────────
    val firstRideAt: Long?,
    val lastRideAt: Long?,
    val activeDays: Int,
    val longestStreakDays: Int,
    val currentStreakDays: Int,
    val ridesThisWeek: Int,
    val distanceThisWeekMeters: Double,
    val ridesThisMonth: Int,
    val distanceThisMonthMeters: Double,

    // ── Fun facts ───────────────────────────────────────────────────────────────
    val co2SavedGrams: Double,
    val caloriesBurned: Int,
    val earthCircumferencePercent: Double
) {
    val hasData: Boolean get() = rideCount > 0
}

/** Average car tail-pipe emissions in grams of CO₂ per kilometre. */
private const val CAR_CO2_GRAMS_PER_KM = 120.0

/** Rough energy expenditure for moderate cycling, in kcal per kilometre. */
private const val CALORIES_PER_KM = 30.0

/** Earth's equatorial circumference in kilometres. */
private const val EARTH_CIRCUMFERENCE_KM = 40_075.0

/**
 * Crunches the full list of recorded rides into a single [RideStatistics] bundle.
 * Returns an all-zero instance (with `hasData == false`) when there are no rides.
 */
internal fun computeRideStatistics(
    rides: List<RecordedRide>,
    now: Long = System.currentTimeMillis()
): RideStatistics {
    if (rides.isEmpty()) {
        return RideStatistics(
            rideCount = 0,
            totalDistanceMeters = 0.0,
            totalElapsedSeconds = 0,
            totalMovingSeconds = 0,
            totalElevationGainMeters = 0.0,
            totalElevationLossMeters = 0.0,
            avgDistanceMeters = 0.0,
            avgElapsedSeconds = 0,
            avgMovingSpeedMps = 0.0,
            avgElevationGainMeters = 0.0,
            longestRideMeters = 0.0,
            longestRideDurationSeconds = 0,
            topSpeedMps = 0.0,
            bestAvgSpeedMps = 0.0,
            biggestClimbMeters = 0.0,
            firstRideAt = null,
            lastRideAt = null,
            activeDays = 0,
            longestStreakDays = 0,
            currentStreakDays = 0,
            ridesThisWeek = 0,
            distanceThisWeekMeters = 0.0,
            ridesThisMonth = 0,
            distanceThisMonthMeters = 0.0,
            co2SavedGrams = 0.0,
            caloriesBurned = 0,
            earthCircumferencePercent = 0.0
        )
    }

    val totalDistance = rides.sumOf { it.distanceMeters }
    val totalElapsed = rides.sumOf { it.elapsedSeconds }
    val totalMoving = rides.sumOf { it.movingSeconds }
    val totalGain = rides.sumOf { it.elevationGainMeters }
    val totalLoss = rides.sumOf { it.elevationLossMeters }

    val avgMovingSpeed = if (totalMoving > 0) totalDistance / totalMoving else 0.0

    // Day-bucketed activity (calendar day in the device's local time zone).
    val rideDays = rides
        .map { dayBucket(it.startedAt) }
        .toSortedSet()
        .toList()

    val (longestStreak, currentStreak) = computeStreaks(rideDays, now)

    val weekStart = startOfWeek(now)
    val monthStart = startOfMonth(now)

    val thisWeek = rides.filter { it.startedAt >= weekStart }
    val thisMonth = rides.filter { it.startedAt >= monthStart }

    val totalDistanceKm = totalDistance / 1_000.0

    return RideStatistics(
        rideCount = rides.size,
        totalDistanceMeters = totalDistance,
        totalElapsedSeconds = totalElapsed,
        totalMovingSeconds = totalMoving,
        totalElevationGainMeters = totalGain,
        totalElevationLossMeters = totalLoss,
        avgDistanceMeters = totalDistance / rides.size,
        avgElapsedSeconds = (totalElapsed.toDouble() / rides.size).roundToLong(),
        avgMovingSpeedMps = avgMovingSpeed,
        avgElevationGainMeters = totalGain / rides.size,
        longestRideMeters = rides.maxOf { it.distanceMeters },
        longestRideDurationSeconds = rides.maxOf { it.elapsedSeconds },
        topSpeedMps = rides.maxOf { it.maxSpeedMps },
        bestAvgSpeedMps = rides.maxOf { it.avgSpeedMps },
        biggestClimbMeters = rides.maxOf { it.elevationGainMeters },
        firstRideAt = rides.minOf { it.startedAt },
        lastRideAt = rides.maxOf { it.startedAt },
        activeDays = rideDays.size,
        longestStreakDays = longestStreak,
        currentStreakDays = currentStreak,
        ridesThisWeek = thisWeek.size,
        distanceThisWeekMeters = thisWeek.sumOf { it.distanceMeters },
        ridesThisMonth = thisMonth.size,
        distanceThisMonthMeters = thisMonth.sumOf { it.distanceMeters },
        co2SavedGrams = totalDistanceKm * CAR_CO2_GRAMS_PER_KM,
        caloriesBurned = (totalDistanceKm * CALORIES_PER_KM).roundToInt(),
        earthCircumferencePercent = totalDistanceKm / EARTH_CIRCUMFERENCE_KM * 100.0
    )
}

/** Collapses a timestamp to a stable "calendar day" key (days since the epoch). */
private fun dayBucket(timestamp: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = timestamp
        clearTime()
    }
    return cal.timeInMillis / DAY_MILLIS
}

/**
 * Returns `longest streak` and `current streak` (both in consecutive calendar
 * days) from the sorted, de-duplicated list of active-day buckets.
 */
private fun computeStreaks(sortedDays: List<Long>, now: Long): Pair<Int, Int> {
    if (sortedDays.isEmpty()) return 0 to 0

    var longest = 1
    var run = 1
    for (i in 1 until sortedDays.size) {
        run = if (sortedDays[i] == sortedDays[i - 1] + 1) run + 1 else 1
        if (run > longest) longest = run
    }

    // The current streak only counts if the last active day is today or yesterday.
    val today = dayBucket(now)
    val lastDay = sortedDays.last()
    val current = if (lastDay == today || lastDay == today - 1) {
        var c = 1
        var idx = sortedDays.size - 1
        while (idx > 0 && sortedDays[idx] == sortedDays[idx - 1] + 1) {
            c++
            idx--
        }
        c
    } else {
        0
    }

    return longest to current
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

private fun Calendar.clearTime() {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

/** Start-of-week timestamp (Monday 00:00) containing [timestamp]. */
private fun startOfWeek(timestamp: Long): Long {
    val cal = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        timeInMillis = timestamp
        clearTime()
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    }
    return cal.timeInMillis
}

private fun startOfMonth(timestamp: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = timestamp
        clearTime()
        set(Calendar.DAY_OF_MONTH, 1)
    }
    return cal.timeInMillis
}

