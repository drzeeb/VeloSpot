package de.velospot.data.brouter

import androidx.annotation.StringRes
import de.velospot.R

/**
 * How strongly route calculation should **avoid climbing** — the discrete steps
 * behind the "route hilliness" slider.
 *
 * Each level maps to an extra uphill cost ([uphillExtraCost]) that is fed to
 * BRouter as the `uphill_extra` profile parameter (see the bundled `.brf`
 * profiles). It is **added** to the profile's own uphill cost, so [ANY] (`0`)
 * leaves routing exactly as before — flatter levels progressively penalise
 * ascending segments, nudging the router towards flatter (usually slightly
 * longer) routes.
 *
 * Only affects **offline (BRouter)** routing; the online OSRM fallback has no
 * elevation model and ignores it.
 */
enum class ElevationPreference(
    @StringRes val labelRes: Int,
    val uphillExtraCost: Int
) {
    /** Don't avoid hills — shortest/quickest per the active profile (default). */
    ANY(R.string.elevation_level_any, 0),

    /** Slightly prefer flatter ground. */
    GENTLE(R.string.elevation_level_gentle, 20),

    /** Noticeably prefer flatter routes. */
    MODERATE(R.string.elevation_level_moderate, 45),

    /** Strongly avoid climbs. */
    LOW(R.string.elevation_level_low, 75),

    /** Flattest reasonable route, accepting detours to dodge ascents. */
    FLATTEST(R.string.elevation_level_flattest, 115);

    companion object {
        /** Preserves the previous behaviour (no extra uphill penalty). */
        val DEFAULT = ANY

        /** Maps a persisted slider position (ordinal) back to a level, safely. */
        fun fromOrdinal(ordinal: Int): ElevationPreference =
            entries.getOrNull(ordinal) ?: DEFAULT
    }
}

