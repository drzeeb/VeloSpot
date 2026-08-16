package de.velospot.feature.wrapped.engine

import de.velospot.core.stats.RideStatistics
import de.velospot.core.stats.computeRideStatistics
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.feature.wrapped.domain.WrappedComparison
import de.velospot.feature.wrapped.domain.WrappedHighlight
import de.velospot.feature.wrapped.domain.WrappedHighlightType
import de.velospot.feature.wrapped.domain.WrappedPeriod
import de.velospot.feature.wrapped.domain.WrappedReport

/**
 * Pure, deterministic assembler that turns a list of ride aggregates plus a
 * [WrappedPeriod] into a ready-to-render [WrappedReport].
 *
 * No Android, no I/O and no side effects — every input needed to reproduce the
 * output is passed in (including `now`), so this is fully JVM-unit-testable. Mock
 * rides **and archived rides** are excluded everywhere so neither synthetic rides
 * nor rides the user has archived (e.g. the originals left behind after a merge)
 * ever appear in — or skew — a Wrapped story.
 */
internal object WrappedEngine {

    /**
     * Builds the report for [period], or `null` when the period contains no
     * eligible (non-mock, non-archived) rides — this backs the product rule
     * "empty period ⇒ skip; no report, no notification".
     */
    fun build(
        rides: List<RecordedRideSummary>,
        period: WrappedPeriod,
        now: Long = System.currentTimeMillis()
    ): WrappedReport? {
        // Exclude both synthetic (mock) rides and archived rides. Archiving is how a
        // merge retires the original segments; counting them would double-count the
        // ride that replaced them (and any manually archived ride is intentionally
        // hidden from stats too).
        val real = rides.filterNot { it.isMock || it.archivedAt != null }

        val inPeriod = real.filter {
            it.startedAt >= period.startInclusive && it.startedAt < period.endExclusive
        }
        if (inPeriod.isEmpty()) return null

        val stats = computeRideStatistics(inPeriod, now)

        // ── Previous equal-length window ────────────────────────────────────────
        val previousPeriod = WrappedPeriod.previous(period)
        val previousRides = real.filter {
            it.startedAt >= previousPeriod.startInclusive && it.startedAt < previousPeriod.endExclusive
        }
        val previousStats = computeRideStatistics(previousRides, now)

        val distanceDeltaPercent: Double? =
            if (previousStats.totalDistanceMeters > 0.0) {
                (stats.totalDistanceMeters - previousStats.totalDistanceMeters) /
                    previousStats.totalDistanceMeters * 100.0
            } else {
                null
            }
        val comparison = WrappedComparison(
            previousDistanceMeters = previousStats.totalDistanceMeters,
            distanceDeltaPercent = distanceDeltaPercent,
            previousRideCount = previousStats.rideCount,
            rideCountDelta = stats.rideCount - previousStats.rideCount
        )

        // ── Personal-record detection (records SET within this period) ──────────
        // The prior all-time best is the max over every non-mock ride that started
        // strictly BEFORE this period. A NEW_*_RECORD is emitted only when this
        // period strictly beats that prior best (and a prior best exists at all).
        val priorRides = real.filter { it.startedAt < period.startInclusive }
        val priorBestDistance = priorRides.maxOfOrNull { it.distanceMeters }
        val priorBestSpeed = priorRides.maxOfOrNull { it.maxSpeedMps }
        val priorBestClimb = priorRides.maxOfOrNull { it.elevationGainMeters }

        val longestRide = inPeriod.maxByOrNull { it.distanceMeters }
        val topSpeedRide = inPeriod.maxByOrNull { it.maxSpeedMps }
        val biggestClimbRide = inPeriod.maxByOrNull { it.elevationGainMeters }

        val highlights = buildList {
            // NEW_* records first — they are the "wow" moments.
            if (priorBestDistance != null && stats.longestRideMeters > priorBestDistance) {
                add(
                    WrappedHighlight(
                        type = WrappedHighlightType.NEW_DISTANCE_RECORD,
                        valueNumber = stats.longestRideMeters,
                        rideId = longestRide?.id
                    )
                )
            }
            if (priorBestSpeed != null && stats.topSpeedMps > priorBestSpeed) {
                add(
                    WrappedHighlight(
                        type = WrappedHighlightType.NEW_TOP_SPEED_RECORD,
                        valueNumber = stats.topSpeedMps,
                        rideId = topSpeedRide?.id
                    )
                )
            }
            if (priorBestClimb != null && stats.biggestClimbMeters > priorBestClimb) {
                add(
                    WrappedHighlight(
                        type = WrappedHighlightType.NEW_CLIMB_RECORD,
                        valueNumber = stats.biggestClimbMeters,
                        rideId = biggestClimbRide?.id
                    )
                )
            }

            // Core totals / records for the period (always present — period non-empty).
            add(WrappedHighlight(WrappedHighlightType.TOTAL_DISTANCE, stats.totalDistanceMeters))
            add(WrappedHighlight(WrappedHighlightType.RIDE_COUNT, stats.rideCount.toDouble()))
            add(WrappedHighlight(WrappedHighlightType.MOVING_TIME, stats.totalMovingSeconds.toDouble()))
            add(WrappedHighlight(WrappedHighlightType.ELEVATION_GAIN, stats.totalElevationGainMeters))
            add(
                WrappedHighlight(
                    type = WrappedHighlightType.LONGEST_RIDE,
                    valueNumber = stats.longestRideMeters,
                    rideId = longestRide?.id
                )
            )
            add(
                WrappedHighlight(
                    type = WrappedHighlightType.TOP_SPEED,
                    valueNumber = stats.topSpeedMps,
                    rideId = topSpeedRide?.id
                )
            )
            add(
                WrappedHighlight(
                    type = WrappedHighlightType.BIGGEST_CLIMB,
                    valueNumber = stats.biggestClimbMeters,
                    rideId = biggestClimbRide?.id
                )
            )
            add(WrappedHighlight(WrappedHighlightType.ACTIVE_DAYS, stats.activeDays.toDouble()))

            // Streak — only when it is actually running.
            if (stats.currentStreakDays > 0) {
                add(
                    WrappedHighlight(
                        WrappedHighlightType.CURRENT_STREAK,
                        stats.currentStreakDays.toDouble()
                    )
                )
            }

            // Comparisons — only when the previous window had data to compare against.
            if (comparison.previousRideCount > 0) {
                add(
                    WrappedHighlight(
                        type = WrappedHighlightType.VS_PREVIOUS_DISTANCE,
                        valueNumber = stats.totalDistanceMeters,
                        deltaPercent = comparison.distanceDeltaPercent
                    )
                )
                add(
                    WrappedHighlight(
                        type = WrappedHighlightType.VS_PREVIOUS_RIDES,
                        valueNumber = stats.rideCount.toDouble(),
                        deltaPercent = null
                    )
                )
            }
        }

        return WrappedReport(
            period = period,
            generatedAt = now,
            stats = stats,
            comparison = comparison,
            highlights = highlights
        )
    }
}

