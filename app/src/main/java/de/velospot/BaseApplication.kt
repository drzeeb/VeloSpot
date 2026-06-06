package de.velospot

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import de.velospot.core.locale.LanguagePreferences
import org.osmdroid.config.Configuration

@HiltAndroidApp
class BaseApplication : Application() {

    override fun attachBaseContext(base: Context) {
        // Apply user-saved language before any resource is loaded.
        super.attachBaseContext(LanguagePreferences.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize Osmdroid configuration once at app startup.
        // Set User-Agent (mandatory for tile providers) + load personal settings.
        Configuration.getInstance().apply {
            load(this@BaseApplication, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
    }
}

