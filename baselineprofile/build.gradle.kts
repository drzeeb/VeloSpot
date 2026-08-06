// ---------------------------------------------------------------------------
// :baselineprofile — Baseline Profile producer (Macrobenchmark)
// ---------------------------------------------------------------------------
// A `com.android.test` module that drives the app on a device / Gradle Managed
// Device to (a) generate the ART **Baseline Profile** consumed by :app for a
// faster cold start and quicker first map frames, and (b) measure startup.
//
// Generate the profile (headless, via the managed device below):
//   ./gradlew :app:generateBaselineProfile
// Measure startup (before/after):
//   ./gradlew :baselineprofile:pixel6Api34BenchmarkAndroidTest
//
// The generated profile lands in `app/src/release/generated/baselineProfiles/`
// and is merged into the release ART profile automatically by the
// `androidx.baselineprofile` consumer plugin applied in :app. It works together
// with R8 full mode (AGP's default) — the profile guides AOT/JIT compilation of
// the hot startup path while R8 still shrinks/optimizes the code.
// ---------------------------------------------------------------------------
import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "de.velospot.baselineprofile"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The app whose startup we profile / benchmark.
    targetProjectPath = ":app"

    // A headless Gradle Managed Device so the profile can be generated in CI or
    // locally without a physically connected phone. AOSP ATD image = no Google
    // APIs needed for a cold-start profile and it boots fast.
    testOptions.managedDevices.allDevices {
        create<ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
    }
}

// Run the generator/benchmarks on the managed device by default (not on a random
// connected device) so results are deterministic.
baselineProfile {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidxTestRunner)
    implementation(libs.androidxBenchmarkMacroJunit4)
}

