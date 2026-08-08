package de.velospot.feature.map.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import de.velospot.core.weather.WmoWeatherCode
import de.velospot.domain.model.WeatherSnapshot
import kotlin.math.roundToInt

/**
 * Compact, tappable **current-weather chip** shown on the map when the opt-in
 * Open-Meteo feature is enabled and a snapshot is available.
 *
 * The collapsed chip mirrors the map's other rounded overlay pills (a small
 * [Surface] with the condition icon + rounded-to-whole-degree temperature).
 * Tapping it expands a small [DropdownMenu] with the fuller reading (feels-like,
 * wind, humidity, precipitation) using the shared `weather_label_*` strings.
 *
 * Callers should only compose this when the snapshot is non-null, which already
 * implies the feature is enabled (the ViewModel clears it when disabled).
 */
@Composable
internal fun WeatherChip(
    weather: WeatherSnapshot,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val condition = remember(weather.weatherCode) { WmoWeatherCode.fromCode(weather.weatherCode) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Box {
            Row(
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = condition.icon,
                    contentDescription = stringResource(condition.labelRes),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatCelsius(weather.temperatureC),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.weather_heading),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(condition.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    WeatherDetailRow(
                        label = stringResource(R.string.weather_label_temperature),
                        value = formatCelsius(weather.temperatureC)
                    )
                    weather.apparentTemperatureC?.let {
                        WeatherDetailRow(
                            label = stringResource(R.string.weather_label_feels_like),
                            value = formatCelsius(it)
                        )
                    }
                    weather.windSpeedMps?.let {
                        WeatherDetailRow(
                            label = stringResource(R.string.weather_label_wind),
                            value = "${it.roundToInt()} m/s"
                        )
                    }
                    weather.humidityPct?.let {
                        WeatherDetailRow(
                            label = stringResource(R.string.weather_label_humidity),
                            value = "$it %"
                        )
                    }
                    weather.precipitationMm?.let {
                        WeatherDetailRow(
                            label = stringResource(R.string.weather_label_precipitation),
                            value = "%.1f mm".format(it)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherDetailRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Formats a Celsius temperature as a whole-degree string, e.g. "12°". */
internal fun formatCelsius(celsius: Double): String = "${celsius.roundToInt()}°"

