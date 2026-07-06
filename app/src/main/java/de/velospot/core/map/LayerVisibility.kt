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
    /** Heatmap of all recorded-ride GPS tracks (where you ride most). */
    HEATMAP,
    /** Every recorded ride drawn as its own thin line (where you have been). */
    TRACKS
}

/**
 * Which map pin categories are currently shown. Pin layers default to visible;
 * the recorded-ride [showHeatmap] and [showTracks] overlays are opt-in (off by
 * default).
 */
data class LayerVisibility(
    val showParking: Boolean = true,
    val showFavorites: Boolean = true,
    val showSavedPlaces: Boolean = true,
    val showHeatmap: Boolean = false,
    val showTracks: Boolean = false
) {
    fun isVisible(category: MapLayerCategory): Boolean = when (category) {
        MapLayerCategory.PARKING      -> showParking
        MapLayerCategory.FAVORITES    -> showFavorites
        MapLayerCategory.SAVED_PLACES -> showSavedPlaces
        MapLayerCategory.HEATMAP      -> showHeatmap
        MapLayerCategory.TRACKS       -> showTracks
    }

    fun withVisibility(category: MapLayerCategory, visible: Boolean): LayerVisibility = when (category) {
        MapLayerCategory.PARKING      -> copy(showParking = visible)
        MapLayerCategory.FAVORITES    -> copy(showFavorites = visible)
        MapLayerCategory.SAVED_PLACES -> copy(showSavedPlaces = visible)
        MapLayerCategory.HEATMAP      -> copy(showHeatmap = visible)
        MapLayerCategory.TRACKS       -> copy(showTracks = visible)
    }
}


