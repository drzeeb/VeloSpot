package de.velospot.data.remote.api

import de.velospot.data.remote.dto.OpenMeteoResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Retrofit interface for the Open-Meteo forecast endpoint.
 *
 * Base URL: https://api.open-meteo.com/
 *
 * Open-Meteo's public instance is free for non-commercial use and requires no API
 * key; a self-identifying User-Agent is sent for good measure (mirrors PhotonApi).
 */
interface OpenMeteoApi {

    /**
     * Current-conditions forecast for a coordinate. The `current` variable list,
     * wind-speed unit (m/s) and timezone (`auto`) are fixed defaults so callers
     * only pass the location.
     */
    @GET("v1/forecast")
    @Headers("User-Agent: VeloSpot/1.0 (https://github.com/velospot)")
    suspend fun currentForecast(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double,
        @Query("current") current: String =
            "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation," +
                "weather_code,wind_speed_10m,wind_direction_10m",
        @Query("wind_speed_unit") windSpeedUnit: String = "ms",
        @Query("timezone") timezone: String = "auto"
    ): Response<OpenMeteoResponse>
}

