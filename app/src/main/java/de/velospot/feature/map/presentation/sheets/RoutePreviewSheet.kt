package de.velospot.feature.map.presentation.sheets

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.analysis.RouteLeaderboardSummary
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.RouteAttempt
import de.velospot.feature.map.presentation.headingSemantics
import java.text.DateFormat
import java.util.Date

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
    summary.forwardBest?.let { best ->
        BestTimeLine(labelRes = R.string.route_best_forward, attempt = best)
    }
    summary.reverseBest?.let { best ->
        BestTimeLine(labelRes = R.string.route_best_reverse, attempt = best)
    }
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

@Composable
private fun BestTimeLine(labelRes: Int, attempt: RouteAttempt) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatRideDuration(attempt.elapsedSeconds),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatRideSpeed(attempt.avgSpeedMps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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

