package de.velospot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import de.velospot.core.locale.LanguagePreferences
import de.velospot.core.theme.DarkModePreferences
import de.velospot.ui.navigation.VeloSpotNavHost
import de.velospot.ui.theme.VeloSpotTheme

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    /**
     * Set true when the Activity was launched to auto-start a ride recording
     * (see [ACTION_START_RIDE_RECORDING] / [EXTRA_START_RIDE_RECORDING]). Backing a
     * Compose state so the map screen fires the very same start the in-app FAB uses
     * from a guaranteed-foreground Activity context — the widget/tile route through
     * here so the location foreground service is always allowed to start, even on a
     * cold start and on OEMs that block background FGS starts (Android 12+/ColorOS).
     */
    private val startRideRecordingRequest: MutableState<Boolean> = mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        // Re-apply user-saved language for every Activity recreation.
        super.attachBaseContext(LanguagePreferences.wrap(newBase))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Warm start: make the new intent the Activity's current intent, then latch
        // the auto-start request so the map screen reacts.
        setIntent(intent)
        if (wantsStartRideRecording(intent)) {
            consumeStartRideRecordingExtras(intent)
            startRideRecordingRequest.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cold start: honour an auto-start request carried by the launch intent.
        // Consume the marker so a rotation/recreation (which replays this intent)
        // cannot re-trigger a second recording.
        if (savedInstanceState == null && wantsStartRideRecording(intent)) {
            consumeStartRideRecordingExtras(intent)
            startRideRecordingRequest.value = true
        }
        // Draw behind the system bars (Android 15+ / SDK 35+ default). We request
        // fully transparent status and navigation bars so the map and Compose UI
        // extend edge-to-edge; the bar *icon* contrast is then driven from the
        // app's own dark-mode state below (via WindowInsetsControllerCompat, the
        // non-deprecated replacement for Window.setStatusBarColor / setNavigationBarColor).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)
        )
        setContent {
            var darkThemeEnabled by remember {
                mutableStateOf(DarkModePreferences.isDarkModeEnabled(this))
            }

            // Keep the system-bar icon appearance in sync with the *app's* theme,
            // not the system's. VeloSpot has its own in-app dark-mode toggle that is
            // independent of the OS setting, so relying on enableEdgeToEdge()'s
            // system-driven default could render invisible (e.g. light-on-light)
            // status/navigation bar icons in edge-to-edge mode. Light theme → dark
            // icons; dark theme → light icons.
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !darkThemeEnabled
                    controller.isAppearanceLightNavigationBars = !darkThemeEnabled
                }
            }

            VeloSpotTheme(darkTheme = darkThemeEnabled) {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    VeloSpotNavHost(
                        isDarkTheme = darkThemeEnabled,
                        onDarkThemeToggle = {
                            darkThemeEnabled = !darkThemeEnabled
                            DarkModePreferences.setDarkModeEnabled(this, darkThemeEnabled)
                        },
                        autoStartRideRecording = startRideRecordingRequest.value,
                        onAutoStartRideRecordingConsumed = {
                            startRideRecordingRequest.value = false
                        }
                    )
                }
            }
        }
    }

    companion object {
        /**
         * Action requesting that the map screen auto-starts a ride recording as soon
         * as it is shown. Launched by the home-screen widget and the Quick Settings
         * tile so the recording — and its location foreground service — begins from a
         * foreground Activity, which every OEM allows, instead of a background
         * foreground-service start that Android 12+/ColorOS block.
         */
        const val ACTION_START_RIDE_RECORDING = "de.velospot.action.START_RIDE_RECORDING"

        /** Boolean fallback marker carried alongside [ACTION_START_RIDE_RECORDING]. */
        const val EXTRA_START_RIDE_RECORDING = "de.velospot.extra.START_RIDE_RECORDING"

        /**
         * Builds the intent the widget/tile fire to open the app and start a
         * recording from a guaranteed-foreground context. [Intent.FLAG_ACTIVITY_NEW_TASK]
         * is required to launch an Activity from a non-Activity (broadcast/service)
         * context.
         */
        fun startRideRecordingIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_START_RIDE_RECORDING)
                .putExtra(EXTRA_START_RIDE_RECORDING, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** True when [intent] asks the Activity to auto-start a ride recording. */
        fun wantsStartRideRecording(intent: Intent?): Boolean {
            if (intent == null) return false
            return intent.action == ACTION_START_RIDE_RECORDING ||
                intent.getBooleanExtra(EXTRA_START_RIDE_RECORDING, false)
        }
    }

    /**
     * Clears the auto-start markers from [intent] so the request is honoured exactly
     * once and cannot replay when the same intent is redelivered (e.g. on rotation).
     */
    private fun consumeStartRideRecordingExtras(intent: Intent) {
        intent.removeExtra(EXTRA_START_RIDE_RECORDING)
        if (intent.action == ACTION_START_RIDE_RECORDING) intent.action = Intent.ACTION_MAIN
    }
}
