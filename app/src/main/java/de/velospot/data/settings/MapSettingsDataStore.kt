package de.velospot.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import de.velospot.core.map.LayerVisibility
import de.velospot.core.map.MapLayerCategory
import de.velospot.core.map.RideViewOptions
import de.velospot.domain.repository.MapSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore file backing [MapSettingsDataStore]. Declared as a `Context`
 * extension (the recommended pattern) so a single instance is shared per process.
 *
 * [produceMigrations] one-off-imports the values from the legacy
 * `SharedPreferences` files so no user setting is lost on upgrade. The DataStore
 * keys deliberately match the old `SharedPreferences` keys, so each migration is a
 * plain copy.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "velospot_settings",
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(context, "velospot_layers"),
            SharedPreferencesMigration(context, "velospot_navigation"),
            SharedPreferencesMigration(context, "velospot_display"),
            SharedPreferencesMigration(context, "velospot_ride_view")
        )
    }
)

/**
 * DataStore-backed [MapSettingsRepository]. Reads are non-blocking [Flow]s and
 * writes are transactional `suspend` edits, replacing the previous main-thread
 * `SharedPreferences` accesses.
 */
class MapSettingsDataStore(private val context: Context) : MapSettingsRepository {

    private val data: Flow<Preferences> get() = context.settingsDataStore.data

    override val layerVisibility: Flow<LayerVisibility> = data.map { prefs ->
        LayerVisibility(
            showParking     = prefs[KEY_LAYER_PARKING] ?: true,
            showFavorites   = prefs[KEY_LAYER_FAVORITES] ?: true,
            showSavedPlaces = prefs[KEY_LAYER_SAVED] ?: true,
            showHeatmap     = prefs[KEY_LAYER_HEATMAP] ?: false,
            showTracks      = prefs[KEY_LAYER_TRACKS] ?: false
        )
    }

    override val is3DNavigation: Flow<Boolean> =
        data.map { it[KEY_NAV_3D] ?: true }

    override val voiceGuidanceEnabled: Flow<Boolean> =
        data.map { it[KEY_VOICE_GUIDANCE] ?: false }

    override val keepScreenOnEnabled: Flow<Boolean> =
        data.map { it[KEY_KEEP_SCREEN_ON] ?: true }

    override val rideViewOptions: Flow<RideViewOptions> = data.map { prefs ->
        RideViewOptions(
            showMaxSpeedBubble = prefs[KEY_MAX_SPEED_BUBBLE] ?: true,
            colorTrackBySpeed  = prefs[KEY_COLOR_BY_SPEED] ?: false
        )
    }

    override suspend fun setLayerVisible(category: MapLayerCategory, visible: Boolean) {
        val key = when (category) {
            MapLayerCategory.PARKING      -> KEY_LAYER_PARKING
            MapLayerCategory.FAVORITES    -> KEY_LAYER_FAVORITES
            MapLayerCategory.SAVED_PLACES -> KEY_LAYER_SAVED
            MapLayerCategory.HEATMAP      -> KEY_LAYER_HEATMAP
            MapLayerCategory.TRACKS       -> KEY_LAYER_TRACKS
        }
        context.settingsDataStore.edit { it[key] = visible }
    }

    override suspend fun set3DNavigation(enabled: Boolean) =
        put(KEY_NAV_3D, enabled)

    override suspend fun setVoiceGuidance(enabled: Boolean) =
        put(KEY_VOICE_GUIDANCE, enabled)

    override suspend fun setKeepScreenOn(enabled: Boolean) =
        put(KEY_KEEP_SCREEN_ON, enabled)

    override suspend fun setShowMaxSpeedBubble(enabled: Boolean) =
        put(KEY_MAX_SPEED_BUBBLE, enabled)

    override suspend fun setColorTrackBySpeed(enabled: Boolean) =
        put(KEY_COLOR_BY_SPEED, enabled)

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private companion object {
        // Keys mirror the legacy SharedPreferences keys so the migration is a copy.
        val KEY_LAYER_PARKING    = booleanPreferencesKey("layer_parking_visible")
        val KEY_LAYER_FAVORITES  = booleanPreferencesKey("layer_favorites_visible")
        val KEY_LAYER_SAVED      = booleanPreferencesKey("layer_saved_visible")
        val KEY_LAYER_HEATMAP    = booleanPreferencesKey("layer_heatmap_visible")
        val KEY_LAYER_TRACKS     = booleanPreferencesKey("layer_tracks_visible")
        val KEY_NAV_3D           = booleanPreferencesKey("navigation_3d_enabled")
        val KEY_VOICE_GUIDANCE   = booleanPreferencesKey("navigation_tts_enabled")
        val KEY_KEEP_SCREEN_ON   = booleanPreferencesKey("keep_screen_on_enabled")
        val KEY_MAX_SPEED_BUBBLE = booleanPreferencesKey("show_max_speed_bubble")
        val KEY_COLOR_BY_SPEED   = booleanPreferencesKey("color_track_by_speed")
    }
}

