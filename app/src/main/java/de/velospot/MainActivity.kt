package de.velospot

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        enableEdgeToEdge()
        setContent {
            var darkThemeEnabled by remember {
                mutableStateOf(DarkModePreferences.isDarkModeEnabled(this))
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
