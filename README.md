# 🚲 VeloSpot

**Find bike parking spaces across all of Germany**

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-0A2A66)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
![Map](https://img.shields.io/badge/map-MapLibre-3887BE)
![License](https://img.shields.io/badge/license-MIT-green)
[![Release](https://img.shields.io/github/v/release/drzeeb/VeloSpot?label=latest%20release)](https://github.com/drzeeb/VeloSpot/releases/latest)

VeloSpot is an Android application that helps cyclists discover and navigate to bike parking facilities **anywhere in Germany**. Powered by a pre-bundled OpenStreetMap dataset with over **100 000 locations**, the app works fully offline from the very first launch — no network required to find parking.

## 🇩🇪 Germany-Wide Coverage — Now Live!

> **This release replaces the previous Trier-only WFS/WMS data source with a pre-bundled OpenStreetMap extract covering all of Germany.**

- **~100 000+ bicycle parking locations** extracted from the OSM Germany dataset
- **Fully offline** — all data is bundled inside the APK as a Room/SQLite asset
- **Instant startup** — no network call needed to see parking spots
- **Viewport-based loading** — only the markers visible in the current map area are queried, keeping memory usage low even with 100 000 entries
- **Lazy reverse geocoding** — when you tap a marker without a stored address, Nominatim is queried once, the result is cached locally and shown immediately in the details sheet
- **Extraction script included** (`scripts/extract_osm_parking.py`) — regenerate the bundled database from a fresh Geofabrik PBF at any time

## 🔗 Quick Links

- **GitHub Repository**: https://github.com/drzeeb/VeloSpot
- **GitHub Pages**: https://drzeeb.github.io/VeloSpot/
- **Changelog**: [`CHANGELOG.md`](./CHANGELOG.md)
- **Licensing & Attribution**: [ATTRIBUTIONS.md](ATTRIBUTIONS.md)

## ✨ Highlights

- **Germany-wide** bike parking data from OpenStreetMap (~100 000+ locations)
- **Fully offline** after install — no WFS/WMS network calls required
- OpenStreetMap-based map browsing with **MapLibre vector tiles** and custom bike markers
- Viewport-based marker loading — smooth performance even across the entire country
- Lazy address resolution via Nominatim (cached permanently to local DB)
- Red marker highlighting for favorite parking spots
- Orange marker highlighting for currently selected parking space
- Dedicated favorites sheet with direct navigation shortcuts
- Smooth animated map camera transitions powered by MapLibre's built-in easing
- Current-location recentering and location marker support
- In-app dark mode toggle from the top-right menu
- **Parking space photos** with automatic caching via Coil for fast loading
- **In-app bike route navigation** with live route overlay (no external map app handoff)
- **Navigation focus mode**: non-target parking markers become smaller, lighter gray, and more transparent while navigation is active
- **8 languages** with persistent in-app language picker (DE 🇩🇪 EN 🇬🇧 FR 🇫🇷 IT 🇮🇹 PT 🇵🇹 LB 🇱🇺 NL 🇳🇱 ES 🇪🇸)
- **🆕 BRouter offline routing** — routes calculated entirely on-device with 5 cycling profiles; no internet needed after the one-time segment download

## 🌟 Features

- 🇩🇪 **All of Germany** - 100 000+ bike parking spots from OpenStreetMap, bundled offline
- 📍 **Interactive Map** - Browse bike parking spaces on an interactive **MapLibre vector tile** map
- ⚡ **Viewport Loading** - Only the visible map area is queried; scroll anywhere in Germany without slowdowns
- 🏠 **Offline-First** - All parking data is available instantly, even without a network connection
- 📬 **Address Lookup** - Missing addresses are resolved via Nominatim and cached locally on first tap
- 🎬 **Smooth Animations** - Fluid zoom and pan transitions powered by MapLibre's native camera engine
- 🗺️ **Vector Tiles** - Sharp, smooth map rendering at every zoom level via [OpenFreeMap](https://openfreemap.org/) Liberty style (no API key required)
- 🧭 **My Location** - Center the map on your current position and display a live location marker
- ❤️ **Favorites** - Save frequently used bike parking spots and use dedicated actions for navigation or spot details
- ⭐ **Selected Highlight** - See your current selection highlighted with an orange marker
- 📸 **Parking Photos** - View parking space photos with automatic smart caching for fast loading
- 🌙 **Dark Mode Toggle** - Switch the app theme directly from the in-app menu
- 🌐 **8 Languages** - Choose from German, English, French, Italian, Portuguese, Luxembourgish, Dutch, and Spanish; the selection is remembered across restarts
- 💾 **SQLite Offline Database** - All ~100 000 parking locations are bundled as a Room asset; no sync required
- 🎯 **In-App Navigation** - Calculate bike routes directly inside the app and render the route path on the map
- 👁️ **Navigation Focus** - During active navigation, non-target markers are dimmed to keep the destination visually prominent
- 📊 **Detailed Information** - View capacity, address, coverage information, and photos for each location
- 🎨 **Modern UI** - Clean and intuitive Jetpack Compose-based interface

## 📱 Target Platform

- **Android 8.0 (API 26)** and above
- Minimum: API 26 | Target: API 37

## 🛠 Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: Clean Architecture with MVVM
- **Dependency Injection**: Hilt
- **Data**: Retrofit, Moshi, Room (SQLite asset DB), **MapLibre** (vector tile map rendering)
- **Map Style**: [OpenFreeMap](https://openfreemap.org/) Liberty (free, no API key required)
- **Geocoding**: Nominatim REST API (lazy, on-demand, cached)
- **Location**: Android runtime permissions, Google Play Services location APIs
- **Build System**: Gradle
- **Data Pipeline**: Python + pyosmium (`scripts/extract_osm_parking.py`)

## 🗂 Project Structure

```
VeloSpot/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── assets/
│   │   │   │   └── bike_parking_germany.db   # Pre-bundled OSM dataset (~20 MB)
│   │   │   ├── java/de/velospot/
│   │   │   │   ├── feature/          # Feature modules
│   │   │   │   ├── domain/           # Business logic
│   │   │   │   ├── data/             # Data layer (local DB + geocoding)
│   │   │   │   ├── core/             # Shared utilities
│   │   │   │   └── MainActivity.kt
│   │   │   ├── res/                  # Resources
│   │   │   └── AndroidManifest.xml
│   │   ├── test/                     # Unit tests
│   │   └── androidTest/              # Instrumented tests
│   ├── schemas/                      # Room schema exports (v3)
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── scripts/
│   ├── extract_osm_parking.py        # PBF → SQLite pipeline
│   └── README.md
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## 📥 Download

Pre-built debug APKs are available on the [Releases page](https://github.com/drzeeb/VeloSpot/releases/latest).

1. Download the latest `VeloSpot-vX.X.X-debug.apk`
2. On your Android device: **Settings → Install unknown apps** → allow your browser or file manager
3. Open the APK and tap **Install**

New releases are built automatically by GitHub Actions whenever a version tag is pushed.

## 🚀 Getting Started

### Prerequisites

- Android Studio (Jellyfish or newer)
- Java Development Kit (JDK 17+)
- Android SDK 37+
- Git

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/drzeeb/VeloSpot.git
   cd VeloSpot
   ```

2. **Open in Android Studio**
   - File → Open
   - Select the VeloSpot directory
   - Android Studio will automatically detect and configure the project

3. **Build the project**
   ```bash
   ./gradlew build
   ```

4. **Run on device or emulator**
   ```bash
   ./gradlew installDebug
   adb shell am start -n de.velospot/de.velospot.MainActivity
   ```

### Regenerating the Bundled Database

The parking database is pre-generated and committed to `app/src/main/assets/`. To regenerate it from a fresh OSM extract:

```bash
pip install osmium requests
cd scripts/
python extract_osm_parking.py --pbf germany-latest.osm.pbf
# → writes ../app/src/main/assets/bike_parking_germany.db
```

See [`scripts/README.md`](scripts/README.md) for full details on the extraction pipeline, including how to download the PBF from Geofabrik.

## 📊 Data Sources

VeloSpot bundles bike parking data extracted from OpenStreetMap and displays it on OpenStreetMap tiles:

- **Bike Parking Data**: OpenStreetMap contributors (Germany extract via [Geofabrik](https://download.geofabrik.de/europe/germany.html))
- **Data format**: Pre-processed SQLite asset (Room-compatible)
- **Update frequency**: Bundled at build time; regenerate with `extract_osm_parking.py` for fresh data
- **Reverse Geocoding**: [Nominatim](https://nominatim.openstreetmap.org/) (on-demand, cached, OSM-based)
- **Map Tiles**: OpenFreeMap vector tiles (Liberty style, [openfreemap.org](https://openfreemap.org/)) rendered via MapLibre
- **Map License**: Open Data Commons Open Database License (ODbL 1.0)
- **Attribution**: © OpenStreetMap contributors

For more information about OpenStreetMap and ODbL, visit:
- OpenStreetMap: https://www.openstreetmap.org/copyright
- ODbL License: https://opendatacommons.org/licenses/odbl/

## 🎨 UI Components

### Map Screen
- Centered map view with bike parking markers
- Zoom-responsive marker scaling
- Favorite-aware marker colors
- Current location marker and recenter action
- Top-right quick menu with favorites, language picker, and dark mode toggle
- In-app routing polyline, destination highlight, and route status card (distance/time)
- Navigation focus styling that dims non-target markers (smaller, lighter gray, and more transparent)
- Error handling and loading states

### Parking Details Sheet
- Bottom sheet with parking information
- Address auto-resolved via Nominatim if not present in OSM data
- Favorite toggle for quick saving
- Quick-access navigation button
- Capacity and coverage indicators

### Favorites Sheet
- Dedicated list of saved bike parking spots
- Separate actions per saved location: start navigation or show spot details
- Empty-state guidance for first-time use

### Language Picker
- Flag-based selection for 8 supported languages
- Selection persists across app restarts and cold starts

## 🧪 Testing

Run unit tests:
```bash
./gradlew test
```

Run instrumented tests:
```bash
./gradlew connectedAndroidTest
```

## 🔐 CI & Branch Protection

The repository uses GitHub Actions and GitHub Rulesets to enforce safe merges on `main`:

- Required CI checks: `ci-build` and `ci-test`
- Pull requests are required for `main`
- At least one approval is required
- Stale reviews are dismissed on new commits
- Review threads must be resolved
- Non-fast-forward updates and branch deletion are blocked
- Linear history is enforced

Renovate is configured so that only security-related dependency updates can be automerged.

Recent Renovate dependency and tooling updates are documented in [`CHANGELOG.md`](./CHANGELOG.md) under `Unreleased`.

## 🔧 Configuration

### Network Timeout
Edit `core/di/NetworkModule.kt` to adjust API request timeouts.

### Default Map Location
Modify constants in `feature/map/presentation/MainMapScreen.kt`:
```kotlin
private const val TRIER_LAT = 49.7596
private const val TRIER_LON = 6.6441
private const val DEFAULT_ZOOM = 14.0
```

## 📦 Build & Release

### Debug Build
```bash
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

Generated APK location: `app/build/outputs/apk/`

## 🗒 Changelog

Project history and notable milestones are documented in [`CHANGELOG.md`](./CHANGELOG.md).

## 🐛 Troubleshooting

### Build fails with "JAVA_HOME not set"
```bash
# On Windows
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
```

### App crashes on startup
- Ensure location permissions are granted
- Check that the asset database `bike_parking_germany.db` is present under `app/src/main/assets/`

### No parking markers visible after fresh install
- On the very first launch Room copies the ~20 MB asset database — this takes a second or two; wait briefly and the markers will appear
- If upgrading from a previous version: uninstall the old app first so Room copies the fresh asset (or use `adb shell run-as de.velospot rm databases/velospot_database.db` on debug builds)

### Map shows blank
- The MapLibre map style is loaded from [OpenFreeMap](https://openfreemap.org/) on first launch — a brief internet connection is required to cache the vector tile style
- After the style is cached, the map renders offline; only tile data for new map areas requires a network call
- No API key or registration is required

### My Location does not work
- Confirm location permission is granted for the app
- Make sure location services are enabled on the device
- Try tapping the floating action button again after Android shows the permission dialog

### Address shows "—" for a parking spot
- Address resolution happens the first time you tap a marker; it requires a brief network call to Nominatim
- After the first tap the address is cached permanently in the local database

## 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Code Style
- Follow [Kotlin naming conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable names
- Add comments for complex logic (in English)
- Format code with Android Studio's built-in formatter

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

**Important**: The MIT License applies to the VeloSpot source code only. Map data from OpenStreetMap is licensed under the **Open Data Commons Open Database License (ODbL)** — see [ATTRIBUTIONS.md](ATTRIBUTIONS.md) for full attribution details.

## 👨‍💻 Author

**Michael** - Initial development & maintenance

## 📞 Support & Contact

For issues, suggestions, or questions:
- Open an [Issue](https://github.com/drzeeb/VeloSpot/issues)
- Start a [Discussion](https://github.com/drzeeb/VeloSpot/discussions)

## 🚴 Happy Cycling! 🚲

Navigate with confidence and never miss a parking spot again — anywhere in Germany!

---

**Last Updated**: 2026-06-09  
**Status**: Active Development
