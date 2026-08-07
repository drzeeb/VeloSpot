package de.velospot.core.sensors

/**
 * Pure, Android-free parsers for the standard BLE cycling/fitness measurement
 * characteristics, plus the small amount of stateful maths needed to turn a CSC
 * sensor's **cumulative counters** into an instantaneous speed and cadence.
 *
 * Kept dependency-free (only [ByteArray] in, plain values out) so the whole
 * bit-twiddling layer — the part most likely to have off-by-one / endianness /
 * wraparound bugs — is exhaustively covered by fast JVM unit tests, exactly like
 * [de.velospot.core.navigation.GeoMath].
 *
 * All multi-byte fields in these characteristics are **little-endian**.
 */
object SensorParsers {

    // ── Unsigned little-endian readers ──────────────────────────────────────────
    private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
    private fun ByteArray.u32(i: Int): Long =
        (u8(i).toLong()) or (u8(i + 1).toLong() shl 8) or
            (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

    private const val WHEEL_PRESENT = 0x01
    private const val CRANK_PRESENT = 0x02
    private const val UINT16_ROLLOVER = 0x10000L
    private const val UINT32_ROLLOVER = 0x1_0000_0000L
    /** CSC/CPS event times are counted in 1/1024-second ticks. */
    private const val EVENT_TIME_HZ = 1024.0

    /**
     * Raw fields decoded from a **CSC Measurement** (`0x2A5B`) notification. Fields
     * are `null` when the corresponding flag bit was not set.
     */
    data class CscMeasurement(
        val cumulativeWheelRevolutions: Long? = null,
        val lastWheelEventTime: Int? = null,
        val cumulativeCrankRevolutions: Int? = null,
        val lastCrankEventTime: Int? = null
    )

    /** Decode a CSC Measurement (`0x2A5B`). */
    fun parseCscMeasurement(bytes: ByteArray): CscMeasurement {
        require(bytes.isNotEmpty()) { "empty CSC measurement" }
        val flags = bytes.u8(0)
        var offset = 1
        var wheelRevs: Long? = null
        var wheelTime: Int? = null
        var crankRevs: Int? = null
        var crankTime: Int? = null
        if (flags and WHEEL_PRESENT != 0) {
            wheelRevs = bytes.u32(offset); offset += 4
            wheelTime = bytes.u16(offset); offset += 2
        }
        if (flags and CRANK_PRESENT != 0) {
            crankRevs = bytes.u16(offset); offset += 2
            crankTime = bytes.u16(offset); offset += 2
        }
        return CscMeasurement(wheelRevs, wheelTime, crankRevs, crankTime)
    }

    /** Instantaneous power in watts from a **Cycling Power Measurement** (`0x2A63`). */
    fun parseCyclingPowerWatts(bytes: ByteArray): Int {
        require(bytes.size >= 4) { "short cycling-power measurement" }
        // Bytes 0..1 are flags; bytes 2..3 are the signed instantaneous power (W).
        val raw = bytes.u16(2)
        return if (raw >= 0x8000) raw - 0x10000 else raw   // interpret as int16
    }

    /** Heart rate in bpm from a **Heart Rate Measurement** (`0x2A37`). */
    fun parseHeartRate(bytes: ByteArray): Int {
        require(bytes.size >= 2) { "short heart-rate measurement" }
        val flags = bytes.u8(0)
        // Bit0 of the flags selects an 8-bit (0) or 16-bit (1) value.
        return if (flags and 0x01 == 0) bytes.u8(1) else bytes.u16(1)
    }

    /**
     * Turns the CSC sensor's ever-growing counters into an instantaneous speed and
     * cadence by differencing **consecutive** measurements, correctly handling the
     * `uint16` event-time and `uint32` revolution wraparounds.
     *
     * Not thread-safe — feed it from a single coroutine. Returns `null` metrics
     * until it has two samples, when the wheel/crank did not tick between samples
     * (Δtime = 0), or when the sensor reports no movement (Δrevolutions = 0 →
     * speed/cadence 0).
     */
    class CscRateCalculator {
        private var prevWheelRevs: Long? = null
        private var prevWheelTime: Int? = null
        private var prevCrankRevs: Int? = null
        private var prevCrankTime: Int? = null

        data class Rates(val speedMps: Float?, val cadenceRpm: Int?)

        fun update(m: CscMeasurement, wheelCircumferenceMeters: Double): Rates {
            val speed = m.cumulativeWheelRevolutions?.let { revs ->
                val time = m.lastWheelEventTime!!
                val pRevs = prevWheelRevs
                val pTime = prevWheelTime
                prevWheelRevs = revs
                prevWheelTime = time
                if (pRevs == null || pTime == null) return@let null
                val dRev = ((revs - pRevs) + UINT32_ROLLOVER) % UINT32_ROLLOVER
                val dtSec = (((time - pTime) + UINT16_ROLLOVER) % UINT16_ROLLOVER) / EVENT_TIME_HZ
                if (dtSec <= 0.0) 0f else (dRev * wheelCircumferenceMeters / dtSec).toFloat()
            }
            val cadence = m.cumulativeCrankRevolutions?.let { revs ->
                val time = m.lastCrankEventTime!!
                val pRevs = prevCrankRevs
                val pTime = prevCrankTime
                prevCrankRevs = revs
                prevCrankTime = time
                if (pRevs == null || pTime == null) return@let null
                val dRev = ((revs - pRevs) + UINT16_ROLLOVER) % UINT16_ROLLOVER
                val dtSec = (((time - pTime) + UINT16_ROLLOVER) % UINT16_ROLLOVER) / EVENT_TIME_HZ
                if (dtSec <= 0.0) 0 else (dRev / dtSec * 60.0).toInt()
            }
            return Rates(speed, cadence)
        }
    }

    /** Cadence in rpm from a **Cycling Power Measurement** when it carries crank data. */
    class CpsCadenceCalculator {
        private var prevRevs: Int? = null
        private var prevTime: Int? = null

        /**
         * @param bytes the raw CPS measurement. Returns `null` when the optional
         *  "crank revolution data present" flag (bit 5) is not set or on the first
         *  sample.
         */
        fun update(bytes: ByteArray): Int? {
            if (bytes.size < 4) return null
            val flags = bytes.u16(0)
            if (flags and 0x20 == 0) return null   // bit5: crank revolution data present
            // Layout: flags(2) power(2) [pedal balance(1)] [accumulated torque(2)]
            // [wheel rev(4)+time(2)] then crank rev(2)+time(2). Compute the crank
            // fields' offset from the preceding optional fields.
            var offset = 4
            if (flags and 0x01 != 0) offset += 1            // pedal power balance
            if (flags and 0x04 != 0) offset += 2            // accumulated torque
            if (flags and 0x10 != 0) offset += 6            // wheel revolution data
            if (bytes.size < offset + 4) return null
            val revs = bytes.u16(offset)
            val time = bytes.u16(offset + 2)
            val pRevs = prevRevs
            val pTime = prevTime
            prevRevs = revs
            prevTime = time
            if (pRevs == null || pTime == null) return null
            val dRev = ((revs - pRevs) + UINT16_ROLLOVER) % UINT16_ROLLOVER
            val dtSec = (((time - pTime) + UINT16_ROLLOVER) % UINT16_ROLLOVER) / EVENT_TIME_HZ
            return if (dtSec <= 0.0) 0 else (dRev / dtSec * 60.0).toInt()
        }
    }

    /** Default road-bike wheel circumference (700×25c) in metres, for CSC speed. */
    const val DEFAULT_WHEEL_CIRCUMFERENCE_METERS = 2.105
}

