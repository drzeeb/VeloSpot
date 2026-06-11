import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

/**
 * Reads the current Git tag (format vX.Y.Z) and converts it to a numeric versionCode
 * using the formula X * 10000 + Y * 100 + Z (e.g. v1.0.9 → 10009).
 *
 * Used as a fallback when -PversionCode / -PversionName are not supplied by the caller
 * (e.g. F-Droid builds the APK directly from the tagged commit without injecting properties).
 *
 * Returns (1, "1.0") when not on an exact tag (local dev builds).
 */
fun versionFromGitTag(): Pair<Int, String> {
    return try {
        val tag = ProcessBuilder("git", "describe", "--tags", "--exact-match")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readLine()?.trim() ?: ""
        val clean = tag.removePrefix("v")
        val parts = clean.split(".")
        if (parts.size == 3) {
            val code = parts[0].toInt() * 10000 + parts[1].toInt() * 100 + parts[2].toInt()
            Pair(code, clean)
        } else Pair(1, "1.0")
    } catch (_: Exception) {
        Pair(1, "1.0")
    }
}

android {
    namespace = "de.velospot"
    compileSdk = 37

    signingConfigs {
        // Release signing is configured via environment variables in CI.
        // Local builds fall back to the debug signing config automatically.
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile     = file(keystorePath)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias      = System.getenv("KEY_ALIAS")
                keyPassword   = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "de.velospot"
        minSdk = 26
        targetSdk = 37

        // versionCode and versionName are resolved in this priority order:
        //  1. Gradle properties injected by CI: -PversionCode=10009 -PversionName=1.0.9
        //  2. Current Git tag (vX.Y.Z → X*10000+Y*100+Z) – used by F-Droid builds
        //  3. Fallback (1 / "1.0") for local dev builds without a tag
        val (gitCode, gitName) = versionFromGitTag()
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: gitCode
        versionName = (project.findProperty("versionName") as String?) ?: gitName

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
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
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

    // BRouter offline routing engine – place brouter.jar from https://brouter.de into app/libs/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

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
    implementation(libs.coilCompose)

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
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
