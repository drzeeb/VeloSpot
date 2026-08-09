package de.velospot.core.map


/**
 * The toggleable map pin categories ("layers").
 */
enum class MapLayerCategory {
    /** All (non-favourite) bike parking spots. */
    PARKING,
    /** Favourited bike parking spots. */
    FAVORITES,
    /** User-saved custom places (pins saved as named favourites). */
    SAVED_PLACES,
    /**
     * The unified **"My rides"** overlay built from all recorded-ride GPS tracks.
     * A single on/off layer that renders either as thin per-ride lines or as a
     * heatmap, chosen via [RideTracksMode]. Replaces the former separate `HEATMAP`
     * and `TRACKS` categories, which shared the same underlying track geometry.
     */
    RIDE_TRACKS
}

/**
 * How the unified [MapLayerCategory.RIDE_TRACKS] overlay is drawn. Both renderers
 * are fed by the same recorded-ride geometry — this only picks which one is active.
 */
enum class RideTracksMode {
    /** Every recorded ride drawn as its own thin, translucent line. */
    LINES,
    /** All recorded tracks aggregated into a colour heatmap (where you ride most). */
    HEATMAP
}

/**
 * Which map pin categories are currently shown. Pin layers default to visible;
 * the recorded-ride [showRideTracks] overlay is opt-in (off by default) and is
 * drawn in the chosen [rideTracksMode].
 */
data class LayerVisibility(
    val showParking: Boolean = true,
    val showFavorites: Boolean = true,
    val showSavedPlaces: Boolean = true,
    val showRideTracks: Boolean = false,
    val rideTracksMode: RideTracksMode = RideTracksMode.LINES
) {
    fun isVisible(category: MapLayerCategory): Boolean = when (category) {
        MapLayerCategory.PARKING      -> showParking
        MapLayerCategory.FAVORITES    -> showFavorites
        MapLayerCategory.SAVED_PLACES -> showSavedPlaces
        MapLayerCategory.RIDE_TRACKS  -> showRideTracks
    }

    fun withVisibility(category: MapLayerCategory, visible: Boolean): LayerVisibility = when (category) {
        MapLayerCategory.PARKING      -> copy(showParking = visible)
        MapLayerCategory.FAVORITES    -> copy(showFavorites = visible)
        MapLayerCategory.SAVED_PLACES -> copy(showSavedPlaces = visible)
        MapLayerCategory.RIDE_TRACKS  -> copy(showRideTracks = visible)
    }
}

/**
 * Pure resolution of the unified ride-tracks layer state from the persisted keys,
 * including a graceful **one-way migration** from the two legacy booleans so no
 * existing user loses their setting.
 *
 * Precedence:
 *  1. If a new unified value has been written ([newVisible] / [newMode] non-null)
 *     it always wins — the user has interacted with the new UI.
 *  2. Otherwise the legacy keys seed the state: the layer is ON when *either* old
 *     overlay was on (`legacyHeatmap || legacyTracks`), and the mode defaults to
 *     [RideTracksMode.HEATMAP] when the old heatmap key was on (heatmap takes
 *     precedence over lines if both were somehow on), else [RideTracksMode.LINES].
 */
object RideTracksLayerState {
    fun resolve(
        newVisible: Boolean?,
        newMode: RideTracksMode?,
        legacyHeatmap: Boolean,
        legacyTracks: Boolean
    ): Pair<Boolean, RideTracksMode> {
        val visible = newVisible ?: (legacyHeatmap || legacyTracks)
        val mode = newMode ?: if (legacyHeatmap) RideTracksMode.HEATMAP else RideTracksMode.LINES
        return visible to mode
    }
}


