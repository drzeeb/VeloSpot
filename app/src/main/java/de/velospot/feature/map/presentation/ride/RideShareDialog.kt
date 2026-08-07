package de.velospot.feature.map.presentation.ride

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.velospot.R
import de.velospot.core.share.ImageSharer
import de.velospot.core.format.formatWeatherTemperature
import de.velospot.core.format.formatWeatherWind
import de.velospot.core.weather.WmoWeatherCode
import de.velospot.domain.model.RecordedRide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * A full-width preview dialog showing the "VeloSpot Wrapped" share card for a
 * recorded ride. The card image is rendered off the main thread; once ready the
 * user can fire the system share sheet (WhatsApp, Telegram, Instagram, …).
 *
 * A colour-theme picker lets the user restyle the card with a live-updating
 * preview. The (relatively expensive) map snapshot is fetched only once and then
 * reused across theme changes — only the lightweight card render re-runs.
 */
@Composable
internal fun RideShareDialog(
    ride: RecordedRide,
    onDismiss: () -> Unit,
    weatherEnabled: Boolean = false
) {
    val context = LocalContext.current

    val headline = stringResource(R.string.ride_share_headline)
    val durationLabel = stringResource(R.string.ride_share_stat_duration)
    val avgSpeedLabel = stringResource(R.string.ride_share_stat_avg_speed)
    val elevationLabel = stringResource(R.string.ride_share_stat_elevation)
    val maxSpeedLabel = stringResource(R.string.ride_share_stat_max_speed)
    val footer = stringResource(R.string.ride_share_footer)
    val shareChooserTitle = stringResource(R.string.ride_share_chooser_title)

    // Build the optional weather label ONLY when the opt-in feature is on AND this
    // ride carries a stored snapshot. It's shown as a flat icon + temperature next
    // to the headline distance; the temperature format mirrors the detail & analysis
    // views (shared weather formatter) and the icon is the SAME Material
    // WeatherCondition.icon used by the map chip, rasterised flat & tinted white.
    val weatherIcon = ride.weather
        ?.takeIf { weatherEnabled }
        ?.let { rememberFlatWeatherIcon(WmoWeatherCode.fromCode(it.weatherCode).icon) }

    val weatherLabel: RideShareWeatherLabel? = ride.weather
        ?.takeIf { weatherEnabled }
        ?.let { weather ->
            weatherIcon?.let { icon ->
                RideShareWeatherLabel(
                    icon = icon,
                    temperature = formatWeatherTemperature(weather.temperatureC),
                    wind = weather.windSpeedMps?.let {
                        stringResource(R.string.weather_label_wind) + " " + formatWeatherWind(it)
                    }
                )
            }
        }

    val dateLabel = remember(ride.startedAt) {
        DateFormat.getDateInstance(DateFormat.LONG).format(Date(ride.startedAt))
    }

    var selectedTheme by remember { mutableStateOf(RideShareThemes.default) }

    // Fetch the real 2D map cutout once per ride (best effort — falls back to a
    // plain gradient panel when offline or on error). Independent of the theme.
    val mapLayer by produceState<RideMapLayer?>(initialValue = null, ride.id) {
        value = runCatching { snapshotRouteMap(context, ride) }.getOrNull()
    }

    // Re-render the card whenever the theme (or the map layer) changes, so the
    // preview updates live as the user taps through the colour swatches.
    val bitmap by produceState<Bitmap?>(initialValue = null, ride.id, selectedTheme, mapLayer, weatherLabel) {
        value = null
        value = withContext(Dispatchers.Default) {
            renderRideShareCard(
                ride = ride,
                dateLabel = dateLabel,
                labels = RideShareLabels(
                    headline = headline,
                    durationLabel = durationLabel,
                    avgSpeedLabel = avgSpeedLabel,
                    elevationLabel = elevationLabel,
                    maxSpeedLabel = maxSpeedLabel,
                    footer = footer,
                    weather = weatherLabel
                ),
                theme = selectedTheme,
                mapLayer = mapLayer
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
                    text = stringResource(R.string.ride_share_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.ride_share_dialog_subtitle),
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
                    Spacer(Modifier.height(0.dp))
                    Text(
                        text = stringResource(R.string.ride_share_action),
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

/**
 * Rasterises a Material [imageVector] (the same `WeatherCondition.icon` used by the
 * map weather chip) into a flat, **white-tinted** Android [Bitmap] so the off-screen
 * `Canvas` share-card renderer — which can't draw a Compose `ImageVector` — can blit
 * it next to the headline distance. The result matches the flat icon look of the
 * map pill (just tinted white to sit on the card's bold headline).
 */
@Composable
private fun rememberFlatWeatherIcon(imageVector: ImageVector): Bitmap {
    val painter = rememberVectorPainter(image = imageVector)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    return remember(painter) {
        val sizePx = 168 // 2× the drawn 84px so it stays crisp when scaled.
        val imageBitmap = ImageBitmap(sizePx, sizePx)
        val canvas = Canvas(imageBitmap)
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = layoutDirection,
            canvas = canvas,
            size = Size(sizePx.toFloat(), sizePx.toFloat())
        ) {
            with(painter) {
                draw(size = this@draw.size, colorFilter = ColorFilter.tint(Color.White))
            }
        }
        imageBitmap.asAndroidBitmap()
    }
}

/**
 * A horizontal row of circular gradient swatches — one per [RideShareThemes.all]
 * entry. The selected swatch is highlighted with a ring; tapping one updates the
 * card preview live.
 */
@Composable
internal fun ThemePicker(
    selected: RideShareTheme,
    onSelect: (RideShareTheme) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ride_share_theme_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RideShareThemes.all.forEach { theme ->
                val isSelected = theme.id == selected.id
                val ringColor =
                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .border(width = 3.dp, color = ringColor, shape = CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(theme.gradientTop),
                                    Color(theme.gradientMid),
                                    Color(theme.gradientBottom)
                                )
                            )
                        )
                        .clickable { onSelect(theme) }
                )
            }
        }
    }
}



