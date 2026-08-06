package de.velospot.core.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure-JVM, **100% offline** sunrise / sunset calculator.
 *
 * Implements the standard **NOAA sunrise/sunset equation** (a.k.a. the "sunrise
 * equation") from latitude, longitude and a calendar date — no network access and
 * no Android location APIs, so it is trivially unit-testable on the JVM. The only
 * dependencies are `java.time` and `kotlin.math`.
 *
 * The official zenith of **90.833°** (i.e. an altitude of -0.833°) is used, which
 * accounts for the standard atmospheric refraction at the horizon plus the sun's
 * apparent radius — the same threshold NOAA uses for "official" sunrise/sunset.
 *
 * Algorithm reference:
 *  - NOAA Solar Calculator equations, https://gml.noaa.gov/grad/solcalc/
 *  - "Sunrise equation", https://en.wikipedia.org/wiki/Sunrise_equation
 *
 * ### Edge cases (polar day / polar night)
 * At high latitudes the sun may never rise or never set on a given day. In that
 * case the hour-angle cosine falls outside `[-1, 1]` and [compute] returns `null`
 * for the affected event instead of crashing — callers must handle nulls.
 */
object SunTimes {

    /**
     * The computed solar events for one day at one location. Either field may be
     * `null` on polar day / polar night, when that event does not occur.
     */
    data class SunEvents(val sunrise: Instant?, val sunset: Instant?)

    /** Official sunrise/sunset zenith angle in degrees (includes refraction). */
    private const val ZENITH_DEGREES = 90.833

    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

    /**
     * Computes the sunrise and sunset [Instant]s for the given [date] at the given
     * [latitude] / [longitude] (decimal degrees, north/east positive).
     *
     * @param zone the time zone used to resolve the local calendar [date] into UTC
     *   instants; defaults to the system zone. Only the date is used from it — the
     *   underlying calculation is done in UTC.
     * @return a [SunEvents] with `null` for any event that does not occur that day
     *   (polar day / polar night).
     */
    fun compute(
        latitude: Double,
        longitude: Double,
        date: LocalDate,
        // Accepted for API completeness / future use; the underlying computation is
        // performed in UTC and the resulting instants are zone-agnostic.
        @Suppress("UNUSED_PARAMETER") zone: ZoneId = ZoneId.systemDefault()
    ): SunEvents {
        val dayOfYear = date.dayOfYear

        val sunrise = computeEvent(latitude, longitude, dayOfYear, date, isSunrise = true)
        val sunset = computeEvent(latitude, longitude, dayOfYear, date, isSunrise = false)
        return SunEvents(sunrise = sunrise, sunset = sunset)
    }

    /**
     * Core sunrise-equation solver for a single event. Returns `null` when the sun
     * does not cross the horizon on that day (polar day/night), signalled by the
     * hour-angle cosine leaving the valid `[-1, 1]` range.
     */
    private fun computeEvent(
        latitude: Double,
        longitude: Double,
        dayOfYear: Int,
        date: LocalDate,
        isSunrise: Boolean
    ): Instant? {
        val lngHour = longitude / 15.0

        // Approximate time of the event (in days) — 6h for sunrise, 18h for sunset.
        val t = if (isSunrise) {
            dayOfYear + ((6.0 - lngHour) / 24.0)
        } else {
            dayOfYear + ((18.0 - lngHour) / 24.0)
        }

        // Sun's mean anomaly.
        val meanAnomaly = (0.9856 * t) - 3.289

        // Sun's true longitude (normalised to 0..360).
        var trueLongitude = meanAnomaly +
            (1.916 * sin(meanAnomaly * DEG_TO_RAD)) +
            (0.020 * sin(2 * meanAnomaly * DEG_TO_RAD)) +
            282.634
        trueLongitude = normalizeDegrees(trueLongitude)

        // Sun's right ascension, put in the same quadrant as the true longitude.
        var rightAscension = RAD_TO_DEG * Math.atan(0.91764 * tan(trueLongitude * DEG_TO_RAD))
        rightAscension = normalizeDegrees(rightAscension)
        val lQuadrant = floor(trueLongitude / 90.0) * 90.0
        val raQuadrant = floor(rightAscension / 90.0) * 90.0
        rightAscension += (lQuadrant - raQuadrant)
        rightAscension /= 15.0 // into hours

        // Sun's declination.
        val sinDec = 0.39782 * sin(trueLongitude * DEG_TO_RAD)
        val cosDec = cos(asin(sinDec))

        // Sun's local hour angle.
        val cosH = (cos(ZENITH_DEGREES * DEG_TO_RAD) - (sinDec * sin(latitude * DEG_TO_RAD))) /
            (cosDec * cos(latitude * DEG_TO_RAD))

        // Polar day (cosH < -1: sun never sets) / polar night (cosH > 1: never rises).
        if (cosH < -1.0 || cosH > 1.0) return null

        // Convert the hour angle into hours.
        val h = if (isSunrise) {
            360.0 - RAD_TO_DEG * acos(cosH)
        } else {
            RAD_TO_DEG * acos(cosH)
        } / 15.0

        // Local mean time of the event.
        val localMeanTime = h + rightAscension - (0.06571 * t) - 6.622

        // Back to UTC (in hours), normalised to 0..24.
        val utHours = normalizeHours(localMeanTime - lngHour)

        // Compose the UTC instant for the given calendar date.
        val totalSeconds = (utHours * 3600.0)
        val secondOfDay = totalSeconds.toLong()
        val utcMidnight = date.atStartOfDay(ZoneId.of("UTC")).toInstant()
        return utcMidnight.plus(Duration.ofSeconds(secondOfDay))
    }

    private fun normalizeDegrees(value: Double): Double {
        var v = value % 360.0
        if (v < 0) v += 360.0
        return v
    }

    private fun normalizeHours(value: Double): Double {
        var v = value % 24.0
        if (v < 0) v += 24.0
        return v
    }
}



