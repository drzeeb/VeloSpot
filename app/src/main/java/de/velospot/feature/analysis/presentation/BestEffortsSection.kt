package de.velospot.feature.analysis.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.analysis.BestEfforts
import de.velospot.core.analysis.DistanceEffort
import de.velospot.core.analysis.DurationEffort
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideSpeed
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToLong

/**
 * Best-effort tables: the fastest time over standard distances (1 / 5 / 10 / 20 /
 * 40 / 100 km) and the furthest distance within standard time windows (1 / 5 / 10
 * / 20 / 60 min). Only the rows the ride was long/long-enough for are shown.
 */
@Composable
fun BestEffortsSection(efforts: BestEfforts, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (efforts.fastestDistances.isNotEmpty()) {
            EffortTable(
                title = stringResource(R.string.ride_efforts_fastest_distances),
                rows = efforts.fastestDistances.map { it.toRow() }
            )
        }
        if (efforts.bestDurations.isNotEmpty()) {
            EffortTable(
                title = stringResource(R.string.ride_efforts_best_times),
                rows = efforts.bestDurations.map { it.toRow() }
            )
        }
    }
}

private data class EffortRow(val label: String, val value: String, val speed: String)

private fun DistanceEffort.toRow(): EffortRow = EffortRow(
    label = formatRideDistance(distanceMeters),
    value = formatRideDuration(elapsedSeconds.roundToLong()),
    speed = formatRideSpeed(avgSpeedMps)
)

private fun DurationEffort.toRow(): EffortRow = EffortRow(
    label = formatDurationTarget(seconds),
    value = formatRideDistance(distanceMeters),
    speed = formatRideSpeed(avgSpeedMps)
)

/** Compact label for a time target: `1 min`, `20 min`, `1 h`. */
private fun formatDurationTarget(seconds: Long): String =
    if (seconds < 3_600) "${seconds / 60} min" else "${seconds / 3_600} h"

@Composable
private fun EffortTable(title: String, rows: List<EffortRow>) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = row.speed,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (index < rows.lastIndex) {
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

