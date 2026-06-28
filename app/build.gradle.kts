import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

// ---------------------------------------------------------------------------
// Release signing credentials
// ---------------------------------------------------------------------------
// Resolved (in order) from:
//   1. A gitignored `keystore.properties` at the repo root (local releases)
//   2. Environment variables (CI / GitHub Actions)
// The keystore and all passwords are NEVER committed (see .gitignore).
//
// keystore.properties keys: storeFile, storePassword, keyAlias, keyPassword
// CI env variables:        KEYSTORE_PATH, KEYSTORE_PASSWORD (or STORE_PASSWORD),
//                          KEY_ALIAS, KEY_PASSWORD
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
fun releaseSigning(propKey: String, vararg envKeys: String): String? =
    keystoreProperties.getProperty(propKey)
        ?: envKeys.firstNotNullOfOrNull { System.getenv(it) }

android {
    namespace = "de.velospot"
    compileSdk = 37

    signingConfigs {
        // Release signing is configured via keystore.properties or CI env vars.
        // Local builds without those fall back to the debug signing config.
        create("release") {
            val storePath = releaseSigning("storeFile", "KEYSTORE_PATH")
            if (storePath != null) {
                storeFile     = file(storePath)
                storePassword = releaseSigning("storePassword", "KEYSTORE_PASSWORD", "STORE_PASSWORD")
                keyAlias      = releaseSigning("keyAlias", "KEY_ALIAS")
                keyPassword   = releaseSigning("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "de.velospot"
        minSdk = 26
        targetSdk = 37

        // Static version literals - F-Droid reads these directly via regex.
        // The release workflow updates them via sed before committing the release tag,
        // so the tagged commit always contains the correct values.
        // WARNING: Do NOT replace these literals with dynamic expressions.
        versionCode = 10024
        versionName = "1.0.24"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("googlePlay") {
            dimension = "distribution"
        }
        create("fdroid") {
            dimension = "distribution"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // In CI: release key from env variables. Locally: falls back to debug signing.
            signingConfig = if (releaseSigning("storeFile", "KEYSTORE_PATH") != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")

            // Reproducible builds: do NOT embed the Git commit hash into
            // META-INF/version-control-info.textproto. AGP writes this VCS info at
            // build time, which is non-reproducible and is the ONLY file that differs
            // between F-Droid's rebuild and the signed release APK. Disabling it makes
            // the F-Droid build byte-for-byte reproducible. (AGP 8.0+ `vcsInfo` DSL.)
            vcsInfo {
                include = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // F-Droid rejects the AGP-generated, Google-signed "Dependency metadata" signing
    // block (it is opaque/non-reproducible). It only lands in the APK, so we strip it
    // there while keeping it in the AAB for Google Play's upload-time processing.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

// Export Room schema for pre-populated database generation
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {

    // BRouter offline routing engine.
    // Built reproducibly from source via the :brouter module, which compiles the
    // pinned `brouter-upstream` git submodule (BRouter v1.7.9). No pre-built JAR,
    // no binary blob, no F-Droid prebuild step — a plain Gradle build resolves it.
    // See brouter/README.md for the module/submodule setup.
    implementation(project(":brouter"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidxLifecycleViewmodelKtx)
    implementation(libs.androidxLifecycleViewmodelCompose)
    implementation(libs.androidxLifecycleRuntimeCompose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidxNavigationCompose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidxComposeIconsExtended)

    implementation(libs.hiltAndroid)
    ksp(libs.hiltAndroidCompiler)
    implementation(libs.androidxHiltNavigationCompose)

    implementation(libs.retrofitCore)
    implementation(libs.retrofitConverterMoshi)
    implementation(libs.moshiKotlin)
    implementation(libs.okhttpLoggingInterceptor)

    implementation(libs.maplibreAndroid)

    // Room Database
    implementation(libs.roomRuntime)
    implementation(libs.roomKtx)
    ksp(libs.roomCompiler)

    // Location Services – only for the Google Play flavor (proprietary, not F-Droid-compatible)
    "googlePlayImplementation"(libs.playServicesLocation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinxCoroutinesTest)
    testImplementation(libs.mockitoCore)
    testImplementation(libs.mockitoKotlin)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
