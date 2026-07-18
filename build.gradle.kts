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
        resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    }
}
