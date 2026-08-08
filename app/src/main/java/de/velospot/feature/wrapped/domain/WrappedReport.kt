package de.velospot.feature.wrapped.domain

import de.velospot.core.stats.RideStatistics

/**
 * The kind of story "page" a [WrappedHighlight] represents. Kept to what the
 * aggregate [de.velospot.domain.model.RecordedRideSummary] can actually support
 * in phase 1 — no GPS track, weather or place names.
 */
internal enum class WrappedHighlightType {
    TOTAL_DISTANCE,
    RIDE_COUNT,
    MOVING_TIME,
    ELEVATION_GAIN,
    LONGEST_RIDE,
    TOP_SPEED,
    BIGGEST_CLIMB,
    ACTIVE_DAYS,
    CURRENT_STREAK,
    NEW_DISTANCE_RECORD,
    NEW_TOP_SPEED_RECORD,
    NEW_CLIMB_RECORD,
    VS_PREVIOUS_DISTANCE,
    VS_PREVIOUS_RIDES
}

/**
 * A neutral, display-agnostic carrier for one story page. The UI phase maps these
 * to localized text, icons and layouts — deliberately **no user-facing strings**
 * live here.
 *
 * @property valueNumber the primary numeric value (meters, seconds, m/s, count …),
 *  interpretation defined by [type].
 * @property deltaPercent optional percentage change vs. the previous window.
 * @property rideId set when a single ride is the source of the highlight (e.g. the
 *  longest ride / top-speed ride / a new record), so the Story UI can show its map.
 */
internal data class WrappedHighlight(
    val type: WrappedHighlightType,
    val valueNumber: Double = 0.0,
    val deltaPercent: Double? = null,
    val rideId: String? = null
)

/**
 * How this period compares to the immediately-preceding equal-length window.
 *
 * The `*Delta*` fields are `null`/neutral when the previous window had zero of the
 * corresponding metric, so the UI never has to guard against divide-by-zero.
 */
internal data class WrappedComparison(
    val previousDistanceMeters: Double,
    val distanceDeltaPercent: Double?,
    val previousRideCount: Int,
    val rideCountDelta: Int
)

/**
 * The complete, pre-computed "VeloSpot Wrapped" report for a single [period].
 * Purely derived from ride aggregates; safe to persist and render later.
 */
internal data class WrappedReport(
    val period: WrappedPeriod,
    val generatedAt: Long,
    val stats: RideStatistics,
    val comparison: WrappedComparison,
    val highlights: List<WrappedHighlight>
) {
    /** The stable, deterministic storage id derived purely from the [period]. */
    val id: String get() = wrappedReportId(period)
}

/**
 * Stable, deterministic id for the report covering [period], so re-generating the
 * same closed bucket upserts over the previous row instead of creating a duplicate.
 * Shared by the persistence layer and the presentation layer (deep links / history).
 */
internal fun wrappedReportId(period: WrappedPeriod): String =
    "${period.type.name}-${period.startInclusive}-${period.endExclusive}"

