package de.velospot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.net.Uri
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
import de.velospot.core.gpx.GpxOpenBus
import de.velospot.core.locale.LanguagePreferences
import de.velospot.core.theme.DarkModePreferences
import de.velospot.feature.wrapped.presentation.WrappedOpenBus
import de.velospot.ui.navigation.VeloSpotNavHost
import de.velospot.ui.theme.VeloSpotTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    /** Hand-off for a `.gpx` file opened from another app via an `ACTION_VIEW` intent. */
    @Inject lateinit var gpxOpenBus: GpxOpenBus

    /** Hand-off for a "VeloSpot Wrapped" report opened from its ready notification. */
    @Inject lateinit var wrappedOpenBus: WrappedOpenBus


    override fun attachBaseContext(newBase: Context) {
        // Re-apply user-saved language for every Activity recreation.
        super.attachBaseContext(LanguagePreferences.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Route a `.gpx` opened from outside the app (file manager, e-mail, browser
        // download, share sheet, …) into the map flow so the import-or-preview
        // chooser appears (handled for both cold start and while already running).
        handleGpxViewIntent(intent)
        // Route a tapped "your Wrapped is ready" notification to the report's Story.
        handleWrappedOpenIntent(intent)
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
                        }
                    )
                }
            }
        }
    }

    /** Handles a `.gpx` opened while the Activity is already running (singleTask reuse). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGpxViewIntent(intent)
        handleWrappedOpenIntent(intent)
    }

    /**
     * Extracts the `.gpx` [Uri] from an incoming `ACTION_VIEW` intent and hands it to
     * the [GpxOpenBus] so the map's `MapViewModel` can show the import-or-preview
     * chooser.
     *
     * The post is **synchronous** on purpose. A cold start from another app (Telegram,
     * e-mail, …) frequently recreates this Activity once right after `onCreate` (the
     * per-Activity locale wrapping in [attachBaseContext] / a config change). Posting
     * from a coroutine tied to the Activity's `lifecycleScope` was therefore **cancelled
     * mid-flight** before it could deliver — which is exactly why the *first* open after
     * launch silently did nothing while later (warm) opens worked. Posting inline can't
     * be cancelled, and the bus is a retained [kotlinx.coroutines.flow.StateFlow], so a
     * value posted before the `MapViewModel` even exists is still delivered once it
     * starts collecting. Copying the bytes into private cache (to decouple the later
     * parse from the transient one-shot URI grant) is done in the ViewModel, on its
     * `viewModelScope`, which the Activity recreation does not cancel.
     *
     * A best-effort persistable read grant is taken first (a harmless no-op — caught —
     * for the common one-shot grants). Non-view intents (e.g. the launcher) are ignored.
     */
    private fun handleGpxViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        gpxOpenBus.post(uri)
    }

    /**
     * Extracts the Wrapped report id from a tapped "your Wrapped is ready"
     * notification and hands it to the [WrappedOpenBus] so the map layer's
     * `WrappedViewModel` opens that report's Story. Posted synchronously (like the
     * GPX hand-off) so a cold start — which may recreate this Activity once right
     * after `onCreate` — still delivers via the retained bus once the UI exists.
     * The extra is cleared so a rotation/recreation can't re-open it.
     */
    private fun handleWrappedOpenIntent(intent: Intent?) {
        val id = intent?.getStringExtra(WrappedOpenBus.EXTRA_OPEN_WRAPPED_REPORT_ID) ?: return
        wrappedOpenBus.post(id)
        intent.removeExtra(WrappedOpenBus.EXTRA_OPEN_WRAPPED_REPORT_ID)
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
