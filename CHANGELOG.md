# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project currently follows a simple chronological structure.

## [Unreleased]

### Added
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

