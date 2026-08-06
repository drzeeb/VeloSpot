package de.velospot

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Unit coverage for [MainActivity.wantsStartRideRecording] — the pure decision that
 * recognises the widget/tile "start a recording" launch intent. Routing the start
 * through a foreground Activity (instead of a background foreground-service start) is
 * what makes widget/tile starts reliable on Android 12+/ColorOS from a cold app.
 *
 * These are plain-JVM tests: there is no Robolectric on the classpath, so real
 * `Intent` construction is unavailable. `Intent` is therefore a Mockito mock whose
 * `action` / `getBooleanExtra` are stubbed — exactly the two members the decision reads.
 */
class MainActivityStartIntentTest {

    @Test
    fun `null intent does not request start`() {
        assertFalse(MainActivity.wantsStartRideRecording(null))
    }

    @Test
    fun `matching action requests start`() {
        val intent = mock<Intent> {
            on { action } doReturn MainActivity.ACTION_START_RIDE_RECORDING
            on { getBooleanExtra(MainActivity.EXTRA_START_RIDE_RECORDING, false) } doReturn false
        }
        assertTrue(MainActivity.wantsStartRideRecording(intent))
    }

    @Test
    fun `boolean extra requests start even without the action`() {
        val intent = mock<Intent> {
            on { action } doReturn Intent.ACTION_MAIN
            on { getBooleanExtra(MainActivity.EXTRA_START_RIDE_RECORDING, false) } doReturn true
        }
        assertTrue(MainActivity.wantsStartRideRecording(intent))
    }

    @Test
    fun `plain launch intent does not request start`() {
        val intent = mock<Intent> {
            on { action } doReturn Intent.ACTION_MAIN
            on { getBooleanExtra(MainActivity.EXTRA_START_RIDE_RECORDING, false) } doReturn false
        }
        assertFalse(MainActivity.wantsStartRideRecording(intent))
    }
}

