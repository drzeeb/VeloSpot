import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
    // Kotlin code coverage (Kover). Generates JaCoCo-compatible XML/HTML reports
    // from the JVM unit tests. Report tasks per Android variant, e.g.
    //   ./gradlew :app:koverXmlReportDebug
    //   ./gradlew :app:koverHtmlReportDebug
    alias(libs.plugins.kover)
    // CycloneDX generates a Software Bill of Materials (SBOM) for supply-chain
    // transparency. Applied here (not at the root) so the per-project "direct"
    // task can scan the app's runtime classpath, which transitively includes
    // the :brouter module's dependencies. Task: `cyclonedxDirectBom`.
    alias(libs.plugins.cyclonedx)
    // Baseline Profile consumer. Merges the profile produced by :baselineprofile
    // into the release ART profile for faster cold start / first map frames.
    // Generate/refresh it with `./gradlew :app:generateBaselineProfile`.
    alias(libs.plugins.androidx.baselineprofile)
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

        // Static version literals. The release workflow greps these to verify they
        // match the pushed Git tag before building, so keep them as plain literals.
        // WARNING: Do NOT replace these literals with dynamic expressions.
        versionCode = 10030
        versionName = "1.0.30"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Lint only needs the app's own (main) sources. Analysing the instrumented
        // androidTest sources pulls in the full androidTest compile classpath
        // (JUnit/Espresso/…) to build the androidTest lint model — which the lint CI
        // job doesn't need and which made `lintDebug` fail (and poisoned the
        // configuration cache) whenever that classpath couldn't be resolved from a
        // flaky/forbidding Maven Central. Skipping test sources keeps lint focused
        // and self-contained; unit/instrumented tests are validated by their own jobs.
        ignoreTestSources = true
    }

    // Keep the AGP-generated "Dependency metadata" block out of the APK (it is opaque
    // and non-reproducible) while keeping it in the AAB for Google Play's upload-time
    // processing.
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
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// Test coverage (Kover)
// ---------------------------------------------------------------------------
// Coverage is measured on the debug variant (the canonical CI build). The
// Android integration auto-registers the `debug` reports variant, so the report
// tasks `:app:koverXmlReportDebug` / `:app:koverHtmlReportDebug` are available.
// Generated code (Hilt/Dagger, Room, Compose synthetics) and pure-UI/DI classes
// carry no meaningful unit-test coverage, so they are excluded to keep the
// percentage representative of the actually testable logic.
kover {
    reports {
        filters {
            excludes {
                classes(
                    // Generated component & binding code
                    "*_HiltModules*",
                    "*_Factory",
                    "*_Impl",
                    "*Hilt_*",
                    "dagger.hilt.*",
                    "hilt_aggregated_deps.*",
                    "*.databinding.*",
                    "*.BuildConfig",
                    // Room-generated DAOs / database implementations
                    "*_Impl*",
                    // Moshi-generated JSON adapters
                    "*JsonAdapter",
                    // Compose UI + previews (exercised by instrumented/UI tests, not JVM units).
                    // Trailing wildcards also drop the synthetic lambda / `…Kt` facade classes
                    // Kotlin generates for these files, which otherwise leak into coverage.
                    "*ComposableSingletons*",
                    "*.*Screen*Kt",
                    "*Screen*Kt$*",
                    "de.velospot.ui.*",
                    // Dependency injection wiring (no logic to unit-test). Modules live in
                    // `core.di`, so both the legacy and the real package are excluded.
                    "de.velospot.di.*",
                    "de.velospot.core.di.*",
                    // Room persistence declarations (interfaces / abstract classes / data holders)
                    "de.velospot.data.local.dao.*",
                    "de.velospot.data.local.database.*",
                    "de.velospot.data.local.entity.*",
                    // Jetpack DataStore persistence — needs a real Context / backing files,
                    // so it is exercised by instrumented tests (same rationale as Room above).
                    "de.velospot.data.settings.MapSettingsDataStore*",
                    // Native routing bridge: drives the bundled BRouter core over real .rd5
                    // segment files (integration/on-device territory, no JVM-unit surface).
                    "de.velospot.data.brouter.BRouterEngine*",
                    // GPX import parser is a thin wrapper around android.util.Xml, which is a
                    // non-functional stub under JVM unit tests (needs an instrumented env).
                    "de.velospot.core.gpx.GpxParser*",
                    // Android framework entry points (require an instrumented environment).
                    // Trailing `*` also excludes their generated lambda classes.
                    "de.velospot.MainActivity*",
                    "de.velospot.BaseApplication*",
                    "de.velospot.core.tracking.RideRecordingService*",
                    "de.velospot.core.tracking.RideRecordingTileService*",
                    "de.velospot.core.tracking.RideRecordingWidget*",
                    "de.velospot.core.tracking.BikeServiceNotifier*",
                    // System location provider glue (FusedLocationProvider callbacks —
                    // needs an instrumented environment / Play services).
                    "de.velospot.data.location.LocationRepositoryImpl*",
                    // MapLibre / Canvas rendering & camera glue (needs a real GL surface).
                    // Trailing `*` also excludes the generated lambda + `…Kt` facade classes.
                    "de.velospot.feature.map.presentation.markers.*",
                    "*NavigationManager*",
                    "*NavigationVoiceGuide*",
                    "*RideShareCardRenderer*",
                    // The all-time "Wrapped" stats card is likewise drawn with the
                    // platform Canvas onto an off-screen Bitmap (needs a real graphics
                    // surface); its plain-data inputs/themes are unit-tested instead.
                    "*StatsShareCardRenderer*",
                    "*RideRouteMapSnapshotter*",
                    "*MapInitializer*",
                    "*RideReplayMap*",
                    "*RouteElevationProfile*",
                    // Offline map tiles download is pure MapLibre OfflineManager glue
                    // (its pure region maths is covered by OfflineMapRegionsTest).
                    "*OfflineMapTilesManager*",
                    // Pure Compose UI on the map surface — the bottom sheets and the
                    // in-map overlays/HUD/banners/FABs. Same category as the excluded
                    // `*Screen*Kt` above (declarative Compose, exercised by instrumented/
                    // UI tests, not JVM units); their `…Kt` facade + synthetic lambda
                    // classes otherwise leak past the `annotatedBy(@Composable)` filter.
                    // The logic they lean on lives in the tested controllers/helpers.
                    "de.velospot.feature.map.presentation.sheets.*",
                    "*MapOverlaysKt",
                    "*MapOverlaysKt$*",
                    "*MapTurnBannerKt",
                    "*MapTurnBannerKt$*",
                    "*MapActionsSpeedDialKt",
                    "*MapActionsSpeedDialKt$*",
                    "*MapUiActionComponentsKt",
                    "*MapUiActionComponentsKt$*",
                    "*SunAlertFabKt",
                    "*SunAlertFabKt$*",
                    "*TripComputerHudKt",
                    "*TripComputerHudKt$*",
                    "*WeatherChipKt",
                    "*WeatherChipKt$*",
                )
                annotatedBy(
                    "androidx.compose.runtime.Composable",
                    "androidx.compose.ui.tooling.preview.Preview",
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SBOM (Software Bill of Materials) — CycloneDX
// ---------------------------------------------------------------------------
// A JSON + XML SBOM is produced for supply-chain transparency and attached to
// each GitHub Release (see .github/workflows/release.yml). The per-project
// "direct" task scans the release runtime classpath — the canonical build that
// actually ships. It transitively includes the :brouter module's runtime
// dependencies, so this one classpath covers everything in the APK/AAB.
// (includeConfigs entries are matched as regexes.)
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    schemaVersion.set(org.cyclonedx.Version.VERSION_16)
    componentName.set("de.velospot")
    componentVersion.set(android.defaultConfig.versionName ?: "")
    includeConfigs.set(listOf("releaseRuntimeClasspath"))
    // Stable, explicit output locations (attached to releases by CI).
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/bom.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx/bom.xml"))
}

dependencies {

    // BRouter offline routing engine.
    // Built from source via the :brouter module, which compiles the pinned
    // `brouter-upstream` git submodule (BRouter v1.7.10). No pre-built JAR and no
    // binary blob — a plain Gradle build resolves it.
    // See brouter/README.md for the module/submodule setup.
    implementation(project(":brouter"))

    // Installs the bundled Baseline Profile (produced by :baselineprofile) on first
    // run so ART can AOT/JIT-compile the hot startup path. `baselineProfile(...)`
    // wires the producer module to the consumer plugin applied above.
    implementation(libs.androidxProfileinstaller)
    baselineProfile(project(":baselineprofile"))

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
    // Hilt/Dagger's generated components reference @CanIgnoreReturnValue from
    // error_prone_annotations, pulled in transitively via play-services. Declare it
    // explicitly (compile-time only annotation) so the build never depends on that
    // transitive edge.
    compileOnly(libs.errorProneAnnotations)

    implementation(libs.retrofitCore)
    implementation(libs.retrofitConverterMoshi)
    implementation(libs.moshiKotlin)
    implementation(libs.okhttpLoggingInterceptor)

    implementation(libs.maplibreAndroid)

    // Room Database
    implementation(libs.roomRuntime)
    implementation(libs.roomKtx)
    ksp(libs.roomCompiler)

    // Jetpack DataStore – reactive, non-blocking key-value settings (replaces
    // the main-thread SharedPreferences reads for the map's UI toggles).
    implementation(libs.androidxDatastorePreferences)

    // Location Services (Google Play Services Fused Location Provider)
    implementation(libs.playServicesLocation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinxCoroutinesTest)
    testImplementation(libs.mockitoCore)
    testImplementation(libs.mockitoKotlin)
    // Real org.json on the unit-test classpath so classes using it (e.g. the offline
    // regions store) are JVM-testable — the android.jar stub throws "not mocked".
    testImplementation(libs.orgJson)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
