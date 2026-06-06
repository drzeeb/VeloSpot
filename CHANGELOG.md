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
- GitHub Pages and README updates describing the latest app features
- Unit tests for `MapViewModel` (loading, favorites, camera target, permission flow)
- Unit tests for the GML parser including coordinate and HTML entity edge cases
- **Multilingual support** with 8 languages: German 🇩🇪, English 🇬🇧, French 🇫🇷, Italian 🇮🇹, Portuguese 🇵🇹, Luxembourgish 🇱🇺, Dutch 🇳🇱, Spanish 🇪🇸
- Flag-based in-app language picker accessible from the top-right menu
- Persistent language preference — the selected language is saved and restored on every app start

### Changed
- Project documentation expanded with updated setup, feature, and troubleshooting guidance
- GitHub Pages landing page updated to better reflect the current Android app experience
- Local bike parking storage moved to SQLite / Room instead of temporary XML-based project-root files
- Data access decoupled via new `BikeParkingLocalDataSource` interface
- `MapViewModel` refactored from direct `MapView` manipulation to a `MapCameraTarget` state approach
- `BikeParkingGmlParser` switched to JVM-testable DOM parsing (`DocumentBuilderFactory`)
- `MainMapScreen` split into smaller composables for status overlay, menu card, and location FAB
- `MainActivity` migrated to `AppCompatActivity` for full AppCompat locale support

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

