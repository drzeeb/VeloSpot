package de.velospot.data.repository

import de.velospot.data.remote.api.OpenMeteoApi
import de.velospot.domain.model.WeatherSnapshot
import de.velospot.domain.repository.WeatherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Open-Meteo-backed [WeatherRepository].
 *
 * The network call runs on [Dispatchers.IO] and the whole flow is wrapped in
 * `runCatching { ... }.getOrNull()` so it **never** throws to callers: any
 * failure, offline state, non-successful response or missing/incomplete `current`
 * block yields `null`.
 *
 * A small in-memory cache keeps the last snapshot and short-circuits requests that
 * are both recent (within [CACHE_TTL_MS]) and close (within [CACHE_RADIUS_M]) to
 * stay polite toward the free public instance.
 */
class WeatherRepositoryImpl(
    private val api: OpenMeteoApi
) : WeatherRepository {

    @Volatile
    private var cached: WeatherSnapshot? = null

    @Volatile
    private var cachedAt: Long = 0L

    override suspend fun currentWeather(lat: Double, lon: Double): WeatherSnapshot? {
        // Serve a fresh, nearby cached snapshot without hitting the network.
        cached?.let { snapshot ->
            val fresh = System.currentTimeMillis() - cachedAt <= CACHE_TTL_MS
            val near = approxDistanceMeters(snapshot.latitude, snapshot.longitude, lat, lon) <= CACHE_RADIUS_M
            if (fresh && near) return snapshot
        }

        return runCatching {
            withContext(Dispatchers.IO) {
                val response = api.currentForecast(lat = lat, lon = lon)
                if (!response.isSuccessful) return@withContext null
                val current = response.body()?.current ?: return@withContext null
                val temperature = current.temperature2m ?: return@withContext null

                val snapshot = WeatherSnapshot(
                    temperatureC = temperature,
                    apparentTemperatureC = current.apparentTemperature,
                    humidityPct = current.relativeHumidity2m,
                    precipitationMm = current.precipitation,
                    weatherCode = current.weatherCode ?: -1,
                    windSpeedMps = current.windSpeed10m,
                    windDirectionDeg = current.windDirection10m,
                    observedAt = parseObservedAt(current.time),
                    latitude = lat,
                    longitude = lon
                )
                cached = snapshot
                cachedAt = System.currentTimeMillis()
                snapshot
            }
        }.getOrNull()
    }

    /**
     * Parses Open-Meteo's ISO-8601 `current.time` to epoch millis, falling back to
     * "now" when it is missing or unparseable (the string can be a local time
     * without an offset, in which case we cannot reliably convert it).
     */
    private fun parseObservedAt(time: String?): Long {
        if (time.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            OffsetDateTime.parse(time).toInstant().toEpochMilli()
        }.getOrElse {
            if (it is DateTimeParseException) System.currentTimeMillis() else throw it
        }
    }

    /**
     * Cheap equirectangular approximation of the distance (metres) between two
     * coordinates — accurate enough for the ~2 km cache-hit test.
     */
    private fun approxDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val meanLatRad = Math.toRadians((lat1 + lat2) / 2.0)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1) * cos(meanLatRad)
        return EARTH_RADIUS_M * sqrt(dLat.pow(2) + dLon.pow(2))
    }

    private companion object {
        /** Cache freshness window: 10 minutes. */
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** Cache proximity window: ~2 km. */
        private const val CACHE_RADIUS_M = 2_000.0

        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}

