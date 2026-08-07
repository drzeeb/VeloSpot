package de.velospot.core.weather

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import de.velospot.R

/**
 * A coarse, human-facing bucket of the WMO weather interpretation codes returned
 * by Open-Meteo's `weather_code` field.
 *
 * Each constant carries a Material [icon] suggestion and the [labelRes] string
 * resource for its localized label, so the presentation layer can render a
 * snapshot without re-deriving the mapping. These properties are plain
 * `Int`/`ImageVector` references and do **not** require an Android runtime, so
 * [WmoWeatherCode.fromCode] stays usable from pure JVM unit tests.
 */
enum class WeatherCondition(
    val icon: ImageVector,
    @StringRes val labelRes: Int
) {
    CLEAR(Icons.Filled.WbSunny, R.string.weather_condition_clear),
    MAINLY_CLEAR(Icons.Filled.WbSunny, R.string.weather_condition_mainly_clear),
    PARTLY_CLOUDY(Icons.Filled.WbCloudy, R.string.weather_condition_partly_cloudy),
    OVERCAST(Icons.Filled.Cloud, R.string.weather_condition_overcast),
    FOG(Icons.Filled.Cloud, R.string.weather_condition_fog),
    DRIZZLE(Icons.Filled.Grain, R.string.weather_condition_drizzle),
    RAIN(Icons.Filled.Umbrella, R.string.weather_condition_rain),
    FREEZING_RAIN(Icons.Filled.AcUnit, R.string.weather_condition_freezing_rain),
    SNOW(Icons.Filled.AcUnit, R.string.weather_condition_snow),
    SNOW_GRAINS(Icons.Filled.AcUnit, R.string.weather_condition_snow_grains),
    RAIN_SHOWERS(Icons.Filled.Umbrella, R.string.weather_condition_rain_showers),
    SNOW_SHOWERS(Icons.Filled.AcUnit, R.string.weather_condition_snow_showers),
    THUNDERSTORM(Icons.Filled.Thunderstorm, R.string.weather_condition_thunderstorm),
    THUNDERSTORM_HAIL(Icons.Filled.Thunderstorm, R.string.weather_condition_thunderstorm_hail),
    UNKNOWN(Icons.Filled.Cloud, R.string.weather_condition_unknown)
}

/**
 * Pure, JVM-testable mapping of Open-Meteo / WMO `weather_code` integers to a
 * coarse [WeatherCondition] bucket.
 *
 * Reference: https://open-meteo.com/en/docs (WW interpretation codes).
 */
object WmoWeatherCode {

    /** Maps a raw WMO [code] to its [WeatherCondition], defaulting to [WeatherCondition.UNKNOWN]. */
    fun fromCode(code: Int): WeatherCondition = when (code) {
        0 -> WeatherCondition.CLEAR
        1 -> WeatherCondition.MAINLY_CLEAR
        2 -> WeatherCondition.PARTLY_CLOUDY
        3 -> WeatherCondition.OVERCAST
        45, 48 -> WeatherCondition.FOG
        51, 53, 55 -> WeatherCondition.DRIZZLE
        56, 57 -> WeatherCondition.FREEZING_RAIN
        61, 63, 65 -> WeatherCondition.RAIN
        66, 67 -> WeatherCondition.FREEZING_RAIN
        71, 73, 75 -> WeatherCondition.SNOW
        77 -> WeatherCondition.SNOW_GRAINS
        80, 81, 82 -> WeatherCondition.RAIN_SHOWERS
        85, 86 -> WeatherCondition.SNOW_SHOWERS
        95 -> WeatherCondition.THUNDERSTORM
        96, 99 -> WeatherCondition.THUNDERSTORM_HAIL
        else -> WeatherCondition.UNKNOWN
    }
}

