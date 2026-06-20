# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- **Ride tracking — record your ride as a timeline ("My rides")** — riders can now record a ride and review it afterwards with full statistics:
  - **One-tap recording** from a dedicated red record/stop FAB stacked above the *My location* button (keeps the already-busy menu uncluttered). While recording, a compact live-stats card at the top of the map shows the running **time, distance and current speed**, with **Stop** and **Discard** actions, and the travelled track is drawn live on the map as a coloured polyline (new `velospot-track` source/layer).
  - **Automatic recording during navigation** — starting turn-by-turn navigation auto-starts a recording for the whole trip and saves it when navigation ends (or on auto-park arrival); a manually-started recording is never interrupted by navigation.
  - **Persistent history** in a dedicated, isolated Room database (`RidesDatabase` / `recorded_rides`, independent of the parking and saved-places stores) — a new *"My rides"* menu entry opens a timeline list of past rides (date, distance, duration, average speed).
  - **Ride detail with statistics + speed timeline** — tapping a ride redraws its track on the map and opens a sheet with moving time, max speed, elevation gain/loss and a Canvas-drawn **speed-over-time chart**, plus a delete action.
  - **Elevation support** — elevation gain/loss now comes from **BRouter's accurate terrain data** (the SRTM elevation baked into its `.rd5` segment files, read per route node — `RoutePoint.elevationMeters`) whenever a ride is recorded while navigating offline; the rider's live position is snapped to the route and its terrain elevation is fed to the tracker instead of the very noisy raw GPS altitude. For manual rides (or the online OSRM fallback, which carries no elevation) it falls back to GPS altitude, now low-pass filtered with a 3 m dead-band so a parked bike no longer racks up phantom metres (`GeoCoordinate.altitudeMeters`, pure unit-tested `RideTracker`). GPS altitude is requested whenever navigation **or** a recording is active. Fully localised across all eight supported languages and covered by new `RideTracker` unit tests.

### Changed
- **Favourites isolated in their own database (no more accidental wipes)** — user favourites used to share the asset-seeded `BikeParkingDatabase`, which is configured with `fallbackToDestructiveMigration`; any future parking-schema bump without an explicit migration would have silently dropped the favourites along with the parking data. Favourites now live in a dedicated, isolated `FavoritesDatabase` (mirroring `SavedPlacesDatabase` / `RidesDatabase`) that a parking-data migration can never touch. Existing favourites are copied over once from the legacy table on first launch (`ATTACH … INSERT OR IGNORE … SELECT`, guarded by a one-time flag); the legacy parking-database schema is left byte-for-byte unchanged so the bundled SQLite asset still validates.
- **Per-API HTTP clients with appropriate timeouts** — the single shared `OkHttpClient` was split into dedicated, `@Named` clients in `NetworkModule`: a **Nominatim** client (carrying the new rate limiter), a **segments** client with a 5-minute read timeout for the 100 MB+ BRouter offline-routing downloads (so a slow-but-progressing download is no longer aborted at 30 s), and the default client for OSRM/everything else.
- **OSRM DTOs use Moshi code-gen adapters** — `OsrmRouteResponseDto` / `OsrmRouteDto` / `OsrmGeometryDto` now carry `@JsonClass(generateAdapter = true)`, matching the Nominatim DTOs (faster parsing, ProGuard-safe, no reflection fallback).
- **Single source of truth for the OSRM host** — the OSRM bicycle URL is no longer duplicated; `OsrmApi.getBikeRoute` takes a relative path resolved against the one base URL configured in `NetworkModule`, removing the constant-drift risk between `NetworkModule` and `RoutingRepositoryImpl`.
- **Faster ride-track drawing during recording** — the live ride polyline used to be rebuilt from scratch on every GPS fix (re-mapping the entire, ever-growing track — O(n²) work and N fresh `RoutePoint` allocations per ride), which churned the GC and triggered redundant recompositions on long rides. Since each fix appends exactly one point, the track now grows by a single incremental append. The route-elevation lookup was likewise changed from a full route rescan per fix to a cursor that resumes from the last match (amortised ~O(1)), so accurate BRouter terrain elevation no longer costs an O(route) scan on every fix.
- **`MapViewModel` split into focused controllers (Single Responsibility)** — the ~1170-line map view-model that mixed address search, offline routing, ride tracking, saved places and the parked bike was decomposed into five small, independently testable controllers (`AddressSearchController`, `OfflineRoutingController`, `RideTrackingController`, `SavedPlacesController`, `ParkedBikeController`). Each owns its own state; the view-model now orchestrates and re-exposes their flows, and cross-feature effects (camera, selection reset, location-accuracy, toasts, geocoding) are passed as callbacks. Pure internal refactor — the public view-model API and all exposed UI state are unchanged.

### Removed
- **Dead Overpass live-fetch code** — the unused `OverpassApi`, `OverpassDto` and `OverpassMapper` (leftovers from the pre-bundled-database era), plus the never-called `writeSpaces`/`readSpaces`/`lastSyncEpochMs` cache methods and the now-orphaned `toEntity`/`toEntities` mappers. Parking data is served read-only from the bundled SQLite asset.

### Fixed
- **Nominatim rate limiting (OSM usage policy)** — a new `NominatimRateLimitInterceptor` on the dedicated Nominatim client enforces the required ≤ 1 request/second, so rapid user actions (e.g. tapping several parking pins to resolve addresses) can no longer burst past the limit and risk an IP ban.
- **OSRM HTTP errors handled gracefully** — `OsrmApi.getBikeRoute` now returns `Response<…>`; the fallback routing surfaces 4xx/5xx as a `RoutingFailedException` instead of throwing a raw exception.

## [v1.0.18] - 2026-06-19

### Added
- **More countries — France 🇫🇷 and Luxembourg 🇱🇺 bike-parking coverage** — the bundled OSM parking dataset is no longer Germany-only. Two additional pre-built SQLite assets (`app/src/main/assets/bike_parking_france.db`, `bike_parking_luxembourg.db`, generated by the same `scripts/extract_osm_parking.py`) are now merged into the single on-device database, so the map's bounding-box queries transparently return parking spots in these countries too. Implemented as a data-only Room migration (`BikeParkingDatabase` v3 → v4) that bulk-imports the extra datasets into the Germany-seeded `bike_parking_spaces` table on first open / app upgrade — preserving existing favorites and Germany data (`INSERT OR IGNORE`, OSM element IDs are globally unique).
- **Multi-country address search with home-country bias** — the Nominatim forward-geocoding search now covers all bundled countries (`countrycodes=de,fr,lu`) instead of Germany only. When the user's location is known, results are biased toward their surroundings via a `viewbox` (≈ 110 km half-span) with `bounded=0`, so matches in the country the user is currently in rank first without excluding the other countries (`NominatimApi.search`, `NominatimGeocoder.searchAddress`, `MapViewModel.onSearchQueryChanged`).
- **About screen** — a new *"About"* entry in the menu opens a sheet showing the app name, a link to the website (https://velospot.app), the per-country dataset status (Germany 08.08.2026, France & Luxembourg 18.06.2026), a link to the privacy policy (https://velospot.app/privacy) and a *"Buy me a coffee"* support link (https://buymeacoffee.com/velospot, opened externally in the browser — no in-app billing, compliant with both Play Store and F-Droid). Fully localised across all eight supported languages (`AboutSheet`).
- **Keep the screen awake during navigation** — while a route is being navigated the display no longer dims or locks; the `keepScreenOn` flag is set on the map view for the duration of active navigation and cleared automatically when navigation ends (`MainMapScreen`).
- **"Where did I park my bike?" — remember your parked-bike location** — a new feature lets riders save exactly where they left their bike and find it again later:
  - **Park your bike** from the map menu (*"Park bike here"* drops the marker at your current GPS position) or from any tapped custom pin (a *"Park bike here"* action in the pin sheet), with a confirmation toast.
  - **Persistent amber bike marker** — a distinctive amber pin carrying a white bike glyph (`createParkedBikeIcon()`, new `velospot-parked-bike` source/layer/image) stays on the map across app restarts until you collect the bike, clearly distinct from the green saved-place star, blue custom pin and red search pin.
  - **Find-my-bike sheet** — tapping the marker (or the menu entry, which then reads *"My parked bike"*) opens a sheet showing how long ago you parked, the reverse-geocoded address, and the live distance from your current position, with **Navigate to my bike** (full in-app routing) and **I picked up my bike** (clears the marker) actions.
  - **Lightweight, isolated persistence** — exactly one parked bike is stored at a time in its own `SharedPreferences`-backed store, exposed reactively (`ParkedBikeRepository` + impl, new domain model `ParkedBike`, Hilt provider and `MapViewModel` wiring: `parkBikeAtCurrentLocation`, `parkBikeAt`, `showParkedBike`, `navigateToParkedBike`, `pickUpBike`). Fully localised across all eight supported languages and covered by new `MapViewModel` unit tests.
  - **Auto-park on arrival** — when you navigate to a genuine bike parking spot, reaching it is detected automatically (the live route progress drops below a 25 m arrival radius) and the bike is parked at the destination without any extra tap, ending navigation and dropping the persistent marker with an *"arrived — bike parked here"* confirmation (`maybeAutoParkOnArrival` in `MapViewModel`, reusing the existing `NavigationManager` route-progress tracking; only real map parking spots auto-park — synthetic destinations like custom pins, address-search results, saved places and the parked bike itself never do). Covered by two new unit tests.
  - **Bigger, more legible parked-bike pin** — the amber marker icon was scaled up (~18 %) so it reads clearly at a glance, and the parked-bike detail sheet's action buttons now match the standard button height used across the saved-place and custom-pin sheets (the bike-rack detail sheet's previously taller buttons were aligned to the same default).
  - **No more pin overlap at the parked spot** — when the bike is parked on a real parking spot, that spot's marker is now hidden so only the single amber parked-bike pin shows (the spot reappears once the bike is picked up), and the parked-bike pin takes click priority during hit-testing — so tapping it always opens the *"my parked bike"* sheet (with parked-ago time, distance, navigate and *"I picked up my bike"*) instead of falling through to the underlying parking-spot sheet. Implemented via a location match in `buildParkingFeatures` (`isParkedAt`, ~12 m radius) and a reordered `queryRenderedFeatures` chain in `MapInitializer`.
  - **Consistent dismiss action on the parking-spot sheet** — the bike parking-spot detail sheet (`SelectedSpaceSheet`) now offers a bottom *"Remove pin"* text button that closes the sheet, mirroring the custom-pin and address-search sheets so dismissing a marker's detail view is consistent across the whole map.
- **Marker clustering for the parking layer — major map performance boost** — at city-level zoom the ~100 000 bike-parking markers are now aggregated into native MapLibre clusters instead of being drawn as thousands of overlapping individual symbols. The parking `GeoJsonSource` is created with clustering enabled (`clusterMaxZoom = 13`, `clusterRadius = 60`); the existing icon layer is filtered to non-clustered points (`!has("point_count")`), and two new layers render the cluster bubble (`CircleLayer` with a step-scaled radius) and its count label (`SymbolLayer`, `point_count_abbreviated`, "Noto Sans Bold"). Tapping a cluster animates the camera to the source's `getClusterExpansionZoom`, so it smoothly breaks apart. The currently selected spot and the active navigation destination are rendered on a dedicated **non-clustered highlight layer** (`velospot-parking-highlight-*`) so they always stay visible on top, never disappearing into a cluster. This drastically reduces the number of rendered symbols when panning/zooming dense areas. Implemented in `MapStyleLayers.kt`, `MapMarkerRenderer.kt` (new `ClusterRenderStyle`, split bulk/highlight feature building) and the click handling in `MapInitializer.kt`.
- **Live 3D turn-by-turn navigation** — navigation is now a real, mitlaufende 3D experience similar to Google Maps, driven by a new self-contained `NavigationManager` and a pure, unit-tested `core/navigation` package:
  - **3D follow camera** — once navigation starts the camera centres on the live GPS position with a fixed 60° pitch, a speed/turn-dependent zoom (closer at standstill / before turns, further out while cruising) and a bearing that smoothly follows the direction of travel. A `Choreographer` frame loop interpolates position, bearing, zoom and tilt every frame (frame-rate-independent exponential smoothing) so the motion stays ruckelfrei despite the ~3 s GPS cadence.
  - **Map matching (snap-to-route)** — each raw GPS fix is snapped onto the active BRouter polyline (`RouteMatcher`) so the heading arrow rides the road instead of jittering beside it; the location puck is a rotating navigation arrow (`IMG_LOCATION_NAV`) aligned to the live heading.
  - **Live route progress + ETA** — the navigation card now shows the dynamically shrinking remaining distance and a remaining-time estimate (ETA), recomputed on every fix; the already-travelled part of the route is greyed out while the remaining part stays in the theme colour (split `velospot-route` / `velospot-route-traveled` line layers).
  - **Off-route detection & auto-reroute** — straying more than ~30 m from the route for several consecutive fixes triggers a silent BRouter recalculation from the current position to the destination (throttled, self re-arming), with an "off route – recalculating…" hint in the overlay.
  - **3D buildings** — a `fill-extrusion` layer pulls the OpenMapTiles building footprints into 3D (using `render_height` / `render_min_height`); enabled during navigation and for the 3D resting view.
  - **GPS heading + speed** — `GeoCoordinate` now carries optional `bearing` + `speedMetersPerSecond`, populated by both flavor `LocationRepositoryImpl`s, feeding the heading arrow and the speed-dependent zoom.
- **2D / 3D map view switch** — a new "Map view" entry in the menu opens a sheet with a segmented 2D/3D selector (animated preview tiles). The choice is persisted (`NavigationModePreferences`) and applied live to the **resting** map (flat north-up vs. 45° tilt + 3D buildings); after navigation ends the map returns to the saved perspective. Active navigation always uses the full 3D camera regardless of the setting.
- **GPS route simulator (debug)** — a debug-only "Simulate route" menu entry drives a synthetic GPS track along the active BRouter route (with bearing + speed, snap/off-route compatible), so the whole live-navigation pipeline can be tested from the couch without moving. Backed by a unit-tested `RouteSimulator`; real GPS updates are ignored while simulating.

### Fixed
- **Crash on first open with the multi-country database** — bumping the database to v4 made Room run its full post-migration `TableInfo` validation, which then failed because the `idx_parking_lat_lon` index present in the bundled assets was not declared on `BikeParkingSpaceEntity` (`Migration didn't properly handle: bike_parking_spaces`). The index is now declared on the entity so the expected schema matches the seeded database (and it speeds up the bounding-box queries).
- **Parking pins no longer get stuck at the wrong size** — the zoom-bucket-scaled parking marker icons (`IMG_NORMAL` … `IMG_MUTED_SELECTED`) were only registered on the style **once** (`registerIcons` guarded each `addImage` with `getImage(id) == null`), so zooming never updated their on-map pixel size — they stayed frozen at the size of the zoom level they were first registered at and only refreshed on a full style reload (e.g. a dark-mode toggle). That made the pins occasionally appear too large/small for the current zoom. The zoom-dependent icons are now re-registered in place on every icon-set change (matching the location dot), so they always match the current zoom level.
- **Address-search result now shows the full custom-pin sheet** — selecting an address from the search dropdown previously opened a reduced sheet that only offered "Navigate here". It now reuses the exact same card as a tapped custom pin, so a searched location also offers **Save as favourite** (with the naming dialog → persistent saved place) and **Remove pin** alongside navigation. The `CustomMapPinSheet` header/subtitle are now parameterised (the search variant shows the "Address" title without the tap-to-move hint); `MapViewModel` gains `saveSearchPinAsFavorite()`, and the now-redundant `SearchPinSheet` was removed.

---

## [v1.0.17] — 2026-06-16

### Fixed
- **F-Droid scanner: remove the bundled BRouter `brouter-routing-app` module** — the pinned `brouter-upstream` submodule now points to a VeloSpot fork of `abrensch/brouter` (BRouter `v1.7.9`) with the entire `brouter-routing-app` Android module deleted. That module bundled binary asset blobs (the `segments4` / `modes` ZIP archives), which F-Droid's repository scanner flagged as non-free/unexpected binaries. VeloSpot only compiles the on-device routing modules (`btools.router`, `.mapaccess`, `.util`, `.codec`, `.expressions`) from source and never builds the routing app, so the module — together with its now-dead references in `settings.gradle` and `brouter-server/build.gradle` — was removed at the source. This complements the earlier removal of the GitHub Packages publishing block, so the checked-out submodule tree no longer contains the GitHub Packages Maven URL or the routing-app binary archives. The `.gitmodules` branch/URL and the submodule pointer are updated accordingly.

---

## [v1.0.16] — 2026-06-16

### Changed
- **Remove unused GitHub Packages publishing from bundled BRouter** — the pinned `brouter-upstream` submodule now points to a VeloSpot fork of `abrensch/brouter` (BRouter `v1.7.9`) with the `publishing { ... }` block deleted from `buildSrc/src/main/groovy/brouter.library-conventions.gradle`. That block declared a GitHub Packages Maven repository via an unresolved `System.env.REPO` URL, which F-Droid's repository scanner flagged as an *unknown maven repo*. It is only used to publish the library to GitHub Packages and is not needed to build the app, so it (and the now-unused `maven-publish` plugin) was removed at the source instead of being hidden behind a `scanignore`. The fork is source-identical to upstream `v1.7.9` apart from this buildSrc-only change; the `.gitmodules` URL/branch and the submodule pointer are updated accordingly.
- **Battery: power-aware location strategy** — the app no longer keeps the GPS running at full `HIGH_ACCURACY` every 5 s for the whole session. While the user is just browsing the map it now uses a battery-friendly **balanced-power mode** (15 s interval, 20 m minimum displacement, `PRIORITY_BALANCED_POWER_ACCURACY` on Google Play / relaxed `LocationManager` cadence on F-Droid); precise, frequent GPS fixes (3 s / 5 m, `PRIORITY_HIGH_ACCURACY`) are requested **only during active turn-by-turn navigation** and dropped again when navigation ends. In addition, location updates are now fully **stopped when the app goes to the background** and re-armed on return, so the GPS radio no longer drains the battery while the app is not visible. Implemented across the shared `LocationRepository` (new `startLocationUpdates(highAccuracy)`), both flavor `LocationRepositoryImpl`s, and `MapViewModel`/`MainMapScreen` lifecycle wiring.
- **Release workflow: validate the new changelog-promotion prompt** — verifies that the release pipeline correctly promotes the `## [Unreleased]` section to a dated `## [v1.0.15]` heading and re-creates an empty `## [Unreleased]` section above it.

### Fixed
- **Reproducible build: disable embedded VCS info** — the Android Gradle Plugin embeds the current Git commit hash into `META-INF/version-control-info.textproto` at build time. This was the only file that still differed between F-Droid's rebuild and the signed release APK, breaking byte-for-byte reproducibility. Added `vcsInfo { include = false }` to the `release` build type in `app/build.gradle.kts`, so the file is no longer written into the APK and the F-Droid build is now fully reproducible.

---

## [v1.0.14] — 2026-06-16

### Changed
- **BRouter is now built from source — fully reproducible, no binary blob, no prebuild** — the bundled `app/libs/brouter-1.7.9-all.jar` is removed. BRouter is now compiled from source by a new `:brouter` Gradle module that builds the on-device routing modules (`btools.router`, `.mapaccess`, `.util`, `.codec`, `.expressions`) from the pinned `brouter-upstream` git submodule (BRouter `v1.7.9`). A plain `./gradlew :app:assembleFdroidRelease` now resolves BRouter without any preparation scripts. This eliminates the F-Droid custom `prebuild` step and `BRouter` `srclib`; the `fdroiddata` recipe simplifies to `submodules: true` + `gradle: [fdroid]`. The ProGuard rules `-dontwarn java.awt.**` / `-dontwarn javax.imageio.**` (previously appended by the prebuild) are now committed in `app/proguard-rules.pro`.
- **Reproducible release setup** — release signing now reads from a gitignored `keystore.properties` or CI env vars (`KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`); secrets are never committed. The release workflow checks out submodules and publishes the F-Droid APK under the stable name `VeloSpot-v<versionName>.apk` (matching the fdroiddata `Binaries` URL). See [`docs/RELEASING.md`](docs/RELEASING.md).

---

## [v1.0.13] — 2026-06-16

### Fixed
- **F-Droid: strip the AGP "Dependency metadata" signing block from the APK** — the Android Gradle Plugin embeds a Google-signed *Dependency metadata* block into the release APK's signing section. F-Droid's `scanner` / *Check App* job rejects this opaque, non-reproducible block (`CRITICAL: Found extra signing block 'Dependency metadata'`), failing the pipeline. Added a `dependenciesInfo { includeInApk = false; includeInBundle = true }` block to `app/build.gradle.kts` so the block is no longer written into the APK that F-Droid scans, while it is kept in the AAB for Google Play's upload-time processing.

---

## [v1.0.12] — 2026-06-16

### Changed
- **BRouter upgraded 1.6.3 → 1.7.9** — the bundled offline-routing engine (`app/libs/brouter-1.7.9-all.jar`) is updated to BRouter 1.7.9. The JAR is a **slimmed archive** rebuilt from the official `brouter-1.7.9.zip` release that contains only the on-device routing modules (`btools.router`, `.mapaccess`, `.util`, `.codec`, `.expressions`); the server/map-creation modules and their protobuf/osmosis dependencies (~2 MB of the upstream `-all.jar`) are intentionally stripped, since they are only used to build map data, not to route on-device. `BRouterEngine.kt` needed **no code change**: the used API (`RoutingEngine(String, String, File, List, RoutingContext)`, `doRun`, `getFoundTrack`, `RoutingContext.localFunction`, `OsmNodeNamed`, and the reflection-read private `OsmPathElement.ilat/ilon` fields) is fully present in 1.7.9. The bundled routing profiles and `lookups.dat` in `app/src/main/assets/brouter/profiles/` (`trekking`, `fastbike`, `gravel`, `mtb`, `shortest`) are refreshed to the 1.7.9 versions from the same release. F-Droid's `srclib` (`BRouter@1.6.3` → `BRouter@1.7.9`) and the `prebuild` step in the `fdroiddata` recipe (`metadata/de.velospot.yml` + `srclibs/BRouter.yml`) must be bumped to 1.7.9 accordingly.
- **Release workflow auto-promotes the changelog** — the release workflow now turns the `## [Unreleased]` section into a dated `## [vX.Y.Z] — YYYY-MM-DD` heading and inserts a fresh, empty `## [Unreleased]` section above it. The change is committed and a sync PR is opened back to `main`. The step is idempotent: if a heading for the released version already exists (e.g. promoted manually), it does nothing.

---

## [v1.0.11] — 2026-06-16

### Added
- **Map layer toggles** — a new "Layers" entry in the menu opens a sheet where the user can show/hide three pin categories independently: **parking spots**, **favourites** and **saved places**. Each layer is a tappable card with a coloured pin badge and a switch (active cards tinted in the layer's accent colour). All layers are visible by default; changes are persisted across restarts via `LayerVisibilityPreferences`. The selected spot and the active navigation destination always stay visible regardless of the toggles. Filtering happens in `buildParkingFeatures` (parking vs. favourites by favourite-state) and on the saved-places source.
- **Dark map tiles** — the map now switches to a dark vector-tile style when dark mode is enabled. The dark style (`app/src/main/assets/map_style_dark.json`) reuses the very same OpenFreeMap vector tiles (OpenMapTiles schema) as the light `liberty` style, so no extra tile provider, API key or tracking dependency is introduced. Toggling dark mode in the menu re-loads the style live without resetting the camera.
- **Theme-aware map markers** — `defaultMarkerStyleConfig(isDarkTheme)` now returns a brighter, higher-contrast marker/route palette for the dark style (normal pin `#3B82F6` instead of the dark navy `#0A2A66`, which previously blended into the dark water colour; favourite `#F44336`, selected `#FFB300`, route `#42A5F5`). The light style keeps its original colours unchanged. Marker icons are regenerated on theme switch.
- **One-time startup centering** — on launch the map now centres on the user's current position once, as soon as the first GPS fix arrives (`hasCenteredOnStartup` guard in `MapViewModel.observeUserLocation()`). Subsequent location updates no longer move the camera, so the user stays in control after the initial centering.
- **Save custom pins as named favourites** — a manually placed map pin can now be saved as a named favourite via a new "Save as favourite" action in the custom-pin sheet (with a naming dialog). Saved places:
  - persist in a **dedicated, isolated Room database** (`SavedPlacesDatabase` / `saved_places` table) so they are completely independent of the asset-seeded parking database and its destructive migrations — a schema change to one can never wipe the other
  - render as **persistent green star markers** on the map (`createSavedPlaceIcon()`, new `velospot-saved-pin` source/layer); tapping one opens a sheet to navigate there or remove it
  - appear in a **"Saved places" section** in the favourites sheet with navigate and delete actions
  - new domain model `SavedPlace`, `SavedPlacesRepository` (+ Room-backed impl), DAO, Hilt providers, and `MapViewModel` wiring (`saveCustomPinAsFavorite`, `selectSavedPlace`, `navigateToSavedPlace`, `removeSavedPlace`)
  - fully localised in all 8 supported languages

### Changed
- **`MainMapScreen` map initialisation refactored** — one-time map setup (compass, initial camera, viewport/zoom/click listeners) is now separated from style loading. Style loading runs in its own `LaunchedEffect(maplibreMap, isDarkTheme)` and re-applies the correct light/dark style whenever the theme changes; a `styleVersion` counter re-runs the marker rendering effect so all custom sources/layers/images are rebuilt after a style reload
- **Favourites counter includes saved places** — the menu favourites count now sums bike-parking favourites **and** saved custom places (`favorites.size + savedPlaces.size`)
- **Explicit "Save as favourite" button on parking spots** — the parking detail sheet (`SelectedSpaceSheet`) now shows a full-width "Save as favourite" / "Remove from favourites" button (matching the custom-pin sheet) instead of the small corner heart toggle
- **Favourites list uses a delete icon** — the remove action in the favourites list now uses a trash/`Delete` icon (consistent with the saved-places cards) instead of the filled heart
- **"Show spot" for saved places** — saved-place cards in the favourites list now offer a "Show spot" action (alongside "Start navigation") that centres the map on the place and opens its detail sheet
- **Modernised adaptive app icon** — redesigned launcher icon for a fresh 2026 look: a vibrant green gradient background with a bold white location pin and brand-green bicycle; foreground, background and Android-13 themed (monochrome) layers all updated

### Refactored
- **`feature/map/presentation` split into sub-packages** for clarity: rendering infrastructure moved to `presentation/markers/` (`MapMarkerRenderer`, `MarkerIconFactory`, `MapStyleLayers`, `MarkerStyleConfig`) and all modal sheets/dialogs moved to `presentation/sheets/` (`MapBottomSheets`, `FavoritesSheet`, `LayersSheet`, `SavedPlaceSheet`, `SelectedSpaceSheet`, `CustomMapPinSheet`, `SearchPinSheet`, `LanguageSheet`, `OfflineRoutingSheet`, `SavePlaceDialog`). The root package keeps the core screen, view model, UI state, overlays, shared components and lifecycle helpers. The shared `BikeParkingType.label` helper moved to `MapUiActionComponents`.
- **`MapMarkerRenderer.kt` split by concern** into three files: `MarkerIconFactory.kt` (pure pin/icon drawing), `MapStyleLayers.kt` (MapLibre source/layer/image IDs + idempotent registration helpers) and `MapMarkerRenderer.kt` (state→GeoJSON orchestration only)
- **`MainMapScreen.kt` slimmed down** by extracting `MapBottomSheets.kt` (all modal sheets/dialogs, collecting their own state from the ViewModel) and `MapInitializer.kt` (the imperative one-time MapLibre setup — compass, initial camera, viewport/zoom/click listeners — plus the map style URLs); behaviour is unchanged

### Docs
- **README, GitHub Pages site and `docs/` updated** with the new features (dark map tiles, map layer toggles, saved places) and two new screenshots (`dark-mode.jpeg`, `layers.jpeg`); the README gains a screenshots gallery

### Fixed
- **Bike marker glyph washed out on lighter pins** — the bike symbol inside the parking markers was drawn as a colour **emoji** (`🚲`), whose tint could not be controlled, so it rendered greyish/blurry on the brighter dark-mode pins. Replaced with a crisp white vector drawable (`ic_bike_marker.xml`, Material `directions_bike`) tinted via `DrawableCompat`, giving a sharp, high-contrast glyph on every pin colour in both light and dark mode (including the dimmed navigation markers)

---

## [v1.0.10] — 2026-06-15

### Added
- **CI: Gradle Wrapper Validation workflow** — verifies the `gradle-wrapper.jar` checksum against official Gradle releases on every push/PR (supply-chain hardening)
- **CI: CodeQL security scanning** — workflow added but currently **disabled for automatic runs** (manual dispatch only): CodeQL's Kotlin extractor does not yet support Kotlin 2.4.x (rejects ≥ 2.3.30 with a build, extracts no Kotlin sources with `build-mode: none`). The build-based config is kept ready to re-enable once CodeQL supports Kotlin 2.4
- **CI: Android Lint workflow** — runs `lintFdroidDebug` on every push/PR and uploads the HTML/XML report as a build artifact
- **CI: Dependency Review workflow** — fails PRs that introduce dependencies with known high-severity vulnerabilities or disallowed licenses
- **README: live pipeline status badges** — CI, Release workflow and Android Lint status badges

### Changed
- **F-Droid: BRouter is now built from source** — the pre-built `app/libs/brouter-1.6.3-all.jar` is no longer shipped as a binary blob in the F-Droid build; instead it is rebuilt from the official BRouter source code via an srclib (`BRouter@1.6.3`) and a `prebuild` step configured in the `fdroiddata` recipe (`metadata/de.velospot.yml` + `srclibs/BRouter.yml`). Local and `googlePlay` builds keep using the committed JAR.

### Fixed
- **Missing translations completed** — added the `custom_pin_*` strings to all 6 non-default locales (es, fr, it, lb, nl, pt) and the `error_*` strings to Italian, resolving the 10 `MissingTranslation` lint errors that broke the Android Lint CI job
- **MapView leak** — `rememberMapViewWithLifecycle` now calls `onDestroy()` in `onDispose`, releasing the native renderer, GL context and map listeners even when the composable leaves composition before the Activity is destroyed
- **Location callback** — the `googlePlay` `LocationRepositoryImpl` resets `locationCallback` to `null` after `removeLocationUpdates` (no double removal, reference is released)
- **F-Droid build hardening** — removed the `foojay-resolver-convention` plugin from `settings.gradle.kts` **and** the `gradle/gradle-daemon-jvm.properties` daemon-JVM pinning (which forced a JetBrains JDK 21 toolchain download via Foojay). The Gradle daemon now runs on the invoking JDK (CI: 17, local: developer's JDK); the app still targets Java 11. This also fixes CI builds failing with `Unable to download toolchain ... vendor=JetBrains`

---

## [v1.0.9] — 2026-06-12

### Added
- **Tap-to-place custom map pin** — tap any empty location on the map to drop a blue pin marker at that exact point; the map camera animates to the tapped position automatically
- **`CustomMapPinSheet`** — bottom sheet that opens immediately when a custom pin is placed; shows the reverse-geocoded address from Nominatim (with a loading spinner and raw coordinates as fallback while the request is in-flight); provides a "Navigate here" primary action and a "Remove pin" secondary action
- **Reverse geocoding on tap** — `MapViewModel.onMapTapped()` fires a `NominatimGeocoder.reverseGeocode()` coroutine in the background; the resolved address replaces the coordinate placeholder as soon as the response arrives
- **Custom pin navigation** — "Navigate here" starts BRouter in-app bike routing directly to the tapped coordinates; the pin stays visible on the map as a route end-point marker for the entire duration of navigation and is automatically removed when navigation is stopped
- **Blue custom pin icon** — new `createCustomPinIcon()` function in `MapMarkerRenderer` renders a distinct blue dropped-pin (Material Blue 700 `#1565C0`) to visually separate the tap pin from the red address-search pin and the bike-rack parking markers
- **Dedicated GeoJSON source and layer** (`velospot-custom-pin-source` / `velospot-custom-pin-layer`) for the custom pin, following the same MapLibre `SymbolLayer` pattern as existing markers
- **`customMapPin` and `customMapPinAddress` StateFlows** in `MapViewModel` to expose pin position and its resolved address to the UI layer independently
- **Zoom-out parking marker visibility** — bike parking markers are hidden below zoom level 11 via MapLibre's native `minZoom` on the parking layer (GPU-side, no state update needed); a Toast notification informs the user when they zoom out too far
- **`MIN_ZOOM_PARKING_VISIBLE` constant** (`= 11f`) in `MapMarkerRenderer` controls the visibility threshold and is shared with `MainMapScreen` for the Toast trigger
- **Profile change recalculates active route** — switching the offline routing profile while navigation is active immediately triggers a new route calculation to the same destination with the new profile
- **Navigation job cancellation** — starting a new navigation while a route is still being computed cancels the previous calculation immediately, preventing race conditions when the user taps quickly between spots
- **F-Droid build flavor** — new `fdroid` product flavor that replaces Google's `FusedLocationProviderClient` with Android's standard `LocationManager`; the `googlePlay` flavor retains the existing GMS-based implementation; `play-services-location` dependency is scoped to `googlePlayImplementation` only
- **F-Droid store listing** — full fastlane metadata for `de-DE` and `en-US` locales: `title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/10000.txt`, phone screenshots
- **F-Droid build metadata** — `metadata/de.velospot.yml` with `scanignore` for the BRouter JAR, `AutoUpdateMode: Version`, `UpdateCheckMode: Tags`, and versionCode formula (`X * 10000 + Y * 100 + Z`)
- **BRouter LICENSE file** — `app/libs/brouter-LICENSE.txt` documents the MIT license and source URL of the bundled JAR for F-Droid reviewers
- **Release signing via environment variables** — `signingConfigs.release` in `app/build.gradle.kts` reads `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` from the environment; local builds fall back to the debug key automatically
- **Version injection from Git tag** — `versionCode` and `versionName` are now Gradle-property-injectable (`-PversionCode`, `-PversionName`); the release workflow computes `versionCode = X * 10000 + Y * 100 + Z` from the tag
- **GitHub Pages screenshots** — added `searchbar.jpeg` and `found-location.jpeg` to the App Preview section; all screenshot `alt` texts updated to English

### Changed
- `MainMapScreen` map-click listener now **always consumes the tap event** (`return true`) — clicking an empty area places a custom pin instead of being silently ignored
- Selecting a parking space or a Nominatim search result **dismisses any active custom pin** and vice versa, ensuring only one pin type is visible at a time
- `stopInAppNavigation()` only clears the custom pin when the destination was the custom pin itself; navigating to a parking space or address does not affect the custom pin state
- **`release.yml` workflow rewritten** — builds both `fdroid` and `googlePlay` release APKs from a single job; version is extracted from the Git tag; release body includes a bilingual APK variant table and sideload instructions; F-Droid fastlane changelog files are auto-committed after each release
- **`security-release.yml` workflow updated** — same two-flavor APK build and version-code formula as `release.yml`; section headings translated to English
- **`ci.yml` workflow updated** — `ci-build` now assembles `assembleFdroidDebug` (the canonical open-source build); APK artifact upload path updated for the new flavor directory structure; unit tests run against `testFdroidDebugUnitTest`
- **`NetworkModule`** — `provideFusedLocationClient` and `provideLocationRepository` extracted from the shared module into flavor-specific `LocationModule` Hilt modules (`src/googlePlay/…` and `src/fdroid/…`)
- **`ATTRIBUTIONS.md`** fully rewritten in English; Trier Geoportal and OSMDroid entries removed; BRouter, MapLibre, Coil, OSRM, OpenFreeMap, and all current libraries added with correct licenses and source links
- All German code comments translated to English across `build.gradle.kts`, workflow YAML files, `MapViewModel.kt`, `MainMapScreen.kt`, `MapMarkerRenderer.kt`, and all `strings.xml` files
- `zoom_in_for_parking` string resource added in all 8 supported languages (DE, EN, FR, IT, PT, LB, NL, ES)
- **`MapViewModel`** — `ID_CUSTOM_MAP_PIN` and `ID_ADDRESS_SEARCH_PIN` extracted as `companion object` constants; all usages in `MapViewModel` and `MainMapScreen` updated
- **`RoutingRepositoryImpl`** — OSRM base URL extracted to `OSRM_BICYCLE_BASE_URL` private constant
- **`MapMarkerRenderer.registerIcons()`** — location icons now updated via direct `style.addImage()` (MapLibre replaces in-place) instead of a `removeImage` + null-checked add on every call, eliminating unnecessary GPU work on each GPS position or zoom update

### Fixed
- **F-Droid `LocationRepositoryImpl` listener leak** — `startLocationUpdates()` now calls `stopLocationUpdates()` first, matching the Google Play implementation; previously calling start twice (once at init, once after permission grant) left the old `LocationListener` orphaned in `LocationManager`
- **Viewport reload errors silently dropped** — when a parking-data refresh fails after the initial load, a short Toast is now shown (`error_loading_parking` string in all 8 languages) instead of discarding the error; the existing map data remains visible

### Removed
- **`osmdroid`** version and library entries removed from `libs.versions.toml` — OSMDroid was replaced by MapLibre in v1.0.8; the catalog entries were never cleaned up
- **`accompanistPermissions`** version and library entries removed from `libs.versions.toml` — the library was superseded by manual permission handling and was never referenced in any build file

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
