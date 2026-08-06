package de.velospot.core.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure BLE measurement parsers and the CSC/CPS rate maths —
 * the endianness, flag-layout and counter-wraparound logic that is easy to get
 * subtly wrong.
 */
class SensorParsersTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** Little-endian uint16 as two ints. */
    private fun le16(value: Int): IntArray = intArrayOf(value and 0xFF, (value shr 8) and 0xFF)
    private fun le32(value: Long): IntArray = intArrayOf(
        (value and 0xFF).toInt(),
        ((value shr 8) and 0xFF).toInt(),
        ((value shr 16) and 0xFF).toInt(),
        ((value shr 24) and 0xFF).toInt()
    )

    private fun csc(wheelRevs: Long?, wheelTime: Int?, crankRevs: Int?, crankTime: Int?): ByteArray {
        var flags = 0
        val body = ArrayList<Int>()
        if (wheelRevs != null && wheelTime != null) {
            flags = flags or 0x01
            body += le32(wheelRevs).toList()
            body += le16(wheelTime).toList()
        }
        if (crankRevs != null && crankTime != null) {
            flags = flags or 0x02
            body += le16(crankRevs).toList()
            body += le16(crankTime).toList()
        }
        return bytes(flags, *body.toIntArray())
    }

    // ── Heart rate ──────────────────────────────────────────────────────────────

    @Test fun `heart rate 8-bit`() {
        assertEquals(72, SensorParsers.parseHeartRate(bytes(0x00, 72)))
    }

    @Test fun `heart rate 16-bit`() {
        // Flags bit0 set → 16-bit value 300 (0x012C) little-endian.
        assertEquals(300, SensorParsers.parseHeartRate(bytes(0x01, 0x2C, 0x01)))
    }

    // ── Cycling power ─────────────────────────────────────────────────────────

    @Test fun `cycling power positive watts`() {
        // flags(2)=0, power=250 (0x00FA) LE.
        assertEquals(250, SensorParsers.parseCyclingPowerWatts(bytes(0x00, 0x00, 0xFA, 0x00)))
    }

    @Test fun `cycling power negative watts (int16)`() {
        // power = -10 → 0xFFF6 LE.
        assertEquals(-10, SensorParsers.parseCyclingPowerWatts(bytes(0x00, 0x00, 0xF6, 0xFF)))
    }

    // ── CSC field parsing ─────────────────────────────────────────────────────

    @Test fun `csc parses wheel and crank fields`() {
        val m = SensorParsers.parseCscMeasurement(csc(wheelRevs = 1000L, wheelTime = 2048, crankRevs = 50, crankTime = 1024))
        assertEquals(1000L, m.cumulativeWheelRevolutions)
        assertEquals(2048, m.lastWheelEventTime)
        assertEquals(50, m.cumulativeCrankRevolutions)
        assertEquals(1024, m.lastCrankEventTime)
    }

    @Test fun `csc parses wheel only`() {
        val m = SensorParsers.parseCscMeasurement(csc(wheelRevs = 7L, wheelTime = 100, crankRevs = null, crankTime = null))
        assertEquals(7L, m.cumulativeWheelRevolutions)
        assertNull(m.cumulativeCrankRevolutions)
    }

    // ── CSC rate maths ────────────────────────────────────────────────────────

    @Test fun `first csc sample yields no rates`() {
        val calc = SensorParsers.CscRateCalculator()
        val r = calc.update(SensorParsers.parseCscMeasurement(csc(100, 1024, null, null)), 2.0)
        assertNull(r.speedMps)
    }

    @Test fun `steady csc speed`() {
        val calc = SensorParsers.CscRateCalculator()
        calc.update(SensorParsers.parseCscMeasurement(csc(100, 1024, null, null)), 2.0)
        // +5 revolutions over exactly 1.0 s, 2.0 m circumference → 10 m/s.
        val r = calc.update(SensorParsers.parseCscMeasurement(csc(105, 2048, null, null)), 2.0)
        assertEquals(10.0f, r.speedMps!!, 0.001f)
    }

    @Test fun `csc wheel-time uint16 wraparound`() {
        val calc = SensorParsers.CscRateCalculator()
        calc.update(SensorParsers.parseCscMeasurement(csc(100, 65024, null, null)), 2.0)
        // Time wraps 65024 → 512 (Δ = 1024 ticks = 1.0 s); +5 rev → 10 m/s.
        val r = calc.update(SensorParsers.parseCscMeasurement(csc(105, 512, null, null)), 2.0)
        assertEquals(10.0f, r.speedMps!!, 0.001f)
    }

    @Test fun `csc wheel-revolution uint32 wraparound`() {
        val calc = SensorParsers.CscRateCalculator()
        calc.update(SensorParsers.parseCscMeasurement(csc(0xFFFFFFFEL, 1024, null, null)), 2.0)
        // Revs wrap 0xFFFFFFFE → 3 (Δ = 5) over 1.0 s → 10 m/s.
        val r = calc.update(SensorParsers.parseCscMeasurement(csc(3, 2048, null, null)), 2.0)
        assertEquals(10.0f, r.speedMps!!, 0.001f)
    }

    @Test fun `csc no movement reads zero`() {
        val calc = SensorParsers.CscRateCalculator()
        calc.update(SensorParsers.parseCscMeasurement(csc(100, 1024, null, null)), 2.0)
        // Same counters resent (Δtime = 0) → speed 0, not a spike.
        val r = calc.update(SensorParsers.parseCscMeasurement(csc(100, 1024, null, null)), 2.0)
        assertEquals(0.0f, r.speedMps!!, 0.001f)
    }

    @Test fun `csc cadence rpm`() {
        val calc = SensorParsers.CscRateCalculator()
        calc.update(SensorParsers.parseCscMeasurement(csc(0, 0, 100, 1024)), 2.0)
        // +2 crank revs over 1.0 s → 120 rpm.
        val r = calc.update(SensorParsers.parseCscMeasurement(csc(0, 0, 102, 2048)), 2.0)
        assertEquals(120, r.cadenceRpm)
    }

    // ── CPS cadence ───────────────────────────────────────────────────────────

    @Test fun `cps cadence absent when flag unset`() {
        val calc = SensorParsers.CpsCadenceCalculator()
        // flags=0 (no crank data): power-only frame.
        assertNull(calc.update(bytes(0x00, 0x00, 0xFA, 0x00)))
    }

    @Test fun `cps cadence computed`() {
        val calc = SensorParsers.CpsCadenceCalculator()
        // flags=0x0020 (crank data present), power 250, crankRevs + time.
        fun frame(revs: Int, time: Int) = bytes(0x20, 0x00, 0xFA, 0x00, *le16(revs), *le16(time))
        calc.update(frame(10, 1024))
        // +2 crank revs over 1.0 s → 120 rpm.
        assertEquals(120, calc.update(frame(12, 2048)))
    }
}

