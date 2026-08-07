package de.velospot.domain.repository

import de.velospot.domain.model.WeatherSnapshot

/**
 * Reactive access to current weather for a coordinate, backed by the opt-in
 * Open-Meteo integration.
 *
 * Implementations must be **fail-soft**: any error, offline state or missing data
 * results in a `null` return rather than a thrown exception, so weather is always
 * a best-effort enhancement that can never crash or block a caller.
 */
interface WeatherRepository {

    /**
     * Current weather for [lat]/[lon], or `null` when it could not be fetched
     * (offline, non-successful response, missing data, or any failure). Never throws.
     */
    suspend fun currentWeather(lat: Double, lon: Double): WeatherSnapshot?
}

