package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.analysis.RouteDirectionStats
import de.velospot.core.analysis.RouteLeaderboardSummary
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import de.velospot.domain.model.PlannedRoute
import de.velospot.feature.map.presentation.headingSemantics
import java.text.DateFormat
import java.util.Date

/** Green used for an improving trend (sparkline + "faster than average"). */
private val IMPROVING_COLOR = Color(0xFF2E7D32)

/**
 * A **non-modal** preview card for a saved route, shown while the route's line is
 * drawn on the map (the map stays pan/zoom-able above it). Lets the rider inspect
 * the route before riding — its distance, climb and a digest of its leaderboard —
 * and start it forward or reversed, open the full leaderboard, or close.
 *
 * Placed inside the map layout `Box`; it fills the screen but only the bottom card
 * consumes touches, so the map remains interactive.
 */
@Composable
internal fun RoutePreviewSheet(
    route: PlannedRoute,
    summary: RouteLeaderboardSummary,
    onRideForward: () -> Unit,
    onRideReverse: () -> Unit,
    onOpenLeaderboard: () -> Unit,
    onClose: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
            ),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // ── Header: name + close ─────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = route.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).headingSemantics()
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }

                // ── Route facts (constant per route) ─────────────────────────
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewStat(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.ride_stat_distance),
                        value = formatRideDistance(route.distanceMeters)
                    )
                    PreviewStat(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.ride_stat_elevation_gain),
                        value = "↑ " + formatRideElevation(route.elevationGainMeters)
                    )
                    PreviewStat(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.ride_stat_elevation_loss),
                        value = "↓ " + formatRideElevation(route.elevationLossMeters)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewStat(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.route_stat_stops),
                        value = route.waypoints.size.toString()
                    )
                    PreviewStat(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.ride_stats_calories),
                        value = route.estimatedKcal?.let { "≈ %,d kcal".format(it) } ?: "—"
                    )
                    Spacer(Modifier.weight(1f))
                }

                // ── Leaderboard digest ───────────────────────────────────────
                Spacer(Modifier.height(12.dp))
                RouteLeaderboardDigest(summary = summary, onOpenLeaderboard = onOpenLeaderboard)

                // ── Ride actions ─────────────────────────────────────────────
                Spacer(Modifier.height(14.dp))
                Button(onClick = onRideForward, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.route_ride))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRideReverse, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.route_ride_reverse))
                }
            }
        }
    }
}

@Composable
private fun RouteLeaderboardDigest(
    summary: RouteLeaderboardSummary,
    onOpenLeaderboard: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.EmojiEvents,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.route_leaderboard_open),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        if (summary.hasAttempts) {
            TextButton(onClick = onOpenLeaderboard) {
                Text(stringResource(R.string.route_leaderboard_view_all))
            }
        }
    }
    if (!summary.hasAttempts) {
        Text(
            text = stringResource(R.string.route_leaderboard_never_ridden),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    DirectionStatsBlock(labelRes = R.string.route_best_forward, stats = summary.forward)
    DirectionStatsBlock(labelRes = R.string.route_best_reverse, stats = summary.reverse)
    val lastRidden = summary.lastRiddenAt?.let {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
    }
    Text(
        text = if (lastRidden != null) {
            stringResource(R.string.route_ridden_count_last, summary.totalAttempts, lastRidden)
        } else {
            stringResource(R.string.route_ridden_count, summary.totalAttempts)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * One direction's leaderboard block: best time (+ avg speed), and — once there
 * are at least two attempts — the average/median times, an improvement sparkline
 * and how much the personal best beats the average. Renders nothing when the
 * route was never ridden in this direction.
 */
@Composable
private fun DirectionStatsBlock(labelRes: Int, stats: RouteDirectionStats) {
    val best = stats.best ?: return
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatRideDuration(best.elapsedSeconds),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatRideSpeed(best.avgSpeedMps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (stats.attemptCount >= 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.route_avg_median,
                        formatRideDuration(stats.averageSeconds ?: 0L),
                        formatRideDuration(stats.medianSeconds ?: 0L)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                stats.bestVsAverageSeconds?.let { faster ->
                    Text(
                        text = "↓ " + stringResource(
                            R.string.route_faster_than_average,
                            formatRideDuration(faster)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = IMPROVING_COLOR,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Sparkline(
                values = stats.trend,
                improving = stats.isImproving,
                modifier = Modifier.size(width = 64.dp, height = 24.dp)
            )
        }
    }
}

/**
 * A tiny line chart of a direction's attempt times **oldest → newest**. Faster
 * (lower) times sit higher, so an improving rider's line **rises**; it is drawn
 * green when improving and in the error colour when regressing.
 */
@Composable
private fun Sparkline(values: List<Long>, improving: Boolean, modifier: Modifier) {
    if (values.size < 2) return
    val lineColor = if (improving) IMPROVING_COLOR else MaterialTheme.colorScheme.error
    val min = values.min().toFloat()
    val max = values.max().toFloat()
    val range = (max - min).takeIf { it > 0f } ?: 1f
    Canvas(modifier = modifier) {
        val stepX = if (values.size > 1) size.width / (values.size - 1) else 0f
        // Invert Y so a faster (smaller) time is drawn higher up.
        fun y(v: Long): Float = size.height - ((v - min) / range) * size.height
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = i * stepX
            val py = y(v)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        // Emphasise the most recent time.
        drawCircle(
            color = lineColor,
            radius = 2.5.dp.toPx(),
            center = Offset((values.size - 1) * stepX, y(values.last()))
        )
    }
}

@Composable
private fun PreviewStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

