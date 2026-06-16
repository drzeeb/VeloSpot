// ---------------------------------------------------------------------------
// :brouter — reproducible, source-only build of the BRouter routing engine
// ---------------------------------------------------------------------------
// This plain `java-library` module compiles BRouter *from source* instead of
// shipping a pre-built JAR. The source lives in the pinned git submodule under
// `brouter-upstream/` (https://github.com/abrensch/brouter @ tag v1.7.9).
//
// Only the five on-device routing modules are compiled — exactly the set that
// the old slimmed `brouter-1.7.9-all.jar` contained:
//   brouter-util        (btools.util)
//   brouter-codec       (btools.codec)
//   brouter-expressions (btools.expressions)
//   brouter-mapaccess   (btools.mapaccess)
//   brouter-core        (btools.router)
// The server / map-creation modules (brouter-server, brouter-map-creator,
// brouter-routing-app) and their protobuf/osmosis dependencies are intentionally
// left out: they are only used to build map data, never to route on-device.
//
// Because the source is pinned via the submodule commit and compiled with a
// fixed JDK/target, both the developer's release build and F-Droid's build
// produce byte-identical classes — the foundation for a reproducible APK.
// ---------------------------------------------------------------------------

plugins {
    `java-library`
}

java {
    // Match the app module's Java level for deterministic, compatible bytecode.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// Location of the checked-out BRouter source (git submodule).
val brouterSrc = rootProject.layout.projectDirectory.dir("brouter-upstream")

// Fail early with an actionable message if the submodule was not initialised.
val routerSrcDir = brouterSrc.dir("brouter-core/src/main/java").asFile
if (!routerSrcDir.exists()) {
    throw GradleException(
        "BRouter source not found at '$routerSrcDir'.\n" +
            "Initialise the pinned submodule once with:\n" +
            "    git submodule update --init --recursive\n" +
            "(see docs/RELEASING.md for details)."
    )
}

sourceSets {
    named("main") {
        java.setSrcDirs(
            listOf(
                brouterSrc.dir("brouter-util/src/main/java"),
                brouterSrc.dir("brouter-codec/src/main/java"),
                brouterSrc.dir("brouter-expressions/src/main/java"),
                brouterSrc.dir("brouter-mapaccess/src/main/java"),
                brouterSrc.dir("brouter-core/src/main/java"),
            )
        )
        // BRouter ships no resources we need on-device; keep the set empty so
        // nothing non-deterministic leaks into the artifact.
        resources.setSrcDirs(emptyList<String>())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Deterministic compilation: no compiler-supplied build timestamps.
    options.compilerArgs.add("-Xlint:none")
    options.isDeprecation = false
}

// Reproducible archive: strip timestamps and force a stable file order so the
// produced JAR (and therefore the dexed output) is bit-for-bit stable.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

