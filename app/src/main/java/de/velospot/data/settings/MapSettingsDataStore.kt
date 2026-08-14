package de.velospot.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.velospot.core.backup.BackupSchema
import de.velospot.core.backup.SettingBackup
import de.velospot.core.backup.SettingType
import de.velospot.core.map.LayerVisibility
import de.velospot.core.map.MapLayerCategory
import de.velospot.core.map.RideTracksLayerState
import de.velospot.core.map.RideTracksMode
import de.velospot.core.map.RideViewOptions
import de.velospot.domain.repository.MapSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

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
class MapSettingsDataStore(private val context: Context) : MapSettingsRepository, AppSettingsBackup {

    private val data: Flow<Preferences> get() = context.settingsDataStore.data

    override val layerVisibility: Flow<LayerVisibility> = data.map { prefs ->
        // The two legacy overlays (heatmap / ridden tracks) were merged into one
        // unified "My rides" layer with a display mode. Resolve the unified state
        // from the new keys, seeding from the legacy booleans so existing users
        // keep their choice (see RideTracksLayerState for the exact precedence).
        val (showRideTracks, rideTracksMode) = RideTracksLayerState.resolve(
            newVisible    = prefs[KEY_LAYER_RIDE_TRACKS],
            newMode       = prefs[KEY_RIDE_TRACKS_MODE]?.let { name ->
                runCatching { RideTracksMode.valueOf(name) }.getOrNull()
            },
            legacyHeatmap = prefs[KEY_LAYER_HEATMAP] ?: false,
            legacyTracks  = prefs[KEY_LAYER_TRACKS] ?: false
        )
        LayerVisibility(
            showParking     = prefs[KEY_LAYER_PARKING] ?: true,
            showFavorites   = prefs[KEY_LAYER_FAVORITES] ?: true,
            showSavedPlaces = prefs[KEY_LAYER_SAVED] ?: true,
            showRideTracks  = showRideTracks,
            rideTracksMode  = rideTracksMode
        )
    }

    override val is3DNavigation: Flow<Boolean> =
        data.map { it[KEY_NAV_3D] ?: true }

    override val voiceGuidanceEnabled: Flow<Boolean> =
        data.map { it[KEY_VOICE_GUIDANCE] ?: false }

    override val keepScreenOnEnabled: Flow<Boolean> =
        data.map { it[KEY_KEEP_SCREEN_ON] ?: true }

    override val hudEnabled: Flow<Boolean> =
        data.map { it[KEY_HUD_ENABLED] ?: false }

    override val hudExpanded: Flow<Boolean> =
        data.map { it[KEY_HUD_EXPANDED] ?: false }

    override val portraitLockEnabled: Flow<Boolean> =
        data.map { it[KEY_PORTRAIT_LOCK] ?: false }

    override val roundedBuildingsEnabled: Flow<Boolean> =
        data.map { it[KEY_ROUNDED_BUILDINGS] ?: false }

    override val amoledEnabled: Flow<Boolean> =
        data.map { it[KEY_AMOLED] ?: false }

    override val sunAlertEnabled: Flow<Boolean> =
        data.map { it[KEY_SUN_ALERT] ?: true }

    override val weatherEnabled: Flow<Boolean> =
        data.map { it[KEY_WEATHER_ENABLED] ?: false }

    override val rideViewOptions: Flow<RideViewOptions> = data.map { prefs ->
        RideViewOptions(
            showMaxSpeedBubble = prefs[KEY_MAX_SPEED_BUBBLE] ?: true,
            colorTrackBySpeed  = prefs[KEY_COLOR_BY_SPEED] ?: false
        )
    }

    override val onboardingCompleted: Flow<Boolean> =
        data.map { it[KEY_ONBOARDING_DONE] ?: false }

    override suspend fun setLayerVisible(category: MapLayerCategory, visible: Boolean) {
        val key = when (category) {
            MapLayerCategory.PARKING      -> KEY_LAYER_PARKING
            MapLayerCategory.FAVORITES    -> KEY_LAYER_FAVORITES
            MapLayerCategory.SAVED_PLACES -> KEY_LAYER_SAVED
            MapLayerCategory.RIDE_TRACKS  -> KEY_LAYER_RIDE_TRACKS
        }
        context.settingsDataStore.edit { it[key] = visible }
    }

    override suspend fun setRideTracksMode(mode: RideTracksMode) {
        context.settingsDataStore.edit { it[KEY_RIDE_TRACKS_MODE] = mode.name }
    }

    override suspend fun set3DNavigation(enabled: Boolean) =
        put(KEY_NAV_3D, enabled)

    override suspend fun setVoiceGuidance(enabled: Boolean) =
        put(KEY_VOICE_GUIDANCE, enabled)

    override suspend fun setKeepScreenOn(enabled: Boolean) =
        put(KEY_KEEP_SCREEN_ON, enabled)

    override suspend fun setHudEnabled(enabled: Boolean) =
        put(KEY_HUD_ENABLED, enabled)

    override suspend fun setHudExpanded(expanded: Boolean) =
        put(KEY_HUD_EXPANDED, expanded)

    override suspend fun setPortraitLock(enabled: Boolean) =
        put(KEY_PORTRAIT_LOCK, enabled)

    override suspend fun setRoundedBuildings(enabled: Boolean) =
        put(KEY_ROUNDED_BUILDINGS, enabled)

    override suspend fun setAmoled(enabled: Boolean) =
        put(KEY_AMOLED, enabled)

    override suspend fun setSunAlertEnabled(enabled: Boolean) =
        put(KEY_SUN_ALERT, enabled)

    override suspend fun setWeatherEnabled(enabled: Boolean) =
        put(KEY_WEATHER_ENABLED, enabled)

    override suspend fun setShowMaxSpeedBubble(enabled: Boolean) =
        put(KEY_MAX_SPEED_BUBBLE, enabled)

    override suspend fun setColorTrackBySpeed(enabled: Boolean) =
        put(KEY_COLOR_BY_SPEED, enabled)

    override suspend fun setOnboardingCompleted(completed: Boolean) =
        put(KEY_ONBOARDING_DONE, completed)

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.settingsDataStore.edit { it[key] = value }
    }

    // ── Backup & Restore (AppSettingsBackup) ──────────────────────────────────

    /** SharedPreferences file backing the offline-routing choices (see OfflineRoutingPreferences). */
    private val offlineRoutingPrefs
        get() = context.getSharedPreferences("velospot_offline_routing", Context.MODE_PRIVATE)

    override suspend fun exportSettings(): List<SettingBackup> {
        val out = mutableListOf<SettingBackup>()

        // The map/app settings DataStore — every stored key, whatever its type.
        val prefs = context.settingsDataStore.data.first()
        prefs.asMap().forEach { (key, value) ->
            encodeValue(value)?.let { (type, str) ->
                out += SettingBackup(BackupSchema.SETTINGS_STORE_DATASTORE, key.name, type.name, str)
            }
        }

        // The offline-routing SharedPreferences (profile, hilliness, on-demand, …).
        offlineRoutingPrefs.all.forEach { (key, value) ->
            if (value == null) return@forEach
            encodeValue(value)?.let { (type, str) ->
                out += SettingBackup(BackupSchema.SETTINGS_STORE_OFFLINE_ROUTING, key, type.name, str)
            }
        }
        return out
    }

    override suspend fun importSettings(entries: List<SettingBackup>) {
        // Replace-all: clear each covered store, then write the backed-up values.
        context.settingsDataStore.edit { prefs ->
            prefs.clear()
            entries.filter { it.store == BackupSchema.SETTINGS_STORE_DATASTORE }
                .forEach { entry -> writeDataStoreEntry(prefs, entry) }
        }
        offlineRoutingPrefs.edit().apply {
            clear()
            entries.filter { it.store == BackupSchema.SETTINGS_STORE_OFFLINE_ROUTING }
                .forEach { entry -> writeSharedPrefEntry(this, entry) }
            apply()
        }
    }

    /** Maps a raw preference value to its [SettingType] + string form, or `null` if unsupported. */
    private fun encodeValue(value: Any?): Pair<SettingType, String>? = when (value) {
        is Boolean -> SettingType.BOOLEAN to value.toString()
        is Int     -> SettingType.INT to value.toString()
        is Long    -> SettingType.LONG to value.toString()
        is Float   -> SettingType.FLOAT to value.toString()
        is Double  -> SettingType.DOUBLE to value.toString()
        is String  -> SettingType.STRING to value
        is Set<*>  -> SettingType.STRING_SET to JSONArray(value.map { it.toString() }).toString()
        else       -> null
    }

    private fun writeDataStoreEntry(prefs: androidx.datastore.preferences.core.MutablePreferences, entry: SettingBackup) {
        val type = runCatching { SettingType.valueOf(entry.type) }.getOrNull() ?: return
        runCatching {
            when (type) {
                SettingType.BOOLEAN -> prefs[booleanPreferencesKey(entry.key)] = entry.value.toBoolean()
                SettingType.INT     -> prefs[intPreferencesKey(entry.key)] = entry.value.toInt()
                SettingType.LONG    -> prefs[longPreferencesKey(entry.key)] = entry.value.toLong()
                SettingType.FLOAT   -> prefs[floatPreferencesKey(entry.key)] = entry.value.toFloat()
                SettingType.DOUBLE  -> prefs[androidx.datastore.preferences.core.doublePreferencesKey(entry.key)] = entry.value.toDouble()
                SettingType.STRING  -> prefs[stringPreferencesKey(entry.key)] = entry.value
                SettingType.STRING_SET -> prefs[stringSetPreferencesKey(entry.key)] = decodeStringSet(entry.value)
            }
        }
    }

    private fun writeSharedPrefEntry(editor: android.content.SharedPreferences.Editor, entry: SettingBackup) {
        val type = runCatching { SettingType.valueOf(entry.type) }.getOrNull() ?: return
        runCatching {
            when (type) {
                SettingType.BOOLEAN -> editor.putBoolean(entry.key, entry.value.toBoolean())
                SettingType.INT     -> editor.putInt(entry.key, entry.value.toInt())
                SettingType.LONG    -> editor.putLong(entry.key, entry.value.toLong())
                SettingType.FLOAT   -> editor.putFloat(entry.key, entry.value.toFloat())
                SettingType.DOUBLE  -> editor.putFloat(entry.key, entry.value.toFloat())
                SettingType.STRING  -> editor.putString(entry.key, entry.value)
                SettingType.STRING_SET -> editor.putStringSet(entry.key, decodeStringSet(entry.value))
            }
        }
    }

    private fun decodeStringSet(json: String): Set<String> {
        val arr = JSONArray(json)
        return buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
    }

    private companion object {
        // Keys mirror the legacy SharedPreferences keys so the migration is a copy.
        val KEY_LAYER_PARKING    = booleanPreferencesKey("layer_parking_visible")
        val KEY_LAYER_FAVORITES  = booleanPreferencesKey("layer_favorites_visible")
        val KEY_LAYER_SAVED      = booleanPreferencesKey("layer_saved_visible")
        // Legacy keys of the two separate overlays, kept read-only as the migration
        // seed for the unified ride-tracks layer (see RideTracksLayerState).
        val KEY_LAYER_HEATMAP    = booleanPreferencesKey("layer_heatmap_visible")
        val KEY_LAYER_TRACKS     = booleanPreferencesKey("layer_tracks_visible")
        // Unified "My rides" layer: one visibility boolean + a display-mode enum.
        val KEY_LAYER_RIDE_TRACKS = booleanPreferencesKey("layer_ride_tracks_visible")
        val KEY_RIDE_TRACKS_MODE  = stringPreferencesKey("ride_tracks_mode")
        val KEY_NAV_3D           = booleanPreferencesKey("navigation_3d_enabled")
        val KEY_VOICE_GUIDANCE   = booleanPreferencesKey("navigation_tts_enabled")
        val KEY_KEEP_SCREEN_ON   = booleanPreferencesKey("keep_screen_on_enabled")
        val KEY_HUD_ENABLED      = booleanPreferencesKey("hud_enabled")
        val KEY_HUD_EXPANDED     = booleanPreferencesKey("hud_expanded")
        val KEY_PORTRAIT_LOCK    = booleanPreferencesKey("portrait_lock_enabled")
        val KEY_ROUNDED_BUILDINGS = booleanPreferencesKey("rounded_buildings_enabled")
        val KEY_AMOLED           = booleanPreferencesKey("amoled_enabled")
        val KEY_SUN_ALERT        = booleanPreferencesKey("sun_alert_enabled")
        val KEY_WEATHER_ENABLED  = booleanPreferencesKey("weather_enabled")
        val KEY_MAX_SPEED_BUBBLE = booleanPreferencesKey("show_max_speed_bubble")
        val KEY_COLOR_BY_SPEED   = booleanPreferencesKey("color_track_by_speed")
        val KEY_ONBOARDING_DONE  = booleanPreferencesKey("onboarding_completed")
    }
}

