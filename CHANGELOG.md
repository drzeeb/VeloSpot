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
- Unit-Tests für `MapViewModel` (Laden, Favoriten, Kamera-Target, Permission-Flow)
- Unit-Tests für den GML-Parser inklusive Koordinaten-/Entity-Fälle

### Changed
- Project documentation expanded with updated setup, feature, and troubleshooting guidance
- GitHub Pages landing page updated to better reflect the current Android app experience
- Local bike parking storage moved to SQLite / Room instead of temporary XML-based project-root files
- Datenzugriff über neues Local-Data-Source-Interface entkoppelt (`BikeParkingLocalDataSource`)
- `MapViewModel` von direkter `MapView`-Steuerung auf `MapCameraTarget`-State umgestellt
- `BikeParkingGmlParser` auf JVM-testbare DOM-Verarbeitung (`DocumentBuilderFactory`) umgestellt
- `MainMapScreen` in kleinere Composables für Status-Overlay, Menü und Location-FAB aufgeteilt

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

