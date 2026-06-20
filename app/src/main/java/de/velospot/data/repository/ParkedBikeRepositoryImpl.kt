package de.velospot.data.repository

import android.content.Context
import androidx.core.content.edit
import de.velospot.domain.model.ParkedBike
import de.velospot.domain.repository.ParkedBikeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SharedPreferences]-backed implementation of [ParkedBikeRepository].
 *
 * The parked bike is a single record, so a full Room database would be overkill.
 * The value is held in a private preference file and mirrored into a
 * [MutableStateFlow] so the UI updates reactively (mirrors the lightweight
 * approach used by the navigation-mode preference).
 */
@Singleton
class ParkedBikeRepositoryImpl @Inject constructor(
    context: Context
) : ParkedBikeRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _parkedBike = MutableStateFlow(readFromPrefs())

    override fun getParkedBikeFlow(): Flow<ParkedBike?> = _parkedBike.asStateFlow()

    override suspend fun park(bike: ParkedBike) {
        prefs.edit {
            putBoolean(KEY_HAS_BIKE, true)
            putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(bike.latitude))
            putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(bike.longitude))
            putLong(KEY_PARKED_AT, bike.parkedAt)
            putString(KEY_NOTE, bike.note)
            putString(KEY_ADDRESS, bike.address)
        }
        _parkedBike.value = bike
    }

    override suspend fun clear() {
        prefs.edit { clear() }
        _parkedBike.value = null
    }

    private fun readFromPrefs(): ParkedBike? {
        if (!prefs.getBoolean(KEY_HAS_BIKE, false)) return null
        return ParkedBike(
            latitude  = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0L)),
            longitude = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LON, 0L)),
            parkedAt  = prefs.getLong(KEY_PARKED_AT, 0L),
            note      = prefs.getString(KEY_NOTE, null),
            address   = prefs.getString(KEY_ADDRESS, null)
        )
    }

    private companion object {
        const val PREFS_NAME  = "velospot_parked_bike"
        const val KEY_HAS_BIKE = "has_bike"
        const val KEY_LAT      = "latitude"
        const val KEY_LON      = "longitude"
        const val KEY_PARKED_AT = "parked_at"
        const val KEY_NOTE     = "note"
        const val KEY_ADDRESS  = "address"
    }
}

