package de.velospot

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import de.velospot.core.locale.LanguagePreferences
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class BaseApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Hilt entry point so the Application (which can't use field injection) can
     *  reach the singleton ride repository for one-off maintenance work. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MaintenanceEntryPoint {
        fun recordedRidesRepository(): RecordedRidesRepository
    }

    override fun attachBaseContext(base: Context) {
        // Apply user-saved language before any resource is loaded.
        super.attachBaseContext(LanguagePreferences.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // MapLibre is initialised lazily via MapLibre.getInstance(context)
        // inside rememberMapViewWithLifecycle – no global setup needed here.
        runElevationBackfillOnce()
    }

    /**
     * One-off recompute of stored rides' cumulative elevation with the corrected
     * shared integrator. Guarded by a persisted flag so it runs at most once per
     * install; the recompute itself is deterministic/idempotent, so re-running is
     * harmless. Runs off the main thread.
     */
    private fun runElevationBackfillOnce() {
        val prefs = getSharedPreferences(MAINTENANCE_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ELEVATION_BACKFILL_DONE, false)) return
        appScope.launch {
            val repo = EntryPointAccessors
                .fromApplication(this@BaseApplication, MaintenanceEntryPoint::class.java)
                .recordedRidesRepository()
            runCatching { repo.recomputeStoredElevation() }
                .onSuccess { prefs.edit { putBoolean(KEY_ELEVATION_BACKFILL_DONE, true) } }
        }
    }

    private companion object {
        const val MAINTENANCE_PREFS = "velospot_maintenance"
        const val KEY_ELEVATION_BACKFILL_DONE = "elevation_backfill_v1_done"
    }
}
