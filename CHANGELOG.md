# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project currently follows a simple chronological structure.

## [Unreleased] — BRouter Offline Routing

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
- **`NoInternetConnectionException`** domain exception — thrown by `BRouterSegmentManager.downloadSegment()` when DNS resolution, connection, or socket errors occur; mapped to new `MapError.NoInternetConnection`
- **`MapError.NoInternetConnection`** — dedicated error state with localised message in all 8 languages
- **`MapError.BRouterProfilesMissing`** — shown when `.brf`/`lookups.dat` assets are absent, with clear instructions
- **Localised profile names and descriptions** in all 8 languages (`profile_*_name`, `profile_*_desc`)
- **Duration formatting** — `MapNavigationOverlay` now shows "X Std. Y Min." / "X h Y min" instead of raw minutes (e.g. "2 Std. 14 Min." instead of "134 min"); `duration_minutes`, `duration_hours`, `duration_hours_minutes` string resources added in all 8 languages

### Changed
- **`RoutingRepositoryImpl`** — respects `OfflineRoutingPreferences.isOfflineRoutingEnabled`; routes via BRouter when enabled and segments are present, falls back to OSRM online when disabled or when destination is outside the downloaded tile
- **`RoutingRepositoryImpl.osrmFallbackRoute()`** — OSRM duration replaced with distance ÷ 15 km/h for realistic cycling travel time (OSRM's raw `duration` was calibrated on road speeds)
- **`MapViewModel`** — injected `@ApplicationContext context` and `BRouterSegmentManager`; `startInAppNavigation()` simplified (no longer triggers segment downloads inline); new offline routing methods: `requestOfflineRoutingSetup()`, `confirmOfflineRoutingSetup()`, `confirmDownloadOnMobileData()`, `startSegmentDownload()`, `selectRoutingProfile()`, `disableOfflineRouting()`
- **`MapMenuCardState`** — new `offlineRoutingUiState: OfflineRoutingUiState` field
- **`MapMenuCardActions`** — new `onActivateOfflineRouting` and `onOpenProfileSheet` callbacks
- **`MapOverlays.kt`** — offline routing menu entries added to dropdown (Disabled / Downloading / DownloadComplete / Enabled states); `OfflineSetupProgressOverlay` and `OfflineSetupSuccessOverlay` composables added; `formatMb()` helper
- **`MainMapScreen`** — collects `offlineRoutingUiState`, `showOfflineSetupSheet`, `showProfileSheet`, `showWifiWarning` states; renders `OfflineRoutingSetupSheet`, `WifiWarningDialog`, `RoutingProfileSheet`
- **`BRouterProfile`** enum fields changed from hardcoded `String` to `@StringRes Int` (`displayNameRes`, `descriptionRes`) — all UI uses `stringResource(profile.displayNameRes)` for full localisation
- **`BRouterSegmentManager.downloadSegmentsForLocation()`** — reduced from 3×3 tile grid (~1.1 GB) to **single primary tile** (~200–250 MB); progress callback extended with `fileIndex` and `totalFiles` parameters
- **`app/build.gradle.kts`** — `fileTree("libs/*.jar")` dependency added for BRouter JAR; `buildConfig = true` enabled
- Download size hints updated from "100–150 MB" to **"200–250 MB"** in all 8 language files

## [2026-06-08] 🇩🇪 Germany-Wide Coverage

### ✨ Highlight

VeloSpot now covers **all of Germany** with over **100 000 bicycle parking locations** from OpenStreetMap — replacing the previous Trier-only WFS/WMS data source. The app is fully offline-capable: all parking data is bundled as a pre-populated Room/SQLite asset and available instantly from first launch.

### Added
- **Germany-wide OSM dataset** bundled as `app/src/main/assets/bike_parking_germany.db` (~20 MB, ~100 000 nodes)
- **`scripts/extract_osm_parking.py`** — Python pipeline (pyosmium + sqlite3) to regenerate the database from a Geofabrik PBF at any time; uses a C++-level `TagFilter` for fast extraction (seconds instead of minutes)
- **`scripts/README.md`** — full documentation for the extraction pipeline including install instructions, runtime estimates, and OSM tag mapping table
- **Viewport-based marker loading** — `BikeParkingRepository.getSpacesInBoundingBox()` queries only the visible map area; the ViewModel debounces scroll/zoom events (300 ms) so performance stays smooth anywhere in Germany
- **`BikeParkingSpaceDao.getSpacesInBoundingBox()`** — spatial SQL query backed by a `(latitude, longitude)` index in the bundled SQLite database
- **`BikeParkingSpaceDao.getSpacesByIds()`** — used to resolve full `BikeParkingSpace` objects for favorited spots regardless of the current viewport
- **Lazy Nominatim reverse geocoding** — `BikeParkingRepository.resolveAddress()` is triggered automatically when a marker without an address is tapped; the resolved address is written back to the local database and shown immediately in the details sheet
- **`NominatimApi`** (Retrofit) and **`NominatimReverseDto`** (Moshi) — typed REST client for `nominatim.openstreetmap.org/reverse`
- **`NominatimGeocoder`** — coroutine-safe wrapper that parses Nominatim JSON into a compact `"Straße HNr, PLZ Stadt"` string
- **`BikeParkingLocalDataSource.updateAddress()`** and **`BikeParkingSpaceDao.updateAddress()`** — persist geocoded addresses back to the local DB
- **`MapViewModel.favoriteSpaces`** state — full `BikeParkingSpace` objects for all favorited locations, resolved independently of the visible viewport so the Favorites sheet always shows complete data
- Room schema exported to `app/schemas/` (`exportSchema = true`)
- `ksp { arg("room.schemaLocation", ...) }` added to `app/build.gradle.kts`

### Changed
- **Data source replaced**: Trier Geoportal WFS/WMS → pre-bundled OpenStreetMap SQLite asset
- **`BikeParkingRepository`** changed from `fun interface` with `getBikeParkingSpaces()` to a full interface with `getSpacesInBoundingBox()`, `getSpacesByIds()`, and `resolveAddress()`
- **`BikeParkingRepositoryImpl`** rewritten: all WMS/GML fetching removed; delegates entirely to `BikeParkingLocalDataSource` and `NominatimGeocoder`
- **`BikeParkingDatabase`** now uses `Room.databaseBuilder().createFromAsset("bike_parking_germany.db")` for first-launch seeding
- **`NetworkModule`** split Retrofit into two named instances (`@Named("osrm")` and `@Named("nominatim")`); removed `TrierGeoportalApi`, `BikeParkingGmlParser`, and `GeocoderNominatim` providers
- **`MapViewModel`** init now loads the default Trier viewport from local DB; `selectSpace()` triggers address resolution for markers without an address
- **`MainMapScreen`** MapListener now reports bounding-box changes on both scroll and zoom events via `viewModel.onViewportChanged()`; `FavoritesSheet` uses `favoriteSpaces` instead of the viewport-filtered `uiState` spaces
- `BoundingBox.DEFAULT` remains centred on Trier as the cold-start viewport

### Removed
- `TrierGeoportalApi` — WFS endpoint no longer used
- `BikeParkingGmlParser` — GML parsing no longer needed
- `BikeParkingRepositoryImpl` dependency on `geoportalApi` and `gmlParser`
- Sync-interval logic (`syncIntervalMs`, `lastSyncEpochMs`) — data is now static/bundled

### Added
- Navigation-focused marker dimming: while in-app navigation is active, non-destination parking markers are shown smaller, lighter gray, and more transparent
- Favorites system backed by Room / SQLite
- Favorites sheet with direct navigation shortcuts
- Current-location support with map recenter action and location marker
- Dark mode toggle integrated into the in-app menu
- Favorite-aware map markers with a red visual state
- **Smooth map camera animations** with easing functions for natural zoom and pan transitions (~120-180ms)
- **Selected marker highlighting** with orange pin color to distinguish currently selected parking spaces
- **Vertical offset support** for better map positioning with bottom sheets
- GitHub Pages and README updates describing the latest app features
- Unit tests for `MapViewModel` (loading, favorites, camera target, permission flow)
- Unit tests for the GML parser including coordinate and HTML entity edge cases
- **Multilingual support** with 8 languages: German 🇩🇪, English 🇬🇧, French 🇫🇷, Italian 🇮🇹, Portuguese 🇵🇹, Luxembourgish 🇱🇺, Dutch 🇳🇱, Spanish 🇪🇸
- Flag-based in-app language picker accessible from the top-right menu
- Persistent language preference — the selected language is saved and restored on every app start
- GitHub Actions CI workflow with dedicated `ci-build` and `ci-test` checks for pull requests and main branch pushes
- Repository branch protection ruleset for `main` with required PR approval, review-thread resolution, linear history, and required status checks
- **Parking space image display** in bottom sheet details with photo preview from available sources
- **Automatic image caching** with Coil for fast loading and offline access
- **In-app bike navigation** with OSRM route calculation and route polyline overlay on the map
- Navigation status card with route loading, distance/time summary, and stop action
- Favorites now provide two explicit actions: start navigation and show spot details
- Localized navigation/favorites labels for all supported app languages
- Additional `MapViewModel` unit tests for in-app navigation states (loading/active/error)

### Changed
- Project documentation expanded with updated setup, feature, and troubleshooting guidance
- GitHub Pages landing page updated to better reflect the current Android app experience
- README and GitHub Pages content now explicitly highlight in-app bike navigation flows
- Local bike parking storage moved to SQLite / Room instead of temporary XML-based project-root files
- Data access decoupled via new `BikeParkingLocalDataSource` interface
- `MapViewModel` refactored from direct `MapView` manipulation to a `MapCameraTarget` state approach
- `BikeParkingGmlParser` switched to JVM-testable DOM parsing (`DocumentBuilderFactory`)
- `MainMapScreen` split into smaller composables for status overlay, menu card, and location FAB
- `MainActivity` migrated to `AppCompatActivity` for full AppCompat locale support
- Release automation hardened to avoid duplicate release creation when security auto-release tags are pushed by workflows
- Renovate configuration tightened: global automerge disabled; only security-related updates are automerged
- Build toolchain modernised: Kotlin 2.4.0, AGP 9.2.1 (Gradle 9.4.1), Hilt 2.59.2, KSP 2.3.9
- Migrated annotation processing from KAPT to KSP for Hilt and Room
- Removed temporary AGP 9 compatibility flags (`android.newDsl=false`, `android.disallowKotlinSourceSets=false`) now that Hilt 2.59.2 fully supports the AGP 9 New DSL
- Force-resolved `kotlin-metadata-jvm:2.4.0` to allow Hilt to process Kotlin 2.4 class metadata
- Renovate PR `renovate/accompanistpermissions`: `accompanist-permissions` von `0.13.0` auf `0.37.3`
- Renovate PR `renovate/androidx`: AndroidX-Updates (`core-ktx` `1.16.0 -> 1.19.0`, `appcompat` `1.7.0 -> 1.7.1`, `lifecycle-runtime-ktx` `2.9.1 -> 2.10.0`, `activity-compose` `1.10.1 -> 1.13.0`, `navigation-compose` `2.9.0 -> 2.9.8`, Testlibs) und `compileSdk` auf `37` angehoben
- Renovate PR `renovate/gradle-wrapper`: Gradle Wrapper von `9.4.1` auf `9.5.1` (inkl. neue Wrapper-Property-Defaults)
- Renovate PR `renovate/hilt-and-dagger`: `androidx.hilt:hilt-navigation-compose` von `1.2.0` auf `1.3.0`
- Renovate PR `renovate/major-androidx`: Compose BOM von `2024.09.00` auf `2026.05.01`
- Renovate PR `renovate/major-networking`: `retrofit` `2.11.0 -> 3.0.0` und `okhttp` `4.12.0 -> 5.3.2`
- Renovate PR `renovate/room`: Room von `2.8.1` auf `2.8.4`
- Renovate PR `renovate/major-github-actions`: Workflow Actions aktualisiert (`actions/checkout` `v5 -> v6`, `actions/upload-artifact` `v4 -> v7`)
- Renovate PR `renovate/major-agp`: AGP-9-Migrationsstand dokumentiert (KSP-Migration, Gradle-Wrapper- und Build-Config-Anpassungen im Branch)
- README aktualisiert: Anforderungen auf Android SDK 37+ und Target API 37 angehoben
- External Google Maps intent flow removed from primary navigation action; routing now stays in-app
- Active destination marker color aligned with selected marker highlight (orange)
- Favorites action layout adjusted to avoid compressed button text on smaller widths

## [2026-06-06]

### Added
- Initial public repository setup on GitHub
- GitHub Pages website in `docs/`
- ODbL / OpenStreetMap attribution documentation
- English code comments across the project

### Changed
- App launcher bike icon positioning refined
- Build toolchain updated to AGP 8.13.2 and Gradle 8.13

### Removed
- Temporary XML files from the project root

