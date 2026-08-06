# `:baselineprofile` — Baseline Profile producer

This module generates the **Baseline Profile** for VeloSpot: a list of the
classes/methods on the critical startup path (process start → first drawn map
frame). Shipping it lets ART **AOT/JIT-compile that hot path ahead of time** on
the user's device, measurably shortening **cold start** and the
**time-to-first-map-frame** — on top of R8 full mode (AGP's default), not
instead of it.

It is a `com.android.test` module driven by AndroidX **Macrobenchmark**:

- `BaselineProfileGenerator` — captures the profile (`BaselineProfileRule`).
- `StartupBenchmark` — measures cold start with vs. without the profile so the
  gain can be quantified.

## Generate / refresh the profile

Runs headlessly on the Gradle **Managed Device** configured in
`build.gradle.kts` (`pixel6Api34`, AOSP ATD) — no physical phone required:

```bash
./gradlew :app:generateBaselineProfile
```

The result is written to `app/src/release/generated/baselineProfiles/` and is
merged into the release ART profile automatically by the
`androidx.baselineprofile` consumer plugin applied in `:app`. Commit the
regenerated profile alongside the release.

## Measure the improvement

```bash
./gradlew :baselineprofile:pixel6Api34BenchmarkAndroidTest
```

Compare `timeToInitialDisplay` between `StartupBenchmark.startupNoCompilation`
(JIT only) and `startupBaselineProfile` (partial AOT with the profile).

## How it fits together

- `:app` applies `androidx.baselineprofile` (consumer) and depends on
  `androidx.profileinstaller`, whose `ProfileInstallReceiver` installs the
  bundled profile on first run.
- `:app` declares `baselineProfile(project(":baselineprofile"))` to wire this
  producer to the consumer.
- The profile is regenerated per release; it is not hand-edited.

