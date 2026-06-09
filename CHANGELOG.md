# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- **Tap-to-place custom map pin** — tap any empty location on the map to drop a blue pin marker at that exact point; the map camera animates to the tapped position automatically
- **`CustomMapPinSheet`** — bottom sheet that opens immediately when a custom pin is placed; shows the reverse-geocoded address from Nominatim (with a loading spinner and raw coordinates as fallback while the request is in-flight); provides a "Navigate here" primary action and a "Remove pin" secondary action
- **Reverse geocoding on tap** — `MapViewModel.onMapTapped()` fires a `NominatimGeocoder.reverseGeocode()` coroutine in the background; the resolved address replaces the coordinate placeholder as soon as the response arrives
- **Custom pin navigation** — "Navigate here" starts BRouter in-app bike routing directly to the tapped coordinates; the pin stays visible on the map as a route end-point marker for the entire duration of navigation and is automatically removed when navigation is stopped
- **Blue custom pin icon** — new `createCustomPinIcon()` function in `MapMarkerRenderer` renders a distinct blue dropped-pin (Material Blue 700 `#1565C0`) to visually separate the tap pin from the red address-search pin and the bike-rack parking markers
- **Dedicated GeoJSON source and layer** (`velospot-custom-pin-source` / `velospot-custom-pin-layer`) for the custom pin, following the same MapLibre `SymbolLayer` pattern as existing markers
- **`customMapPin` and `customMapPinAddress` StateFlows** in `MapViewModel` to expose pin position and its resolved address to the UI layer independently
- **String resources** for `custom_pin_title`, `custom_pin_subtitle`, `custom_pin_navigate`, `custom_pin_remove` in EN and DE

### Changed
- `MainMapScreen` map-click listener now **always consumes the tap event** (`return true`) — clicking an empty area places a custom pin instead of being silently ignored
- Selecting a parking space or a Nominatim search result **dismisses any active custom pin** and vice versa, ensuring only one pin type is visible at a time
- `stopInAppNavigation()` only clears the custom pin when the destination was the custom pin itself; navigating to a parking space or address does not affect the custom pin state

---

## [v1.0.8] — 2026-06-10

### Added
- **Address search bar** — floating search field at the top of the map; type any German address and receive up to 5 Nominatim suggestions with 400 ms debounce (minimum 3 characters) while you type
- **Search result pin** — tapping a suggestion drops a location pin on the map and animates the camera to that position; a `SearchPinSheet` bottom sheet displays the full resolved address and a "Navigate here" action that starts in-app BRouter routing directly to the address coordinates
- **`AddressSearchResult`** domain model and `NominatimSearchResultDto` Moshi DTO backing the Nominatim `/search` JSON response
- **Nominatim `/search` forward geocoding endpoint** added to `NominatimApi`; results are restricted to Germany (`countrycodes=de`); existing reverse geocoding endpoint unchanged
- **Search string resources** in all 8 supported languages: `search_placeholder`, `search_no_results`, `search_clear`, `search_result_pin_title`, `search_navigate_to`

### Changed
- **Map rendering migrated from OSMDroid to MapLibre** (`org.maplibre.gl:android-sdk:11.5.2`)
  - Map tiles now rendered as **vector tiles** (OpenFreeMap Liberty style, no API key required) — smooth scaling at every zoom level, no more pixelated raster tiles
  - `MapView` lifecycle now follows the full MapLibre lifecycle chain (`onCreate` / `onStart` / `onResume` / `onPause` / `onStop` / `onDestroy`)
  - `MapCameraAnimator` rewritten to use `MapLibreMap.animateCamera()` with `CameraPosition.Builder` — replaces manual coroutine-based easing loop; vertical offset for bottom-sheet compensation preserved via `calculateAdjustedCenter()`
  - `MapMarkerRenderer` rewritten: parking spots, route polyline, and location dot are now managed as **GeoJSON sources** (`GeoJsonSource`) with **`SymbolLayer`** / **`LineLayer`** instead of per-marker osmdroid overlays — only the data payload is updated on each state change, no overlay teardown/rebuild
  - `MainMapScreen` updated: `getMapAsync` callback sets up `CameraIdleListener` (viewport changes), `CameraMoveListener` (zoom-bucket tracking), and map-click listener (feature hit-testing via `queryRenderedFeatures`) once; marker updates driven by `LaunchedEffect` reacting to Compose state
  - `BaseApplication` cleaned up: osmdroid `Configuration.getInstance()` initialisation removed
  - `MapCameraAnimatorTest` updated: `org.osmdroid.util.GeoPoint` replaced with `org.maplibre.android.geometry.LatLng`
- **Address search bar and menu button** are now placed in a shared `Row` with `verticalAlignment = Alignment.CenterVertically` so both UI elements sit on the exact same horizontal baseline regardless of their individual card heights
- **MapLibre compass** repositioned to top-left with system-bar-aware margins to avoid overlap with the search bar and menu card

### Security
- **R8 minification and obfuscation** enabled for release builds (`isMinifyEnabled = true`, `isShrinkResources = true` in `app/build.gradle.kts`)
- **Comprehensive ProGuard rules** added for Room, Moshi, Retrofit, OkHttp, BRouter JAR (including private `ilat`/`ilon` reflection fields), Hilt/Dagger, Kotlin coroutines, and all domain/model classes
- **`HttpLoggingInterceptor`** guarded behind `BuildConfig.DEBUG` — network URLs and headers are never written to Logcat in release builds
- **All `Log.d` / `Log.w` calls** in `BRouterEngine` and `NominatimGeocoder` guarded behind `BuildConfig.DEBUG` to prevent GPS coordinates leaking to Logcat in production

### Dependencies
- `mockito-kotlin` updated to v6 (Renovate #35)
- `mockito` monorepo updated to v5.23.0 (Renovate #34)
- OkHttp / Retrofit (Networking) updated to v5.4.0 (Renovate #15)

---

## [v1.0.7] — 2026-06-08

### Added
- **BRouter offline routing engine** embedded as a local JAR (`app/libs/brouter.jar`) — routes are calculated entirely on-device, no internet required after the one-time segment download
- **`BRouterEngine`** — Kotlin wrapper around the BRouter Java API; reads `.brf` profile files from app assets and `.rd5` segment files from external storage; accesses private coordinate fields via reflection for JAR-version compatibility
- **`BRouterSegmentManager`** — manages download and local caching of BRouter `.rd5` map segment files; downloads the single 5°×5° tile covering the user's position (~200–250 MB, one-time); throws `NoInternetConnectionException` on network failure
- **`BRouterProfile` enum** — five routing profiles with localised name and description string resources: Trekking *(recommended default)*, Schnell, Kürzeste Strecke, Mountainbike, Gravel
- **`OfflineRoutingPreferences`** — SharedPreferences-backed storage for the opt-in flag and selected profile
- **Offline routing activation flow**: "Offline Navigation aktivieren" menu item → info sheet with benefits + download size hint (~200–250 MB) → Wi-Fi check → optional warning dialog → segment download with live progress overlay → 2.5 s success card
- **`OfflineRoutingUiState`** sealed class — `Disabled`, `Downloading` (with file index, total files, per-file byte counter), `DownloadComplete`, `Enabled`
- **Download progress overlay** — shows "Datei X von Y", per-file MB / total MB counter, filename, and `LinearProgressIndicator`
- **Success overlay** — brief green card after download completes ("Offline Navigation bereit!")
- **Wi-Fi warning dialog** — `AlertDialog` shown before download starts when device is not on Wi-Fi; user can proceed on mobile data or cancel
- **`WifiWarningDialog`** composable + `isWifiConnected()` utility function
- **`RoutingProfileSheet`** — bottom sheet to switch between the five profiles (with localised names and descriptions) or deactivate offline routing; wipes all `.rd5` segment files on deactivation
- **`NoInternetConnectionException`** domain exception mapped to `MapError.NoInternetConnection`
- **`MapError.BRouterProfilesMissing`** — shown when `.brf`/`lookups.dat` assets are absent
- **Localised profile names and descriptions** in all 8 languages (`profile_*_name`, `profile_*_desc`)
- **Duration formatting** — `MapNavigationOverlay` now shows "X Std. Y Min." / "X h Y min" instead of raw minutes; `duration_minutes`, `duration_hours`, `duration_hours_minutes` string resources added in all 8 languages

### Changed
- **`RoutingRepositoryImpl`** — respects `OfflineRoutingPreferences.isOfflineRoutingEnabled`; routes via BRouter when enabled and segments present, falls back to OSRM online otherwise
- **`RoutingRepositoryImpl.osrmFallbackRoute()`** — OSRM duration replaced with distance ÷ 15 km/h for realistic cycling travel time
- **`BRouterSegmentManager.downloadSegmentsForLocation()`** — reduced from 3×3 tile grid (~1.1 GB) to single primary tile (~200–250 MB)
- **`app/build.gradle.kts`** — `fileTree("libs/*.jar")` dependency added for BRouter JAR; `buildConfig = true` enabled

---

## [1.0.6] — 2026-06-08

### Added
- **Germany-wide coverage** via pre-bundled OSM SQLite asset — full PR #32 consolidating all Germany-wide parking data features into the release branch

### Fixed / Refactored
- Remove redundant `GeoCoordinate` wrapping in `MapViewModel.startInAppNavigation()` (location is already a `GeoCoordinate`)
- Replace `Math.pow()` with idiomatic Kotlin `pow()` in `MapCameraAnimator`; use `kotlin.math.abs` import instead of fully qualified form
- Remove duplicate `activeNavigationMarkerIcon` in `MainMapScreen` (identical to `selectedMarkerIcon`); pass `selectedMarkerIcon` directly for the active navigation slot
- Remove stale KDoc comment in `SelectedSpaceSheet` describing the old geo-intent redirect
- Fix import ordering in `NetworkModule` (android.* before third-party); narrow `LenientJsonAdapterFactory.create()` return type to non-null `JsonAdapter<*>`
- Remove unused `getFavoritesCount()` from `FavoritesRepository` interface

---

## [1.0.5] — 2026-06-08

### Added
- **In-app bike navigation** with OSRM route calculation and route polyline overlay on the map
- Navigation status card with route loading, distance/time summary, and stop action
- Favorites now provide two explicit actions: start navigation and show spot details
- Localized navigation/favorites labels for all supported app languages
- **Navigation focus mode**: non-destination parking markers are shown smaller, lighter gray, and more transparent during active navigation
- **`GeoCoordinate`** domain model replacing raw `Double` pairs across the routing layer
- **Typed `MapError`** sealed class replacing raw string error messages in `MapUiState` and `NavigationUiState`
- **Shared UI action components** (`PrimaryActionButton`, `SecondaryActionButton`, `MetaInfoChip`, `SheetHeader`, `SpotInfoCard`, `DetailRow`) extracted for reuse across overlays and sheets
- **`MapScreenUiState`** extracted to manage bottom sheet visibility independently of data state
- Unit tests for `MapViewModel` in-app navigation states (loading / active / error)
- Complete Priority 4 test coverage: ViewModel, camera animator, permission flow
- Dark mode preference persisted across restarts via `DarkModePreferences`

### Changed
- `MapViewModel` refactored from direct `MapView` manipulation to a `MapCameraTarget` state approach
- `MainMapScreen` split into smaller composables for status overlay, menu card, and location FAB
- Active destination marker color aligned with selected marker highlight (orange)
- Favorites action layout adjusted to avoid compressed button text on smaller widths
- External Google Maps intent flow removed; routing stays in-app
- Build toolchain modernised: Kotlin 2.4.0, AGP 9.2.1 (Gradle 9.5.1), Hilt 2.59.2, KSP 2.3.9
- Migrated annotation processing from KAPT to KSP for Hilt and Room
- Renovate configuration tightened: global automerge disabled; only security updates automerge
- Renovate dependency updates: `accompanist-permissions` 0.13.0→0.37.3, AndroidX libraries, Compose BOM 2024→2026.05.01, Retrofit 2→3, OkHttp 4→5, Room 2.8.1→2.8.4, Gradle Wrapper 9.4.1→9.5.1, GitHub Actions updated
- Release automation hardened to avoid duplicate release creation

### Fixed
- Sonar maintainability issues resolved across map, data, and domain layers

---

## [1.0.4] — 2026-06-07

### Added
- **Parking space image display** in bottom sheet details with photo preview from available sources
- **Automatic image caching** with Coil for fast loading and offline access

---

## [1.0.3] — 2026-06-07

### Fixed
- CI: upgraded `action-gh-release` to v3 for GitHub Actions compatibility

---

## [1.0.2] — 2026-06-07

### Fixed
- CI: migrated all workflows to Node 24-ready action runtime

---

## [1.0.1] — 2026-06-07

### Added
- **Smooth map camera animations** with easing functions for natural zoom and pan transitions (~120–180 ms)
- **Selected marker highlighting** with orange pin color to distinguish the currently selected parking space
- **Vertical offset support** for better map positioning with bottom sheets open
- Real app screenshots added to GitHub Pages preview section
- GitHub Actions CI workflow with dedicated `ci-build` and `ci-test` checks for pull requests and main branch pushes
- Repository branch protection ruleset for `main` with required PR approval, review-thread resolution, linear history, and required status checks
- Security dependency update automation via Renovate (security-only automerge)

### Fixed
- Removed temporary AGP 9 compatibility flags (`android.newDsl=false`, `android.disallowKotlinSourceSets=false`) now superseded by Hilt 2.59.2

---

## [1.0.0] — 2026-06-06

### Added
- **Germany-wide OSM dataset** bundled as `app/src/main/assets/bike_parking_germany.db` (~20 MB, ~100 000 nodes) — replaces the previous Trier-only WFS/WMS data source
- **`scripts/extract_osm_parking.py`** — Python pipeline (pyosmium + sqlite3) to regenerate the database from a Geofabrik PBF; uses a C++-level `TagFilter` for fast extraction
- **`scripts/README.md`** — full documentation for the extraction pipeline
- **Viewport-based marker loading** — `BikeParkingRepository.getSpacesInBoundingBox()` queries only the visible map area; ViewModel debounces scroll/zoom events (300 ms)
- **Lazy Nominatim reverse geocoding** — address resolved automatically on first marker tap, written back to local DB and cached permanently
- **`NominatimApi`** (Retrofit) and **`NominatimReverseDto`** (Moshi) — typed REST client for `nominatim.openstreetmap.org/reverse`
- **Favorites system** backed by Room / SQLite
- **Favorites sheet** with direct navigation shortcuts
- **Current-location support** with map recenter action and location marker
- **Dark mode toggle** integrated into the in-app menu
- **Favorite-aware map markers** with a red visual state
- **8 languages** with persistent in-app language picker: German 🇩🇪 English 🇬🇧 French 🇫🇷 Italian 🇮🇹 Portuguese 🇵🇹 Luxembourgish 🇱🇺 Dutch 🇳🇱 Spanish 🇪🇸
- **`MapViewModel.favoriteSpaces`** — full space objects for all favorited locations, resolved independently of the current viewport
- Room schema exported to `app/schemas/` (`exportSchema = true`)
- `ATTRIBUTIONS.md` — ODbL / OpenStreetMap attribution documentation
- GitHub Pages website in `docs/`
- Initial public repository setup on GitHub

### Changed
- Data source replaced: Trier Geoportal WFS/WMS → pre-bundled OpenStreetMap SQLite asset
- `BikeParkingDatabase` uses `Room.databaseBuilder().createFromAsset(...)` for first-launch seeding
- `NetworkModule` split Retrofit into two named instances (`@Named("osrm")` and `@Named("nominatim")`)
- `MainActivity` migrated to `AppCompatActivity` for full AppCompat locale support
- Local bike parking storage moved to SQLite / Room; temporary XML project-root files removed
- App launcher bike icon positioning refined

### Removed
- `TrierGeoportalApi` — WFS endpoint no longer used
- `BikeParkingGmlParser` — GML parsing no longer needed
- Sync-interval logic (`syncIntervalMs`, `lastSyncEpochMs`) — data is now static/bundled
