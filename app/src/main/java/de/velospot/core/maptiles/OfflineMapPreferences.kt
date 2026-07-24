package de.velospot.core.maptiles

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "velospot_offline_map"
private const val KEY_HAS_MAP = "offline_map_present"

/**
 * Tiny persisted flag for the **offline map tiles** feature, mirroring
 * [de.velospot.core.routing.OfflineRoutingPreferences]. Only tracks whether the user
 * has downloaded an offline map region, so the initial UI state can be resolved
 * synchronously without touching MapLibre's `OfflineManager` at construction time
 * (which requires MapLibre to be initialised first). The actual tiles live in
 * MapLibre's own store, managed by `OfflineMapTilesManager`.
 */
object OfflineMapPreferences {

    fun hasOfflineMap(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAS_MAP, false)

    fun setHasOfflineMap(context: Context, present: Boolean) =
        prefs(context).edit { putBoolean(KEY_HAS_MAP, present) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

