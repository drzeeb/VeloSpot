package de.velospot.feature.map.presentation.ride

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.feature.map.presentation.formatRideDistance
import de.velospot.feature.map.presentation.formatRideDuration
import de.velospot.feature.map.presentation.formatRideElevation
import de.velospot.feature.map.presentation.formatRideSpeed
import java.text.DateFormat
import java.util.Date

/**
 * The "stats-nerd" dashboard shown at the top of the "My rides" sheet.
 *
 * Groups every available metric — totals, averages, personal records, activity
 * streaks and a few fun facts — into a collapsible card so it stays out of the
 * way of the ride list when not needed, yet exposes *everything* when expanded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RideStatisticsSection(stats: RideStatistics) {
    if (!stats.hasData) return

    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header (tap to collapse/expand) ──────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ride_stats_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.ride_stats_subtitle,
                            stats.rideCount,
                            formatRideDistance(stats.totalDistanceMeters)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(14.dp))

                    // ── Totals ────────────────────────────────────────────────
                    StatCategory(stringResource(R.string.ride_stats_cat_totals)) {
                        StatChip(stringResource(R.string.ride_stats_total_rides), stats.rideCount.toString())
                        StatChip(stringResource(R.string.ride_stats_total_distance), formatRideDistance(stats.totalDistanceMeters))
                        StatChip(stringResource(R.string.ride_stats_total_time), formatRideDuration(stats.totalElapsedSeconds))
                        StatChip(stringResource(R.string.ride_stats_total_moving), formatRideDuration(stats.totalMovingSeconds))
                        StatChip(stringResource(R.string.ride_stats_total_gain), "↑ " + formatRideElevation(stats.totalElevationGainMeters))
                        StatChip(stringResource(R.string.ride_stats_total_loss), "↓ " + formatRideElevation(stats.totalElevationLossMeters))
                    }

                    // ── Averages ──────────────────────────────────────────────
                    StatCategory(stringResource(R.string.ride_stats_cat_averages)) {
                        StatChip(stringResource(R.string.ride_stats_avg_distance), formatRideDistance(stats.avgDistanceMeters))
                        StatChip(stringResource(R.string.ride_stats_avg_duration), formatRideDuration(stats.avgElapsedSeconds))
                        StatChip(stringResource(R.string.ride_stats_avg_speed), formatRideSpeed(stats.avgMovingSpeedMps))
                        StatChip(stringResource(R.string.ride_stats_avg_gain), formatRideElevation(stats.avgElevationGainMeters))
                    }

                    // ── Records ───────────────────────────────────────────────
                    StatCategory(stringResource(R.string.ride_stats_cat_records)) {
                        StatChip(stringResource(R.string.ride_stats_top_speed), formatRideSpeed(stats.topSpeedMps), highlight = true)
                        StatChip(stringResource(R.string.ride_stats_longest_ride), formatRideDistance(stats.longestRideMeters), highlight = true)
                        StatChip(stringResource(R.string.ride_stats_longest_duration), formatRideDuration(stats.longestRideDurationSeconds), highlight = true)
                        StatChip(stringResource(R.string.ride_stats_best_avg_speed), formatRideSpeed(stats.bestAvgSpeedMps), highlight = true)
                        StatChip(stringResource(R.string.ride_stats_biggest_climb), "↑ " + formatRideElevation(stats.biggestClimbMeters), highlight = true)
                    }

                    // ── Activity ──────────────────────────────────────────────
                    StatCategory(stringResource(R.string.ride_stats_cat_activity)) {
                        stats.firstRideAt?.let {
                            StatChip(stringResource(R.string.ride_stats_first_ride), dateFormat.format(Date(it)))
                        }
                        StatChip(stringResource(R.string.ride_stats_active_days), stats.activeDays.toString())
                        StatChip(stringResource(R.string.ride_stats_current_streak), stringResource(R.string.ride_stats_days_value, stats.currentStreakDays))
                        StatChip(stringResource(R.string.ride_stats_longest_streak), stringResource(R.string.ride_stats_days_value, stats.longestStreakDays))
                        StatChip(stringResource(R.string.ride_stats_this_week), stringResource(R.string.ride_stats_rides_dist, stats.ridesThisWeek, formatRideDistance(stats.distanceThisWeekMeters)))
                        StatChip(stringResource(R.string.ride_stats_this_month), stringResource(R.string.ride_stats_rides_dist, stats.ridesThisMonth, formatRideDistance(stats.distanceThisMonthMeters)))
                    }

                    // ── Fun facts ─────────────────────────────────────────────
                    StatCategory(stringResource(R.string.ride_stats_cat_fun)) {
                        StatChip(stringResource(R.string.ride_stats_co2), formatGrams(stats.co2SavedGrams))
                        StatChip(stringResource(R.string.ride_stats_calories), "%,d kcal".format(stats.caloriesBurned))
                        StatChip(stringResource(R.string.ride_stats_earth), "%.3f %%".format(stats.earthCircumferencePercent))
                    }
                }
            }
        }
    }
}

/** A titled group of stat chips laid out in a wrapping flow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatCategory(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
    Spacer(Modifier.height(14.dp))
}

/**
 * A compact label/value chip. [highlight] tints record-breaking values with the
 * primary container colour so personal bests pop.
 */
@Composable
private fun StatChip(label: String, value: String, highlight: Boolean = false) {
    val container = if (highlight) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val onContainer = if (highlight) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onContainer
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = 0.7f)
            )
        }
    }
}

/** Formats a CO₂ mass: grams under 1 kg, otherwise kilograms with one decimal. */
private fun formatGrams(grams: Double): String =
    if (grams < 1_000) "${grams.toInt()} g" else "%.1f kg".format(grams / 1_000.0)

