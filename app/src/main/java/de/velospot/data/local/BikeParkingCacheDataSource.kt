package de.velospot.data.local

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.domain.model.BikeParkingSpace
import javax.inject.Inject

class BikeParkingCacheDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi
) {

    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    private val listType = Types.newParameterizedType(List::class.java, BikeParkingSpace::class.java)
    private val adapter = moshi.adapter<List<BikeParkingSpace>>(listType)

    fun readSpaces(): List<BikeParkingSpace> {
        val json = prefs.getString(KEY_SPACES_JSON, null) ?: return emptyList()
        return runCatching { adapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
    }

    fun writeSpaces(spaces: List<BikeParkingSpace>) {
        val json = adapter.toJson(spaces)
        prefs.edit()
            .putString(KEY_SPACES_JSON, json)
            .putLong(KEY_LAST_SYNC_EPOCH_MS, System.currentTimeMillis())
            .apply()
    }

    fun lastSyncEpochMs(): Long = prefs.getLong(KEY_LAST_SYNC_EPOCH_MS, 0L)

    private companion object {
        const val PREF_FILE = "bike_parking_cache"
        const val KEY_SPACES_JSON = "spaces_json"
        const val KEY_LAST_SYNC_EPOCH_MS = "last_sync_epoch_ms"
    }
}

