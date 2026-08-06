package de.velospot.domain.repository

import de.velospot.core.sensors.DiscoveredSensor
import de.velospot.core.sensors.SensorSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Access to external **Bluetooth-LE** cycling/fitness sensors (speed, cadence,
 * power, heart-rate) using the open, vendor-agnostic GATT profiles. Proprietary
 * e-bike drive telemetry (battery, assist level, motor power) is intentionally
 * out of scope — no open standard exposes it.
 */
interface SensorRepository {

    /** The merged latest readings across all connected sensors (`null` fields until live). */
    val snapshot: StateFlow<SensorSnapshot>

    /** Persisted MAC addresses of the sensors to auto-connect to on a ride. */
    val rememberedAddresses: Flow<Set<String>>

    /** Wheel circumference (metres) used to derive speed from a CSC wheel sensor. */
    val wheelCircumferenceMeters: Flow<Double>

    /**
     * Scan for nearby sensors advertising a supported profile. Cold flow: scanning
     * starts on collection and stops on cancellation. Emits the growing set of
     * distinct devices seen so far.
     */
    fun scan(): Flow<List<DiscoveredSensor>>

    /** Persist [address] and connect to it (now and on future rides). */
    suspend fun remember(address: String)

    /** Forget [address], disconnecting it. */
    suspend fun forget(address: String)

    /** Connect to all remembered sensors (called when a ride/session starts). */
    fun connectRemembered()

    /** Disconnect everything (called when the session ends). */
    fun disconnectAll()

    /** Persist the wheel circumference (metres) for CSC speed. */
    suspend fun setWheelCircumferenceMeters(meters: Double)
}

