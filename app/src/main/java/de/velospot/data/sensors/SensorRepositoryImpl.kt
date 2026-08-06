package de.velospot.data.sensors

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.velospot.core.sensors.BleSensorController
import de.velospot.core.sensors.DiscoveredSensor
import de.velospot.core.sensors.SensorParsers
import de.velospot.core.sensors.SensorSnapshot
import de.velospot.domain.repository.SensorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.sensorDataStore: DataStore<Preferences> by preferencesDataStore(name = "sensors")

/**
 * [SensorRepository] backed by a [BleSensorController] for the live BLE plumbing
 * and a small Preferences [DataStore] for the persisted state (remembered sensor
 * MAC addresses and the wheel circumference used for CSC speed).
 *
 * Keeps the controller's live wheel circumference in sync with the stored value so
 * a setting change immediately affects speed derivation.
 */
class SensorRepositoryImpl(
    private val context: Context,
    private val controller: BleSensorController,
    private val scope: CoroutineScope
) : SensorRepository {

    init {
        // Mirror the stored wheel circumference into the controller.
        scope.launch {
            wheelCircumferenceMeters.collect { controller.wheelCircumferenceMeters = it }
        }
    }

    override val snapshot: StateFlow<SensorSnapshot> = controller.snapshot

    override val rememberedAddresses: Flow<Set<String>> =
        context.sensorDataStore.data.map { it[KEY_ADDRESSES] ?: emptySet() }

    override val wheelCircumferenceMeters: Flow<Double> =
        context.sensorDataStore.data.map {
            it[KEY_WHEEL_CIRCUMFERENCE] ?: SensorParsers.DEFAULT_WHEEL_CIRCUMFERENCE_METERS
        }

    override fun scan(): Flow<List<DiscoveredSensor>> = controller.scan()

    override suspend fun remember(address: String) {
        context.sensorDataStore.edit { prefs ->
            prefs[KEY_ADDRESSES] = (prefs[KEY_ADDRESSES] ?: emptySet()) + address
        }
        controller.connect(address)
    }

    override suspend fun forget(address: String) {
        context.sensorDataStore.edit { prefs ->
            prefs[KEY_ADDRESSES] = (prefs[KEY_ADDRESSES] ?: emptySet()) - address
        }
        controller.disconnect(address)
    }

    override fun connectRemembered() {
        scope.launch {
            rememberedAddresses.first().forEach { controller.connect(it) }
        }
    }

    override fun disconnectAll() = controller.disconnectAll()

    override suspend fun setWheelCircumferenceMeters(meters: Double) {
        context.sensorDataStore.edit { it[KEY_WHEEL_CIRCUMFERENCE] = meters }
    }

    private companion object {
        val KEY_ADDRESSES = stringSetPreferencesKey("remembered_sensor_addresses")
        val KEY_WHEEL_CIRCUMFERENCE = doublePreferencesKey("wheel_circumference_meters")
    }
}

