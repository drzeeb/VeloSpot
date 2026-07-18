// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hiltAndroid) apply false
    // CycloneDX generates a Software Bill of Materials (SBOM) aggregating the
    // dependencies of every module (:app + :brouter). Task: `cyclonedxBom`.
    // Output: build/reports/bom.json + build/reports/bom.xml
    alias(libs.plugins.cyclonedx)
}

// ---------------------------------------------------------------------------
// SBOM (Software Bill of Materials) — CycloneDX
// ---------------------------------------------------------------------------
// A JSON + XML SBOM is produced for supply-chain transparency and attached to
// each GitHub Release (see .github/workflows/release.yml). It lists every
// runtime dependency with its version and license.
group = "de.velospot"
version = "1.0.24"

tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    setProjectType("application")
    setSchemaVersion("1.6")
    setDestination(file("build/reports"))
    setOutputName("bom")
    setOutputFormat("all")
    // Scan the F-Droid release runtime classpath — the canonical, reproducible
    // build that actually ships. It transitively includes the :brouter module's
    // runtime dependencies, so this single classpath covers everything in the APK.
    // (includeConfigs entries are matched as regular expressions.)
    setIncludeConfigs(listOf("fdroidReleaseRuntimeClasspath"))
}

// Force kotlin-metadata-jvm to the version that matches the Kotlin compiler,
// so Hilt's bundled copy does not reject Kotlin 2.4+ class metadata.
allprojects {
    configurations.all {
        resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    }
}
