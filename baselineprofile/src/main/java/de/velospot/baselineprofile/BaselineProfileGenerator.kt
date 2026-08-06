package de.velospot.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's **Baseline Profile**.
 *
 * The profile captures the classes/methods exercised on the critical startup
 * path (process start → first drawn frame of the map) so ART can AOT/JIT-compile
 * them ahead of time on the user's device, measurably shortening cold start and
 * the time-to-first-map-frame. `includeInStartupProfile = true` also emits a
 * *startup* profile that feeds AGP's dex layout optimization.
 *
 * Run headlessly on the managed device configured in this module's build script:
 *   ./gradlew :app:generateBaselineProfile
 *
 * The result is written to `app/src/release/generated/baselineProfiles/` and is
 * consumed automatically by the `androidx.baselineprofile` plugin in :app.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        // Fresh cold start.
        pressHome()
        startActivityAndWait()

        // Let the first map frames render so map bootstrap code is captured on the
        // profiled path (kept resilient: no hard waits on specific UI nodes so the
        // generator never fails if the map style/labels change).
        device.waitForIdle()
    }
}

