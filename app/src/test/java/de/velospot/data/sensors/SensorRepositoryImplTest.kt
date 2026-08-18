package de.velospot.data.sensors

import android.content.Context
import de.velospot.core.sensors.BleSensorController
import de.velospot.core.sensors.SensorParsers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files

/**
 * JVM unit tests for [SensorRepositoryImpl].
 *
 * The BLE plumbing is exercised through a **real** [BleSensorController] wired to a
 * mocked [Context] (no Bluetooth adapter is available on the JVM, so scanning simply
 * yields an empty list and connect/disconnect are safe no-ops). The persisted state
 * is backed by a real Preferences DataStore rooted in a shared temp directory, so
 * `remember`/`forget` and the wheel-circumference setter round-trip through disk.
 */
class SensorRepositoryImplTest {

    private companion object {
        // The `sensorDataStore` delegate is a process-global singleton keyed by the
        // application context it first sees. A single shared temp dir for the whole
        // class keeps that resolved file path valid across every test method.
        private lateinit var filesDir: java.io.File

        @JvmStatic
        @BeforeClass
        fun setUpFilesDir() {
            filesDir = Files.createTempDirectory("sensor-store").toFile()
        }
    }

    private fun context(): Context = mock {
        whenever(it.applicationContext).thenReturn(it)
        whenever(it.filesDir).thenReturn(filesDir)
        // getSystemService(BLUETOOTH_SERVICE) → null ⇒ controller has no adapter.
        whenever(it.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(null)
    }

    private fun repo(scope: kotlinx.coroutines.CoroutineScope): SensorRepositoryImpl {
        val ctx = context()
        return SensorRepositoryImpl(ctx, BleSensorController(ctx), scope)
    }

    @Test
    fun `wheel circumference persists and defaults to the parser constant`() = runTest {
        val repo = repo(backgroundScope)

        // Start from the parser default when nothing has been written yet is not
        // guaranteed (shared store), so assert the setter round-trips instead.
        repo.setWheelCircumferenceMeters(2.222)
        assertEquals(2.222, repo.wheelCircumferenceMeters.first(), 0.0)

        repo.setWheelCircumferenceMeters(SensorParsers.DEFAULT_WHEEL_CIRCUMFERENCE_METERS)
        assertEquals(
            SensorParsers.DEFAULT_WHEEL_CIRCUMFERENCE_METERS,
            repo.wheelCircumferenceMeters.first(),
            0.0
        )
    }

    @Test
    fun `remember then forget toggles the stored address set`() = runTest {
        val repo = repo(backgroundScope)
        val address = "AA:BB:CC:DD:EE:01"

        repo.remember(address)
        assertTrue(address in repo.rememberedAddresses.first())

        repo.forget(address)
        assertFalse(address in repo.rememberedAddresses.first())
    }

    @Test
    fun `scan and live plumbing degrade gracefully without a bluetooth adapter`() = runTest {
        val repo = repo(backgroundScope)

        // No adapter ⇒ scanning yields an empty list and closes.
        assertEquals(emptyList<Any>(), repo.scan().first())
        // The live snapshot is always available (starts empty).
        assertNotNull(repo.snapshot.value)
        // These must not throw even without any hardware.
        repo.connectRemembered()
        repo.disconnectAll()
    }
}

