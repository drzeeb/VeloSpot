# Generating page / store screenshots

The marketing screenshots (used on the website under `docs/screenshots/` and in the
store metadata under `fastlane/metadata/android/<locale>/images/phoneScreenshots/`)
are produced from the **real running app** — including the native MapLibre map — by an
instrumented test.

> Why not Compose Preview Screenshot Testing (the "Google screenshot" tool)?
> It renders isolated `@Preview` composables headlessly via `layoutlib` and **cannot
> render the native MapLibre `SurfaceView`**. Since every page screenshot is dominated
> by the live map, we capture the real running app instead.

## How it works

- `app/src/androidTest/.../screenshots/ScreenshotSaver.kt` — captures the **whole
  screen** via `UiAutomation.takeScreenshot()` (so the native map *and* Compose popups
  like the menu / bottom sheets are included), **crops the status bar** off the top, and
  writes a PNG into the shared `Pictures/VeloSpotScreenshots` MediaStore folder.
- `app/src/androidTest/.../screenshots/PageScreenshotTest.kt` — launches the app,
  **denies the location permission** (so the map never shows your real position), drives
  the UI (search, menu, layers, favorites, language, dark mode) and saves one PNG per
  screen. Each step is best-effort, so a single failure never aborts the run.

The MediaStore folder survives the app uninstall AGP performs after the run, so it can
be pulled afterwards:

```
/sdcard/Pictures/VeloSpotScreenshots/<name>.png
```

## Run it

Start an emulator (or plug in a device with network for map tiles). Wipe any previous
run on the device first (the freshly installed app can't overwrite orphaned files),
then run the command **on a single line**.

PowerShell (Windows) — quote the `-P` argument and keep it on one line:

```powershell
adb shell rm -rf /sdcard/Pictures/VeloSpotScreenshots
.\gradlew :app:connectedGooglePlayDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=de.velospot.screenshots.PageScreenshotTest"
adb pull /sdcard/Pictures/VeloSpotScreenshots ./screenshots
```

bash / zsh (macOS, Linux) — the `\` line-continuation only works here, **not** in
PowerShell:

```bash
adb shell rm -rf /sdcard/Pictures/VeloSpotScreenshots
./gradlew :app:connectedGooglePlayDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.velospot.screenshots.PageScreenshotTest
adb pull /sdcard/Pictures/VeloSpotScreenshots ./screenshots
```

> Set the device language to English for one run and to German for another to
> regenerate both the `en-US` and `de-DE` sets.

### If the build fails

- **`No connected devices!`** — start an emulator (Android Studio ▸ Device Manager)
  or connect a device with USB debugging, then re-run. Check with `adb devices`.
- **`Unknown command` / it splits across lines** — you pasted the multi-line bash
  form into PowerShell. Use the single-line PowerShell command above.
- **`(1)` suffixes pile up** — you skipped the `adb shell rm -rf …` cleanup step.
- **Map tiles missing in the PNGs** — the device had no network, or `APP_INIT_MS` /
  `MAP_SETTLE_MS` in `PageScreenshotTest` were too short; raise them and re-run.


## Extending

`found-location` and `parking-details` need real map data / a marker tap and are left
as `TODO`s in `PageScreenshotTest`. Script them once against a device where you know the
visible content (e.g. type a known address and tap the first result, or tap a parking
marker at a known location).

