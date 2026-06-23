package de.velospot.core.map

import android.content.Context
import androidx.core.content.edit

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

/**
 * Persists [LayerVisibility] across app restarts. Pin layers default to visible,
 * the recorded-ride heatmap to hidden.
 */
object LayerVisibilityPreferences {

    private const val PREFS_NAME      = "velospot_layers"
    private const val KEY_PARKING     = "layer_parking_visible"
    private const val KEY_FAVORITES   = "layer_favorites_visible"
    private const val KEY_SAVED       = "layer_saved_visible"
    private const val KEY_HEATMAP     = "layer_heatmap_visible"
    private const val KEY_TRACKS      = "layer_tracks_visible"

    fun get(context: Context): LayerVisibility {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return LayerVisibility(
            showParking     = prefs.getBoolean(KEY_PARKING, true),
            showFavorites   = prefs.getBoolean(KEY_FAVORITES, true),
            showSavedPlaces = prefs.getBoolean(KEY_SAVED, true),
            showHeatmap     = prefs.getBoolean(KEY_HEATMAP, false),
            showTracks      = prefs.getBoolean(KEY_TRACKS, false)
        )
    }

    fun setVisible(context: Context, category: MapLayerCategory, visible: Boolean) {
        val key = when (category) {
            MapLayerCategory.PARKING      -> KEY_PARKING
            MapLayerCategory.FAVORITES    -> KEY_FAVORITES
            MapLayerCategory.SAVED_PLACES -> KEY_SAVED
            MapLayerCategory.HEATMAP      -> KEY_HEATMAP
            MapLayerCategory.TRACKS       -> KEY_TRACKS
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(key, visible) }
    }
}

