package de.velospot

import android.app.Activity
import android.content.Context
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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

    override fun attachBaseContext(newBase: Context) {
        // Re-apply user-saved language for every Activity recreation.
        super.attachBaseContext(LanguagePreferences.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
