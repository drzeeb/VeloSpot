package de.velospot.domain.model

import androidx.compose.runtime.Immutable

/**
 * A single point-in-time weather observation for a coordinate, fetched from the
 * opt-in Open-Meteo integration.
 *
 * The raw WMO [weatherCode] is stored as-is (rather than a mapped enum) so the
 * snapshot stays a stable, serialisable value object; interpretation into an icon
 * / label happens at the presentation layer via
 * [de.velospot.core.weather.WmoWeatherCode].
 *
 * @property temperatureC Air temperature at 2 m, in degrees Celsius.
 * @property apparentTemperatureC "Feels like" temperature in degrees Celsius, or
 *  `null` when the provider did not return it.
 * @property humidityPct Relative humidity at 2 m, in whole percent, or `null` when absent.
 * @property precipitationMm Precipitation of the current period, in millimetres, or `null`.
 * @property weatherCode Raw WMO weather interpretation code (see
 *  [de.velospot.core.weather.WmoWeatherCode]); a safe sentinel (`-1`) when the
 *  provider omitted it.
 * @property windSpeedMps Wind speed at 10 m, in metres per second, or `null` when absent.
 * @property windDirectionDeg Wind direction at 10 m, in degrees (0 = from north), or `null`.
 * @property observedAt Wall-clock time of the observation in epoch milliseconds.
 * @property latitude WGS84 latitude of the requested location, in degrees.
 * @property longitude WGS84 longitude of the requested location, in degrees.
 */
@Immutable
data class WeatherSnapshot(
    val temperatureC: Double,
    val apparentTemperatureC: Double?,
    val humidityPct: Int?,
    val precipitationMm: Double?,
    val weatherCode: Int,
    val windSpeedMps: Double?,
    val windDirectionDeg: Int?,
    val observedAt: Long,
    val latitude: Double,
    val longitude: Double
)

