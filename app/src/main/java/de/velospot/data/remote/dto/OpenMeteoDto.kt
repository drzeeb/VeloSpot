package de.velospot.data.remote.dto

import com.squareup.moshi.Json

/**
 * Top-level Moshi DTO for Open-Meteo's `v1/forecast` response. Only the `current`
 * block is requested/parsed; everything else is ignored.
 */
data class OpenMeteoResponse(
    @Json(name = "current") val current: OpenMeteoCurrent?
)

/**
 * The `current` weather block of an Open-Meteo forecast response. Every field is
 * nullable because the provider only returns the variables that were requested
 * (and may omit any of them on error / partial data).
 */
data class OpenMeteoCurrent(
    @Json(name = "time") val time: String?,
    @Json(name = "temperature_2m") val temperature2m: Double?,
    @Json(name = "apparent_temperature") val apparentTemperature: Double?,
    @Json(name = "relative_humidity_2m") val relativeHumidity2m: Int?,
    @Json(name = "precipitation") val precipitation: Double?,
    @Json(name = "weather_code") val weatherCode: Int?,
    @Json(name = "wind_speed_10m") val windSpeed10m: Double?,
    @Json(name = "wind_direction_10m") val windDirection10m: Int?
)

