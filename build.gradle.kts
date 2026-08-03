// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hiltAndroid) apply false
}

// Force kotlin-metadata-jvm to the version that matches the Kotlin compiler,
// so Hilt's bundled copy does not reject Kotlin 2.4+ class metadata.
allprojects {
    configurations.all {
        // TODO: Temporary workaround for Hilt vs. Kotlin 2.4. Hilt bundles an older
        // kotlin-metadata-jvm that rejects Kotlin 2.4+ class metadata, so we force it
        // to match the Kotlin compiler. Keep this version in sync with the kotlin
        // version in gradle/libs.versions.toml (currently 2.4.10); if they ever drift
        // this force is wrong. Remove entirely once Hilt ships a release that accepts
        // the current Kotlin metadata, and re-check on every Hilt/Kotlin bump.
        resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    }
}
