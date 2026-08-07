package de.velospot.core.weather

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM unit tests for [WmoWeatherCode.fromCode]. These only touch the enum
 * mapping (no Android runtime), verifying every documented WMO bucket boundary.
 */
class WmoWeatherCodeTest {

    @Test
    fun `maps known codes to their conditions`() {
        assertEquals(WeatherCondition.CLEAR, WmoWeatherCode.fromCode(0))
        assertEquals(WeatherCondition.MAINLY_CLEAR, WmoWeatherCode.fromCode(1))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, WmoWeatherCode.fromCode(2))
        assertEquals(WeatherCondition.OVERCAST, WmoWeatherCode.fromCode(3))
        assertEquals(WeatherCondition.FOG, WmoWeatherCode.fromCode(45))
        assertEquals(WeatherCondition.FOG, WmoWeatherCode.fromCode(48))
        assertEquals(WeatherCondition.DRIZZLE, WmoWeatherCode.fromCode(51))
        assertEquals(WeatherCondition.DRIZZLE, WmoWeatherCode.fromCode(53))
        assertEquals(WeatherCondition.DRIZZLE, WmoWeatherCode.fromCode(55))
        assertEquals(WeatherCondition.FREEZING_RAIN, WmoWeatherCode.fromCode(56))
        assertEquals(WeatherCondition.FREEZING_RAIN, WmoWeatherCode.fromCode(57))
        assertEquals(WeatherCondition.RAIN, WmoWeatherCode.fromCode(61))
        assertEquals(WeatherCondition.RAIN, WmoWeatherCode.fromCode(63))
        assertEquals(WeatherCondition.RAIN, WmoWeatherCode.fromCode(65))
        assertEquals(WeatherCondition.FREEZING_RAIN, WmoWeatherCode.fromCode(66))
        assertEquals(WeatherCondition.FREEZING_RAIN, WmoWeatherCode.fromCode(67))
        assertEquals(WeatherCondition.SNOW, WmoWeatherCode.fromCode(71))
        assertEquals(WeatherCondition.SNOW, WmoWeatherCode.fromCode(73))
        assertEquals(WeatherCondition.SNOW, WmoWeatherCode.fromCode(75))
        assertEquals(WeatherCondition.SNOW_GRAINS, WmoWeatherCode.fromCode(77))
        assertEquals(WeatherCondition.RAIN_SHOWERS, WmoWeatherCode.fromCode(80))
        assertEquals(WeatherCondition.RAIN_SHOWERS, WmoWeatherCode.fromCode(81))
        assertEquals(WeatherCondition.RAIN_SHOWERS, WmoWeatherCode.fromCode(82))
        assertEquals(WeatherCondition.SNOW_SHOWERS, WmoWeatherCode.fromCode(85))
        assertEquals(WeatherCondition.SNOW_SHOWERS, WmoWeatherCode.fromCode(86))
        assertEquals(WeatherCondition.THUNDERSTORM, WmoWeatherCode.fromCode(95))
        assertEquals(WeatherCondition.THUNDERSTORM_HAIL, WmoWeatherCode.fromCode(96))
        assertEquals(WeatherCondition.THUNDERSTORM_HAIL, WmoWeatherCode.fromCode(99))
    }

    @Test
    fun `maps unknown or out-of-range codes to UNKNOWN`() {
        assertEquals(WeatherCondition.UNKNOWN, WmoWeatherCode.fromCode(-1))
        assertEquals(WeatherCondition.UNKNOWN, WmoWeatherCode.fromCode(4))
        assertEquals(WeatherCondition.UNKNOWN, WmoWeatherCode.fromCode(100))
        assertEquals(WeatherCondition.UNKNOWN, WmoWeatherCode.fromCode(999))
    }
}

