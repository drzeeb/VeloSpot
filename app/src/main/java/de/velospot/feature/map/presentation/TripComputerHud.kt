package de.velospot.feature.map.presentation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.velospot.R
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import de.velospot.core.navigation.NavigationProgress
import de.velospot.domain.model.LiveRideStats
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToLong

/**
 * OpenType "tabular figures" feature so digits keep a fixed advance width — the
 * numeric cells never twitch sideways as the values tick over while riding.
 */
private const val TABULAR_FIGURES = "tnum"

/**
 * Distraction-minimising **Trip Computer** HUD shown along the bottom of the map
 * while a ride is being recorded. A single tap toggles between two states
 * (persisted via [onToggleExpanded]):
 *
 * - **Compact** — a large "hero" current speed plus small distance + elapsed time.
 * - **Expanded** — a fixed 6-cell grid (speed · avg · distance / time · gain · grade).
 *   While navigating ([navigationProgress] non-null) the distance and elevation-gain
 *   cells are swapped for ETA and remaining distance.
 *
 * Vertically it is lifted to sit in the free band **between the bottom-right record
 * FAB and the centre-right actions speed-dial ("+")**, so it never overlaps either
 * of them; the turn-by-turn banner ([MapTurnBanner]) stays at the top.
 */
@Composable
internal fun BoxScope.TripComputerHud(
    stats: LiveRideStats,
    navigationProgress: NavigationProgress?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val paused = stats.isPaused
    val toggleHint = stringResource(R.string.hud_toggle_cd)

    Card(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            // Lift the band clear of the bottom-right record FAB: its top edge sits
            // at 88 dp (inset) + 56 dp (FAB height) = 144 dp above the nav bar, so a
            // 152 dp bottom inset leaves a small gap above it while staying below the
            // centre-right speed-dial.
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 152.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = toggleHint,
                onClick = onToggleExpanded
            ),
        shape = RoundedCornerShape(24.dp),
        // Semi-transparent, theme-coupled scrim so the numbers stay legible over
        // any map style (light, dark or AMOLED). onSurface is used for the text,
        // keeping contrast automatic across themes.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (paused) 0.55f else 1f)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Gently cross-fade only between the two layouts; individual number
            // changes rely on tabular figures rather than animation, so nothing
            // bounces or jitters while riding.
            Crossfade(
                targetState = expanded,
                animationSpec = tween(durationMillis = 220),
                label = "hudLayout"
            ) { isExpanded ->
                if (isExpanded) {
                    ExpandedHud(stats, navigationProgress)
                } else {
                    CompactHud(stats)
                }
            }

            if (paused) {
                Spacer(Modifier.height(6.dp))
                PausedIndicator()
            }
        }
    }
}

/** Compact layout: hero current speed + small distance & elapsed time. */
@Composable
private fun CompactHud(stats: LiveRideStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeroSpeedCell(
            value = formatRideSpeed(stats.currentSpeedMps),
            label = stringResource(R.string.ride_stat_speed),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        HudStatCell(
            value = formatRideDistance(stats.distanceMeters),
            label = stringResource(R.string.ride_stat_distance),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        HudStatCell(
            value = formatRideDuration(stats.elapsedSeconds),
            label = stringResource(R.string.ride_stat_time),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Expanded "Trip Computer": a fixed, curated 6-cell grid. When navigating, the
 * distance and elevation-gain cells become ETA and remaining distance.
 */
@Composable
private fun ExpandedHud(stats: LiveRideStats, navigationProgress: NavigationProgress?) {
    val navigating = navigationProgress != null

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Row 1: current speed (hero) · avg speed · distance/remaining.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeroSpeedCell(
                value = formatRideSpeed(stats.currentSpeedMps),
                label = stringResource(R.string.ride_stat_speed),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            HudStatCell(
                value = formatRideSpeed(stats.avgSpeedMps),
                label = stringResource(R.string.hud_stat_avg_speed),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            if (navigating) {
                HudStatCell(
                    value = formatRideDistance(navigationProgress.remainingMeters),
                    label = stringResource(R.string.hud_stat_remaining),
                    modifier = Modifier.weight(1f)
                )
            } else {
                HudStatCell(
                    value = formatRideDistance(stats.distanceMeters),
                    label = stringResource(R.string.ride_stat_distance),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Row 2: elapsed time · elevation gain / ETA · grade %.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudStatCell(
                value = formatRideDuration(stats.elapsedSeconds),
                label = stringResource(R.string.ride_stat_time),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            if (navigating) {
                HudStatCell(
                    value = formatEta(navigationProgress.remainingSeconds),
                    label = stringResource(R.string.hud_stat_eta),
                    modifier = Modifier.weight(1f)
                )
            } else {
                HudStatCell(
                    value = formatRideElevation(stats.elevationGainMeters),
                    label = stringResource(R.string.ride_stat_elevation_gain),
                    icon = Icons.Default.North,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.width(12.dp))
            HudStatCell(
                value = formatGrade(stats.currentGradePercent),
                label = stringResource(R.string.hud_stat_grade),
                icon = Icons.Default.Terrain,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Large emphasised current-speed cell used by both layouts. */
@Composable
private fun HeroSpeedCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFeatureSettings = TABULAR_FIGURES,
                fontSize = 40.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** Standard numeric cell: tabular value on top, small label (optional leading icon). */
@Composable
private fun HudStatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFeatureSettings = TABULAR_FIGURES
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** Subtle "paused" chip echoing the [RideTrackingOverlay] paused styling. */
@Composable
private fun PausedIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.ride_paused),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * ETA as a wall-clock time (e.g. `14:37`), derived by adding the remaining
 * seconds to the current time and formatting with the device's short time style.
 */
private fun formatEta(remainingSeconds: Double): String {
    val arrival = LocalTime.now().plusSeconds(remainingSeconds.roundToLong())
    return arrival.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}

/** Signed road grade, e.g. `+4.2 %` (uphill) or `-3.0 %` (downhill). */
private fun formatGrade(gradePercent: Float): String = "%+.1f %%".format(gradePercent)

