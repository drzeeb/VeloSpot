package de.velospot.feature.wrapped.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import de.velospot.R
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import de.velospot.feature.wrapped.domain.WrappedHighlightType

/**
 * Pure (non-Composable) presentation helpers for the "VeloSpot Wrapped" Story.
 *
 * The icon mapping, the title string-resource selection and — crucially — the
 * **value-formatting selection** all live here as plain functions with no Android
 * resource lookups, so the branch logic is JVM-unit-testable (Kover counts this
 * package; the surrounding `@Composable`s / Canvas are excluded). The Story
 * composable resolves the `@StringRes` title and pairs these with a theme.
 */

/** The Material icon shown on a highlight slide for [type]. */
internal fun wrappedIconFor(type: WrappedHighlightType): ImageVector = when (type) {
    WrappedHighlightType.TOTAL_DISTANCE -> Icons.Filled.Route
    WrappedHighlightType.RIDE_COUNT -> Icons.AutoMirrored.Filled.DirectionsBike
    WrappedHighlightType.MOVING_TIME -> Icons.Filled.Timer
    WrappedHighlightType.ELEVATION_GAIN -> Icons.Filled.Terrain
    WrappedHighlightType.LONGEST_RIDE -> Icons.Filled.Straighten
    WrappedHighlightType.TOP_SPEED -> Icons.Filled.Speed
    WrappedHighlightType.BIGGEST_CLIMB -> Icons.Filled.Terrain
    WrappedHighlightType.ACTIVE_DAYS -> Icons.Filled.CalendarMonth
    WrappedHighlightType.CURRENT_STREAK -> Icons.Filled.LocalFireDepartment
    WrappedHighlightType.NEW_DISTANCE_RECORD,
    WrappedHighlightType.NEW_TOP_SPEED_RECORD,
    WrappedHighlightType.NEW_CLIMB_RECORD -> Icons.Filled.EmojiEvents
    WrappedHighlightType.VS_PREVIOUS_DISTANCE,
    WrappedHighlightType.VS_PREVIOUS_RIDES -> Icons.AutoMirrored.Filled.TrendingUp
}

/** The title string-resource for a highlight slide of [type]. */
internal fun wrappedTitleResFor(type: WrappedHighlightType): Int = when (type) {
    WrappedHighlightType.TOTAL_DISTANCE -> R.string.wrapped_hl_total_distance
    WrappedHighlightType.RIDE_COUNT -> R.string.wrapped_hl_ride_count
    WrappedHighlightType.MOVING_TIME -> R.string.wrapped_hl_moving_time
    WrappedHighlightType.ELEVATION_GAIN -> R.string.wrapped_hl_elevation_gain
    WrappedHighlightType.LONGEST_RIDE -> R.string.wrapped_hl_longest_ride
    WrappedHighlightType.TOP_SPEED -> R.string.wrapped_hl_top_speed
    WrappedHighlightType.BIGGEST_CLIMB -> R.string.wrapped_hl_biggest_climb
    WrappedHighlightType.ACTIVE_DAYS -> R.string.wrapped_hl_active_days
    WrappedHighlightType.CURRENT_STREAK -> R.string.wrapped_hl_current_streak
    WrappedHighlightType.NEW_DISTANCE_RECORD -> R.string.wrapped_hl_new_distance_record
    WrappedHighlightType.NEW_TOP_SPEED_RECORD -> R.string.wrapped_hl_new_speed_record
    WrappedHighlightType.NEW_CLIMB_RECORD -> R.string.wrapped_hl_new_climb_record
    WrappedHighlightType.VS_PREVIOUS_DISTANCE -> R.string.wrapped_hl_vs_previous_distance
    WrappedHighlightType.VS_PREVIOUS_RIDES -> R.string.wrapped_hl_vs_previous_rides
}

/**
 * Formats the big numeric value on a highlight slide, choosing the unit family
 * (distance / duration / speed / elevation / plain count) from [type]. Pure — it
 * only calls the locale-neutral `RideFormat` helpers, so it is unit-testable.
 */
internal fun formatWrappedValue(type: WrappedHighlightType, value: Double): String = when (type) {
    WrappedHighlightType.TOTAL_DISTANCE,
    WrappedHighlightType.LONGEST_RIDE,
    WrappedHighlightType.NEW_DISTANCE_RECORD,
    WrappedHighlightType.VS_PREVIOUS_DISTANCE -> formatRideDistance(value)

    WrappedHighlightType.MOVING_TIME -> formatRideDuration(value.toLong())

    WrappedHighlightType.ELEVATION_GAIN,
    WrappedHighlightType.BIGGEST_CLIMB,
    WrappedHighlightType.NEW_CLIMB_RECORD -> "↑ " + formatRideElevation(value)

    WrappedHighlightType.TOP_SPEED,
    WrappedHighlightType.NEW_TOP_SPEED_RECORD -> formatRideSpeed(value)

    WrappedHighlightType.RIDE_COUNT,
    WrappedHighlightType.ACTIVE_DAYS,
    WrappedHighlightType.CURRENT_STREAK,
    WrappedHighlightType.VS_PREVIOUS_RIDES -> value.toLong().toString()
}

/**
 * Formats a signed percentage delta, e.g. `+42%` / `-13%`, or `null` when there
 * is no delta to show. Used for the "vs previous window" caption.
 */
internal fun formatWrappedDelta(deltaPercent: Double?): String? {
    val d = deltaPercent ?: return null
    val rounded = Math.round(d)
    val sign = if (rounded > 0) "+" else ""
    return "$sign$rounded%"
}

