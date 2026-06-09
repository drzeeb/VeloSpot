package de.velospot

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import de.velospot.core.locale.LanguagePreferences

@HiltAndroidApp
class BaseApplication : Application() {

    override fun attachBaseContext(base: Context) {
        // Apply user-saved language before any resource is loaded.
        super.attachBaseContext(LanguagePreferences.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // MapLibre is initialised lazily via MapLibre.getInstance(context)
        // inside rememberMapViewWithLifecycle – no global setup needed here.
    }
}
