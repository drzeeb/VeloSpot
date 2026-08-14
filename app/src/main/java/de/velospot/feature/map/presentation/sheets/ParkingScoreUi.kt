package de.velospot.feature.map.presentation.sheets

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.parking.ParkingScore
import de.velospot.core.parking.ParkingScorer
import de.velospot.core.parking.ParkingTier
import de.velospot.core.parking.ScoreFactorKey
import de.velospot.domain.model.BikeParkingSpace

/** Maps a display-agnostic [ScoreFactorKey] to its localised chip label. */
@StringRes
internal fun ScoreFactorKey.labelRes(): Int = when (this) {
    ScoreFactorKey.THEFT_PROTECTION -> R.string.score_factor_theft_protection
    ScoreFactorKey.WEATHER_PROTECTION -> R.string.score_factor_weather_protection
    ScoreFactorKey.SURVEILLANCE -> R.string.score_factor_surveillance
    ScoreFactorKey.LIGHTING -> R.string.score_factor_lighting
    ScoreFactorKey.PUBLIC_ACCESS -> R.string.score_factor_public_access
    ScoreFactorKey.FREE -> R.string.score_factor_free
    ScoreFactorKey.OPENING_HOURS -> R.string.score_factor_opening_hours
    ScoreFactorKey.CAPACITY -> R.string.score_factor_capacity
    ScoreFactorKey.CARGO_BIKE -> R.string.score_factor_cargo_bike
    ScoreFactorKey.CHARGING -> R.string.score_factor_charging
    ScoreFactorKey.SUPERVISED -> R.string.score_factor_supervised
    ScoreFactorKey.DATA_FRESH -> R.string.score_factor_data_fresh
}

/** Localised tier label. */
@StringRes
internal fun ParkingTier.labelRes(): Int = when (this) {
    ParkingTier.BASIC -> R.string.parking_tier_basic
    ParkingTier.DECENT -> R.string.parking_tier_decent
    ParkingTier.GOOD -> R.string.parking_tier_good
    ParkingTier.SECURE -> R.string.parking_tier_secure
    ParkingTier.PREMIUM -> R.string.parking_tier_premium
}

/**
 * Red → amber → green color ramp for the VeloScore badge, interpolated on the
 * `0..100` value so the badge colour tracks the numeric score smoothly.
 */
internal fun scoreRampColor(value: Int): Color {
    val red = Color(0xFFD32F2F)
    val amber = Color(0xFFF6A623)
    val green = Color(0xFF2E7D32)
    val t = value.coerceIn(0, 100) / 100f
    return if (t < 0.5f) lerpColor(red, amber, t / 0.5f)
    else lerpColor(amber, green, (t - 0.5f) / 0.5f)
}

private fun lerpColor(from: Color, to: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = 1f
    )
}

/**
 * VeloScore badge: numeric `0..100` score, tier label, colour ramp and a short
 * "why secure" chip row built from the top positive factors.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VeloScoreBadge(
    space: BikeParkingSpace,
    modifier: Modifier = Modifier
) {
    val score: ParkingScore = ParkingScorer.compute(space)
    val rampColor = scoreRampColor(score.value)
    val topFactors = ParkingScorer.topPositiveFactors(score)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = score.value.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = rampColor
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.velo_score_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(id = score.tier.labelRes()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = rampColor
                    )
                }
            }

            if (topFactors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(id = R.string.velo_score_why),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    topFactors.forEach { key ->
                        ScoreChip(label = stringResource(id = key.labelRes()), color = rampColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreChip(label: String, color: Color) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.16f)
        ),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}



