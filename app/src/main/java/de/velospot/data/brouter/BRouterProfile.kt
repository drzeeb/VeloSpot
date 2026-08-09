package de.velospot.data.brouter

import androidx.annotation.StringRes
import de.velospot.R

/**
 * Available BRouter routing profiles.
 * Display name and description are stored as string resource IDs so they are
 * automatically localised by the Android resource system.
 */
enum class BRouterProfile(
    val fileName: String,
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
    val typicalSpeedKmh: Double,
    /**
     * Whether this profile is offered in the user-facing routing-profile
     * selection UI. A hidden profile keeps its `.brf` asset and all engine
     * plumbing intact (so it can be re-enabled trivially) but never appears in
     * the chooser and can never be persisted as the active selection.
     */
    val userSelectable: Boolean = true
) {
    /**
     * Balanced touring profile – best for everyday city cycling.
     * Prefers bike lanes and quiet roads. **Recommended default.**
     */
    TREKKING(
        fileName        = "trekking",
        displayNameRes  = R.string.profile_trekking_name,
        descriptionRes  = R.string.profile_trekking_desc,
        typicalSpeedKmh = 14.0
    ),

    /**
     * Speed-optimised – maximises cycling speed on the fastest available
     * cycling infrastructure. Suited for experienced cyclists.
     */
    FASTBIKE(
        fileName        = "fastbike",
        displayNameRes  = R.string.profile_fastbike_name,
        descriptionRes  = R.string.profile_fastbike_desc,
        typicalSpeedKmh = 20.0
    ),

    /**
     * "Shortest regardless of bike-suitability." **Hidden from the selection UI**
     * (product decision #23): for a cycling app this almost always produces
     * unpleasant/unsafe routes. The enum entry, its `shortest.brf` asset and the
     * engine plumbing are intentionally kept in place so it can be re-enabled by
     * flipping [userSelectable] back to `true` — no other change needed.
     */
    SHORTEST(
        fileName        = "shortest",
        displayNameRes  = R.string.profile_shortest_name,
        descriptionRes  = R.string.profile_shortest_desc,
        typicalSpeedKmh = 13.0,
        userSelectable  = false
    ),
    MTN_BIKE(
        fileName        = "mtb",
        displayNameRes  = R.string.profile_mtb_name,
        descriptionRes  = R.string.profile_mtb_desc,
        typicalSpeedKmh = 10.0
    ),

    /**
     * Gravel / mixed-surface riding – balances speed and surface variety.
     */
    GRAVEL(
        fileName        = "gravel",
        displayNameRes  = R.string.profile_gravel_name,
        descriptionRes  = R.string.profile_gravel_desc,
        typicalSpeedKmh = 16.0
    );

    /** Speed in metres per second, derived from [typicalSpeedKmh]. */
    val typicalSpeedMs: Double get() = typicalSpeedKmh / 3.6

    companion object {
        /**
         * Safe fallback for defaults and for any persisted/legacy value that no
         * longer maps to a [userSelectable] profile.
         */
        val DEFAULT: BRouterProfile = TREKKING

        /**
         * Bike-appropriate profiles presented to the user, in declaration order.
         * Excludes hidden profiles such as [SHORTEST].
         */
        val selectableEntries: List<BRouterProfile> = entries.filter { it.userSelectable }
    }
}

