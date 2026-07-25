package de.velospot.core.offline

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "velospot_offline_regions"
private const val KEY_REGIONS = "regions"

/**
 * Tiny persisted list of downloaded [OfflineRegionPack]s — the **source of truth**
 * for the offline-regions manager UI. The actual bytes live in MapLibre's offline
 * store (map tiles) and the BRouter segments folder (routing); this only records
 * *which* regions the rider has, so the list can be shown and each one deleted.
 *
 * Persisted as a small JSON array in `SharedPreferences` (via `org.json`, which is
 * part of the Android platform — no extra dependency and no Moshi adapter needed).
 * Reads are synchronous and cheap (a handful of entries), so the UI can render the
 * list without an async round-trip.
 */
class OfflineRegionsStore(private val context: Context) {

    fun list(): List<OfflineRegionPack> {
        val raw = prefs().getString(KEY_REGIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                OfflineRegionPack(
                    id        = obj.getString("id"),
                    label     = obj.getString("label"),
                    latitude  = obj.getDouble("lat"),
                    longitude = obj.getDouble("lon"),
                    createdAt = obj.getLong("createdAt"),
                )
            }
        }.getOrDefault(emptyList())
            .sortedBy { it.createdAt }
    }

    fun add(pack: OfflineRegionPack) {
        // Replace any existing entry with the same id (idempotent re-add).
        val next = list().filterNot { it.id == pack.id } + pack
        save(next)
    }

    fun remove(id: String) = save(list().filterNot { it.id == id })

    fun clear() = prefs().edit { remove(KEY_REGIONS) }

    private fun save(packs: List<OfflineRegionPack>) {
        val array = JSONArray()
        packs.forEach { pack ->
            array.put(
                JSONObject()
                    .put("id", pack.id)
                    .put("label", pack.label)
                    .put("lat", pack.latitude)
                    .put("lon", pack.longitude)
                    .put("createdAt", pack.createdAt)
            )
        }
        prefs().edit { putString(KEY_REGIONS, array.toString()) }
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

