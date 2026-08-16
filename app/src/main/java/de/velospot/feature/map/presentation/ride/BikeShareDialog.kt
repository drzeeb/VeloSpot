package de.velospot.feature.map.presentation.ride

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import de.velospot.core.share.ImageSharer
import de.velospot.feature.bikeprofiles.presentation.BikeProfileStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A full-width preview dialog for the shareable bike "Sharepic": the rider's bike
 * photo plus a grid of meaningful ride stats. The card image is rendered off the main
 * thread; once ready the user can fire the system share sheet.
 *
 * Reuses the same colour-theme picker and off-thread render → FileProvider → system
 * share pattern as [StatsShareDialog]. Only stats that make sense are shown (empty
 * ones are dropped), and the "no rides yet" case is surfaced as a hint.
 *
 * @param bikeName the bike's display name (hero title).
 * @param subtitle the pre-composed brand / model / type line (may be blank).
 * @param photoPath absolute path to the bike photo in app storage, or `null`.
 * @param typeLabel localised bike-type label, used as the top-right fallback when the
 *  bike has no rides yet (so there is no "since" date to show).
 * @param stats the bike's aggregate ride statistics.
 */
@Composable
internal fun BikeShareDialog(
    bikeName: String,
    subtitle: String,
    photoPath: String?,
    typeLabel: String,
    stats: BikeProfileStats,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val footer = stringResource(R.string.ride_share_footer)
    val shareChooserTitle = stringResource(R.string.bike_share_chooser_title)

    // Period label: "SINCE <MMM yyyy>" of the first ride, else the bike type.
    val firstRideMonth = remember(stats.firstRideAt) {
        stats.firstRideAt?.let {
            SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(it)).uppercase(Locale.ROOT)
        }
    }
    val periodLabel = if (firstRideMonth != null) {
        stringResource(R.string.bike_share_period_since, firstRideMonth)
    } else {
        typeLabel
    }.uppercase(Locale.ROOT)

    // Only include stats that make sense (drop zero / empty ones).
    val cells = buildList {
        if (stats.totalDistanceMeters > 0.0) {
            add(BikeShareCell("🛣", formatRideDistance(stats.totalDistanceMeters), stringResource(R.string.bike_share_stat_distance)))
        }
        if (stats.rideCount > 0) {
            add(BikeShareCell("🚴", stats.rideCount.toString(), stringResource(R.string.bike_share_stat_rides)))
        }
        if (stats.totalElevationGainMeters > 0.0) {
            add(BikeShareCell("⛰", "↑ " + formatRideElevation(stats.totalElevationGainMeters), stringResource(R.string.bike_share_stat_elevation)))
        }
        if (stats.totalMovingSeconds > 0L) {
            add(BikeShareCell("⏱", formatRideDuration(stats.totalMovingSeconds), stringResource(R.string.bike_share_stat_moving_time)))
        }
        if (stats.longestRideMeters > 0.0) {
            add(BikeShareCell("📏", formatRideDistance(stats.longestRideMeters), stringResource(R.string.bike_share_stat_longest)))
        }
        if (stats.topSpeedMps > 0.0) {
            add(BikeShareCell("⚡", formatRideSpeed(stats.topSpeedMps), stringResource(R.string.bike_share_stat_top_speed)))
        }
    }

    val labels = BikeShareLabels(
        bikeName = bikeName,
        subtitle = subtitle,
        cells = cells,
        periodLabel = periodLabel,
        footer = footer
    )

    // Decode the bike photo once, off the main thread.
    val photo by produceState<Bitmap?>(initialValue = null, photoPath) {
        value = photoPath?.let {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(it) }.getOrNull()
            }
        }
    }

    var selectedTheme by remember { mutableStateOf(RideShareThemes.default) }

    // Re-render the card whenever the theme or decoded photo changes.
    val bitmap by produceState<Bitmap?>(initialValue = null, selectedTheme, photo) {
        value = null
        value = withContext(Dispatchers.Default) {
            renderBikeShareCard(photo = photo, labels = labels, theme = selectedTheme)
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
                    text = stringResource(R.string.bike_share_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (cells.isEmpty()) {
                        stringResource(R.string.bike_share_no_rides)
                    } else {
                        stringResource(R.string.bike_share_dialog_subtitle)
                    },
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
                        text = stringResource(R.string.bike_share_action),
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

