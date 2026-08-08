package de.velospot.feature.map.presentation.ride

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.velospot.R
import de.velospot.core.format.formatCo2Saved
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import de.velospot.core.share.ImageSharer
import de.velospot.core.stats.RideStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A full-width preview dialog showing the "VeloSpot Wrapped — All-time" share card
 * summarising the rider's whole history. The card image is rendered off the main
 * thread; once ready the user can fire the system share sheet.
 *
 * Reuses the exact same colour-theme picker as the per-ride [RideShareDialog], with
 * a live-updating preview — only the lightweight card render re-runs on theme change.
 */
@Composable
internal fun StatsShareDialog(
    stats: RideStatistics,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val headline = stringResource(R.string.stats_share_headline_distance)
    val footer = stringResource(R.string.ride_share_footer)
    val shareChooserTitle = stringResource(R.string.ride_share_chooser_title)

    val subtitle = stringResource(
        R.string.stats_share_subtitle,
        stats.rideCount,
        stats.activeDays
    )

    // Period label: the month/year of the first ride, else the "all-time" fallback.
    val firstRideMonth = remember(stats.firstRideAt) {
        stats.firstRideAt?.let {
            SimpleDateFormat("MMM yyyy", Locale.getDefault())
                .format(Date(it))
                .uppercase(Locale.ROOT)
        }
    }
    val periodLabel = if (firstRideMonth != null) {
        stringResource(R.string.stats_share_period_since, firstRideMonth)
    } else {
        stringResource(R.string.stats_share_period_all_time)
    }.uppercase(Locale.ROOT)

    // Pre-format the six stat cells so the renderer stays resource-free.
    val cells = listOf(
        StatsShareCell("⏱", formatRideDuration(stats.totalMovingSeconds), stringResource(R.string.ride_stats_total_moving)),
        StatsShareCell("⛰", "↑ " + formatRideElevation(stats.totalElevationGainMeters), stringResource(R.string.ride_stats_total_gain)),
        StatsShareCell("⚡", formatRideSpeed(stats.topSpeedMps), stringResource(R.string.ride_stats_top_speed)),
        StatsShareCell("🚴", formatRideSpeed(stats.avgMovingSpeedMps), stringResource(R.string.ride_stats_avg_speed)),
        StatsShareCell("🔥", "%,d kcal".format(stats.caloriesBurned), stringResource(R.string.ride_stats_calories)),
        StatsShareCell("🌱", formatCo2Saved(stats.co2SavedGrams), stringResource(R.string.ride_stats_co2))
    )

    // Build the flex badges, filtering out any that are too trivial to flex.
    val badges = buildList {
        if (qualifiesWorldBadge(stats.earthCircumferencePercent)) {
            add(StatsShareBadge("🌍", stringResource(R.string.stats_share_badge_world, stats.earthCircumferencePercent)))
        }
        if (qualifiesEverestBadge(stats.totalElevationGainMeters)) {
            add(StatsShareBadge("🏔", stringResource(R.string.stats_share_badge_everest, everestRatio(stats.totalElevationGainMeters))))
        }
        if (qualifiesStreakBadge(stats.longestStreakDays)) {
            add(StatsShareBadge("🔥", stringResource(R.string.stats_share_badge_streak, stats.longestStreakDays)))
        }
    }

    val labels = StatsShareLabels(
        headline = headline,
        subtitle = subtitle,
        cells = cells,
        badges = badges,
        footer = footer
    )

    var selectedTheme by remember { mutableStateOf(RideShareThemes.default) }

    // Re-render the card whenever the theme changes so the preview updates live.
    val bitmap by produceState<Bitmap?>(initialValue = null, selectedTheme) {
        value = null
        value = withContext(Dispatchers.Default) {
            renderStatsShareCard(
                stats = stats,
                labels = labels,
                periodLabel = periodLabel,
                theme = selectedTheme
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.stats_share_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.stats_share_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1080f / 1350f),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp == null) {
                        CircularProgressIndicator()
                    } else {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                ThemePicker(
                    selected = selectedTheme,
                    onSelect = { selectedTheme = it }
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = {
                        bitmap?.let {
                            ImageSharer.shareBitmap(
                                context = context,
                                bitmap = it,
                                chooserTitle = shareChooserTitle
                            )
                        }
                    },
                    enabled = bitmap != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = null)
                    Text(
                        text = stringResource(R.string.stats_share_action),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.ride_share_dismiss))
                }
            }
        }
    }
}

