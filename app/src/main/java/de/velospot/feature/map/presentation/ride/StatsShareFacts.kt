package de.velospot.feature.map.presentation.ride

/**
 * Pure, JVM-unit-testable helpers deciding which "bro flex" badges qualify for the
 * shareable all-time statistics card, plus the underlying ratios they show.
 *
 * Kept free of Android APIs so the (deterministic) badge logic can be verified with
 * plain unit tests — the [android.graphics.Canvas] renderer only draws what these
 * decide.
 */

/** Height of Mount Everest in metres, used for the "×Everest" elevation flex. */
internal const val EVEREST_HEIGHT_METERS = 8_848.0

/** How many times the accumulated elevation gain stacks up to Everest's height. */
internal fun everestRatio(gainMeters: Double): Double =
    if (gainMeters <= 0.0) 0.0 else gainMeters / EVEREST_HEIGHT_METERS

/** The "% around the world" badge only makes sense once any distance was ridden. */
internal fun qualifiesWorldBadge(earthCircumferencePercent: Double): Boolean =
    earthCircumferencePercent > 0.0

/** The "×Everest" badge shows once the rider has climbed at least ~5 % of Everest. */
internal fun qualifiesEverestBadge(gainMeters: Double): Boolean =
    everestRatio(gainMeters) >= 0.05

/** The streak badge is only a flex from two consecutive active days upwards. */
internal fun qualifiesStreakBadge(longestStreakDays: Int): Boolean =
    longestStreakDays >= 2

