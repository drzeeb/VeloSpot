package de.velospot.data.repository

import de.velospot.data.remote.api.OpenMeteoApi
import de.velospot.data.remote.dto.OpenMeteoCurrent
import de.velospot.data.remote.dto.OpenMeteoResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Unit tests for [WeatherRepositoryImpl] using a hand-written fake [OpenMeteoApi]
 * (the project convention — no mocking library is used elsewhere) so the DTO ⇄
 * [de.velospot.domain.model.WeatherSnapshot] mapping and the "never throw" contract
 * are exercised directly.
 */
class WeatherRepositoryImplTest {

    /**
     * Fake [OpenMeteoApi] that returns a pre-canned [Response] or throws a
     * pre-canned error, so tests stay hermetic and offline.
     */
    private class FakeOpenMeteoApi(
        private val response: Response<OpenMeteoResponse>? = null,
        private val error: Throwable? = null,
    ) : OpenMeteoApi {
        var lastLat: Double? = null
        var lastLon: Double? = null

        override suspend fun currentForecast(
            lat: Double,
            lon: Double,
            current: String,
            windSpeedUnit: String,
            timezone: String,
        ): Response<OpenMeteoResponse> {
            lastLat = lat
            lastLon = lon
            error?.let { throw it }
            return response!!
        }
    }

    private fun fullCurrent() = OpenMeteoCurrent(
        time = "2024-05-01T12:00:00+02:00",
        temperature2m = 21.5,
        apparentTemperature = 20.0,
        relativeHumidity2m = 55,
        precipitation = 0.2,
        weatherCode = 3,
        windSpeed10m = 4.5,
        windDirection10m = 180,
    )

    @Test
    fun `happy path maps every field and echoes the requested coordinate`() = runTest {
        val api = FakeOpenMeteoApi(Response.success(OpenMeteoResponse(current = fullCurrent())))
        val repo = WeatherRepositoryImpl(api)

        val snapshot = repo.currentWeather(lat = 49.75, lon = 6.64)!!

        assertEquals(21.5, snapshot.temperatureC, 0.0)
        assertEquals(20.0, snapshot.apparentTemperatureC!!, 0.0)
        assertEquals(55, snapshot.humidityPct)
        assertEquals(0.2, snapshot.precipitationMm!!, 0.0)
        assertEquals(3, snapshot.weatherCode)
        assertEquals(4.5, snapshot.windSpeedMps!!, 0.0)
        assertEquals(180, snapshot.windDirectionDeg)
        assertEquals(49.75, snapshot.latitude, 0.0)
        assertEquals(6.64, snapshot.longitude, 0.0)
        // The parseable ISO-8601 time is converted to epoch millis (not "now").
        assertEquals(1_714_557_600_000L, snapshot.observedAt)
        assertEquals(49.75, api.lastLat)
        assertEquals(6.64, api.lastLon)
    }

    @Test
    fun `missing weather code falls back to the -1 sentinel`() = runTest {
        val api = FakeOpenMeteoApi(
            Response.success(OpenMeteoResponse(current = fullCurrent().copy(weatherCode = null))),
        )
        val repo = WeatherRepositoryImpl(api)

        val snapshot = repo.currentWeather(lat = 1.0, lon = 2.0)!!
        assertEquals(-1, snapshot.weatherCode)
    }

    @Test
    fun `network failure yields null and never throws`() = runTest {
        val api = FakeOpenMeteoApi(error = IOException("offline"))
        val repo = WeatherRepositoryImpl(api)

        assertNull(repo.currentWeather(lat = 49.75, lon = 6.64))
    }

    @Test
    fun `non-successful response yields null`() = runTest {
        val errorBody = "boom".toResponseBody("text/plain".toMediaType())
        val api = FakeOpenMeteoApi(Response.error(500, errorBody))
        val repo = WeatherRepositoryImpl(api)

        assertNull(repo.currentWeather(lat = 49.75, lon = 6.64))
    }

    @Test
    fun `null current block yields null`() = runTest {
        val api = FakeOpenMeteoApi(Response.success(OpenMeteoResponse(current = null)))
        val repo = WeatherRepositoryImpl(api)

        assertNull(repo.currentWeather(lat = 49.75, lon = 6.64))
    }

    @Test
    fun `missing temperature yields null even when the current block is present`() = runTest {
        val api = FakeOpenMeteoApi(
            Response.success(OpenMeteoResponse(current = fullCurrent().copy(temperature2m = null))),
        )
        val repo = WeatherRepositoryImpl(api)

        assertNull(repo.currentWeather(lat = 49.75, lon = 6.64))
    }
}


