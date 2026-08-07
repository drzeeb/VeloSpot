package de.velospot.core.sensors

/**
 * The standard Bluetooth-LE cycling/fitness sensor profiles VeloSpot can read.
 *
 * These are the **open, vendor-agnostic** GATT profiles — unlike the proprietary,
 * closed protocols e-bike drive systems (Bosch, Shimano STEPS, Specialized/Brose,
 * …) use for their motor/battery telemetry, which cannot be read by third-party
 * apps. So VeloSpot reads generic speed/cadence/power/heart-rate sensors, not the
 * e-bike computer itself.
 *
 * @property serviceUuid16 the 16-bit assigned service UUID (used for scan filters
 *  and service discovery).
 * @property measurementUuid16 the 16-bit measurement characteristic UUID that the
 *  sensor notifies on.
 */
enum class SensorProfile(val serviceUuid16: Int, val measurementUuid16: Int) {
    /** Cycling Speed and Cadence (CSC): wheel speed and/or crank cadence. */
    SPEED_CADENCE(serviceUuid16 = 0x1816, measurementUuid16 = 0x2A5B),

    /** Cycling Power (CPS): instantaneous power in watts (and optionally cadence). */
    POWER(serviceUuid16 = 0x1818, measurementUuid16 = 0x2A63),

    /** Heart Rate (HRS): beats per minute. */
    HEART_RATE(serviceUuid16 = 0x180D, measurementUuid16 = 0x2A37);

    companion object {
        /** The profile whose service matches [serviceUuid16], or `null`. */
        fun fromServiceUuid16(serviceUuid16: Int): SensorProfile? =
            entries.firstOrNull { it.serviceUuid16 == serviceUuid16 }
    }
}

/**
 * A sensor found while scanning, offered to the user for pairing.
 *
 * @property address the device's Bluetooth MAC (stable identifier we persist to
 *  auto-reconnect on the next ride).
 * @property name the advertised device name, or `null` when the peripheral does
 *  not advertise one.
 * @property profiles the standard profiles the device advertised support for.
 */
data class DiscoveredSensor(
    val address: String,
    val name: String?,
    val profiles: Set<SensorProfile>
)

/** Connection lifecycle of a single remembered sensor. */
enum class SensorConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * The merged, latest live readings across **all** connected sensors. Any field is
 * `null` until a sensor that supplies it delivers its first notification, so the
 * UI can show a metric only when it is actually available.
 *
 * @property speedMps ground speed from a CSC wheel sensor (metres per second).
 * @property cadenceRpm crank cadence (revolutions per minute) from CSC or CPS.
 * @property powerWatts instantaneous power from a CPS power meter.
 * @property heartRateBpm heart rate (beats per minute) from an HRS strap.
 */
data class SensorSnapshot(
    val speedMps: Float? = null,
    val cadenceRpm: Int? = null,
    val powerWatts: Int? = null,
    val heartRateBpm: Int? = null
) {
    /** `true` when at least one metric is present (i.e. a sensor is live). */
    val hasAnyReading: Boolean
        get() = speedMps != null || cadenceRpm != null ||
            powerWatts != null || heartRateBpm != null
}

