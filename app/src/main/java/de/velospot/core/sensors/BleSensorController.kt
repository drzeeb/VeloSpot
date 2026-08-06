package de.velospot.core.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin Android BLE wrapper that scans for, connects to and subscribes to the
 * standard cycling/fitness sensors ([SensorProfile]), decoding their notifications
 * with the pure [SensorParsers] and exposing the merged live readings as a
 * [StateFlow] of [SensorSnapshot].
 *
 * All the fragile bit-parsing lives in [SensorParsers] (unit-tested); this class
 * only owns the Android plumbing (scanner, GATT connections, CCCD writes) that
 * cannot run on the JVM. Runtime BLUETOOTH_SCAN / BLUETOOTH_CONNECT permissions
 * are the caller's responsibility; every SDK call is additionally guarded so a
 * missing grant degrades to "no readings" rather than crashing.
 */
class BleSensorController(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _snapshot = MutableStateFlow(SensorSnapshot())
    val snapshot: StateFlow<SensorSnapshot> = _snapshot.asStateFlow()

    /** Live wheel circumference (m) for CSC speed; updated by the repository. */
    @Volatile
    var wheelCircumferenceMeters: Double = SensorParsers.DEFAULT_WHEEL_CIRCUMFERENCE_METERS

    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val cscCalculators = ConcurrentHashMap<String, SensorParsers.CscRateCalculator>()
    private val cpsCadence = ConcurrentHashMap<String, SensorParsers.CpsCadenceCalculator>()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun canScan(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)

    private fun canConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    /**
     * Cold flow that scans for sensors advertising any supported profile and emits
     * the growing, de-duplicated list of devices seen. Scanning stops when the
     * collector cancels.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<DiscoveredSensor>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !canScan()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val found = LinkedHashMap<String, DiscoveredSensor>()
        val filters = SensorProfile.entries.map { profile ->
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(uuidFrom16(profile.serviceUuid16)))
                .build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val profiles = result.scanRecord?.serviceUuids
                    ?.mapNotNull { SensorProfile.fromServiceUuid16(it.uuid.short16()) }
                    ?.toSet()
                    .orEmpty()
                if (profiles.isEmpty()) return
                val name = runCatching { device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                found[device.address] = DiscoveredSensor(device.address, name, profiles)
                trySend(found.values.toList())
            }
        }
        scanner.startScan(filters, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /** Connect (and subscribe) to a single sensor by MAC address. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun connect(address: String) {
        if (!canConnect() || gatts.containsKey(address)) return
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        cscCalculators[address] = SensorParsers.CscRateCalculator()
        cpsCadence[address] = SensorParsers.CpsCadenceCalculator()
        val gatt = device.connectGatt(context, /* autoConnect = */ true, gattCallback)
        if (gatt != null) gatts[address] = gatt
    }

    /** Disconnect a single sensor and drop its cached state. */
    @SuppressLint("MissingPermission")
    fun disconnect(address: String) {
        gatts.remove(address)?.let { runCatching { it.disconnect(); it.close() } }
        cscCalculators.remove(address)
        cpsCadence.remove(address)
    }

    /** Disconnect every sensor and clear all live readings. */
    fun disconnectAll() {
        gatts.keys.toList().forEach { disconnect(it) }
        _snapshot.value = SensorSnapshot()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runCatching { gatt.discoverServices() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            SensorProfile.entries.forEach { profile ->
                val service = gatt.getService(uuidFrom16(profile.serviceUuid16)) ?: return@forEach
                val characteristic = service.getCharacteristic(uuidFrom16(profile.measurementUuid16))
                    ?: return@forEach
                enableNotifications(gatt, characteristic)
            }
        }

        // Android 13+ delivers the value directly.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = handleNotification(gatt, characteristic, value)

        // Pre-Android-13 path.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) = handleNotification(gatt, characteristic, characteristic.value ?: ByteArray(0))
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return
        // The value-taking overload is API 33+; the setValue path works on all
        // levels, so use it for broad compatibility.
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        runCatching { gatt.writeDescriptor(cccd) }
    }

    private fun handleNotification(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (value.isEmpty()) return
        val address = gatt.device?.address ?: return
        when (ch.uuid.short16()) {
            SensorProfile.HEART_RATE.measurementUuid16 -> {
                val bpm = runCatching { SensorParsers.parseHeartRate(value) }.getOrNull() ?: return
                _snapshot.update { it.copy(heartRateBpm = bpm) }
            }
            SensorProfile.SPEED_CADENCE.measurementUuid16 -> {
                val m = runCatching { SensorParsers.parseCscMeasurement(value) }.getOrNull() ?: return
                val rates = cscCalculators.getOrPut(address) { SensorParsers.CscRateCalculator() }
                    .update(m, wheelCircumferenceMeters)
                _snapshot.update { snap ->
                    snap.copy(
                        speedMps = rates.speedMps ?: snap.speedMps,
                        cadenceRpm = rates.cadenceRpm ?: snap.cadenceRpm
                    )
                }
            }
            SensorProfile.POWER.measurementUuid16 -> {
                val watts = runCatching { SensorParsers.parseCyclingPowerWatts(value) }.getOrNull()
                val cadence = cpsCadence.getOrPut(address) { SensorParsers.CpsCadenceCalculator() }
                    .update(value)
                _snapshot.update { snap ->
                    snap.copy(
                        powerWatts = watts ?: snap.powerWatts,
                        cadenceRpm = cadence ?: snap.cadenceRpm
                    )
                }
            }
        }
    }

    private fun MutableStateFlow<SensorSnapshot>.update(
        transform: (SensorSnapshot) -> SensorSnapshot
    ) {
        value = transform(value)
    }

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** Build the full 128-bit UUID from a 16-bit assigned number. */
        fun uuidFrom16(uuid16: Int): UUID =
            UUID.fromString("%08x-0000-1000-8000-00805F9B34FB".format(uuid16 and 0xFFFF))

        /** Extract the 16-bit assigned number from a full Bluetooth base UUID. */
        fun UUID.short16(): Int = ((mostSignificantBits shr 32) and 0xFFFF).toInt()
    }
}

