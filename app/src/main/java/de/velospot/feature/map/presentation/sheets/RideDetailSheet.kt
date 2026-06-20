package de.velospot.feature.map.presentation.sheets

import de.velospot.feature.map.presentation.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import de.velospot.feature.map.presentation.ride.RideShareDialog

/**
 * Detail view for one recorded ride: the full statistics grid plus a speed
 * "timeline" chart drawn from the captured track. The ride's polyline is already
 * shown on the map underneath (drawn when the ride is selected).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RideDetailSheet(
    ride: RecordedRide,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var showShareDialog by remember { mutableStateOf(false) }

    if (showShareDialog) {
        RideShareDialog(
            ride = ride,
            onDismiss = { showShareDialog = false }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SheetHeader(
                title = formatRideDistance(ride.distanceMeters),
                subtitle = stringResource(
                    R.string.ride_detail_subtitle,
                    formatRideDuration(ride.elapsedSeconds),
                    formatRideSpeed(ride.avgSpeedMps)
                )
            )

            Spacer(Modifier.height(16.dp))

            // ── Stats grid ───────────────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.ride_stat_moving),
                    value = formatRideDuration(ride.movingSeconds)
                )
                StatBox(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.ride_stat_max_speed),
                    value = formatRideSpeed(ride.maxSpeedMps)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.ride_stat_elevation_gain),
                    value = "↑ " + formatRideElevation(ride.elevationGainMeters)
                )
                StatBox(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.ride_stat_elevation_loss),
                    value = "↓ " + formatRideElevation(ride.elevationLossMeters)
                )
            }

            Spacer(Modifier.height(18.dp))

            // ── Speed timeline chart ─────────────────────────────────────────
            Text(
                text = stringResource(R.string.ride_speed_chart_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            SpeedChart(
                points = ride.points,
                maxSpeedMps = ride.maxSpeedMps
            )

            Spacer(Modifier.height(16.dp))

            // ── Share as a "VeloSpot Wrapped" card ───────────────────────────
            Button(
                onClick = { showShareDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(text = stringResource(R.string.ride_share_action))
            }

            Spacer(Modifier.height(8.dp))

            // ── Delete ───────────────────────────────────────────────────────
            TextButton(
                onClick = { onDelete(ride.id) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ride_delete),
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Minimal line/area chart of speed (km/h) over the ride's track samples — the
 * "timeline" the user asked for. Drawn with a plain [Canvas] so no charting
 * dependency is needed.
 */
@Composable
private fun SpeedChart(points: List<TrackPoint>, maxSpeedMps: Double) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val speeds = points.map { (it.speedMps ?: 0f).toDouble() }
    val maxSpeed = maxOf(maxSpeedMps, speeds.maxOrNull() ?: 0.0, 1.0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                val w = size.width
                val h = size.height
                // Baseline grid.
                drawLine(
                    color = gridColor,
                    start = Offset(0f, h),
                    end = Offset(w, h),
                    strokeWidth = 1.5f
                )
                if (speeds.size < 2) return@Canvas

                val stepX = w / (speeds.size - 1)
                fun y(v: Double) = (h - (v / maxSpeed * h)).toFloat()

                val linePath = Path()
                val fillPath = Path()
                speeds.forEachIndexed { index, v ->
                    val x = index * stepX
                    val py = y(v)
                    if (index == 0) {
                        linePath.moveTo(x, py)
                        fillPath.moveTo(x, h)
                        fillPath.lineTo(x, py)
                    } else {
                        linePath.lineTo(x, py)
                        fillPath.lineTo(x, py)
                    }
                }
                fillPath.lineTo(w, h)
                fillPath.close()

                drawPath(path = fillPath, color = fillColor)
                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(width = 3f)
                )
            }
        }
    }
}


