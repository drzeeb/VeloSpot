package de.velospot

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class BaseApplication : Application() {
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

