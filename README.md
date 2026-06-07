# 🚲 VeloSpot

**Find bike parking spaces effortlessly**

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-0A2A66)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
![License](https://img.shields.io/badge/license-MIT-green)
[![Release](https://img.shields.io/github/v/release/drzeeb/VeloSpot?label=latest%20release)](https://github.com/drzeeb/VeloSpot/releases/latest)

VeloSpot is an Android application that helps cyclists discover and locate bike parking facilities in Trier and surrounding areas. With real-time data from local services and an intuitive map interface, finding a safe place to park your bike has never been easier.

## 🔗 Quick Links

- **GitHub Repository**: https://github.com/drzeeb/VeloSpot
- **GitHub Pages**: https://drzeeb.github.io/VeloSpot/
- **Changelog**: [`CHANGELOG.md`](./CHANGELOG.md)
- **Licensing & Attribution**: [ATTRIBUTIONS.md](ATTRIBUTIONS.md)

## ✨ Highlights

- Official bike parking data from the Trier geoportal
- OpenStreetMap-based map browsing with custom bike markers
- Red marker highlighting for favorite parking spots
- Orange marker highlighting for currently selected parking space
- Dedicated favorites sheet with direct navigation shortcuts
- Smooth animated map camera transitions with easing functions
- Current-location recentering and location marker support
- In-app dark mode toggle from the top-right menu
- Room / SQLite local cache for faster reloads
- **Parking space photos** with automatic caching via Coil for fast loading
- **In-app bike route navigation** with live route overlay (no external map app handoff)
- **8 languages** with persistent in-app language picker (DE 🇩🇪 EN 🇬🇧 FR 🇫🇷 IT 🇮🇹 PT 🇵🇹 LB 🇱🇺 NL 🇳🇱 ES 🇪🇸)

## 🌟 Features

- 📍 **Interactive Map** - Browse bike parking spaces on an interactive OSM map
- 🎬 **Smooth Animations** - Fluid zoom and pan transitions with easing for a polished user experience
- 🧭 **My Location** - Center the map on your current position and display a live location marker
- ❤️ **Favorites** - Save frequently used bike parking spots and use dedicated actions for navigation or spot details
- ⭐ **Selected Highlight** - See your current selection highlighted with an orange marker
- 📸 **Parking Photos** - View parking space photos with automatic smart caching for fast loading
- 🌙 **Dark Mode Toggle** - Switch the app theme directly from the in-app menu
- 🌐 **8 Languages** - Choose from German, English, French, Italian, Portuguese, Luxembourgish, Dutch, and Spanish; the selection is remembered across restarts
- 🚲 **Real-time Data** - Access current bike parking information via WFS/WMS services
- 💾 **SQLite Offline Cache** - Store downloaded bike parking data locally with Room for fast reloads
- 🎯 **In-App Navigation** - Calculate bike routes directly inside the app and render the route path on the map
- 📊 **Detailed Information** - View capacity, address, coverage information, and photos for each location
- 🔄 **Auto-refresh** - Data updates automatically as you navigate
- 🎨 **Modern UI** - Clean and intuitive Jetpack Compose-based interface

## 📱 Target Platform

- **Android 8.0 (API 26)** and above
- Minimum: API 26 | Target: API 37

## 🛠 Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: Clean Architecture with MVVM
- **Dependency Injection**: Hilt
- **Data**: Retrofit, Moshi, Room (SQLite), OSMDroid
- **Location**: Android runtime permissions, Google Play Services location APIs
- **Build System**: Gradle

## 🗂 Project Structure

```
VeloSpot/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/de/velospot/
│   │   │   │   ├── feature/          # Feature modules
│   │   │   │   ├── domain/           # Business logic
│   │   │   │   ├── data/             # Data layer
│   │   │   │   ├── core/             # Shared utilities
│   │   │   │   └── MainActivity.kt
│   │   │   ├── res/                  # Resources
│   │   │   └── AndroidManifest.xml
│   │   ├── test/                     # Unit tests
│   │   └── androidTest/              # Instrumented tests
│   ├── build.gradle.kts
│   └── proguard-rules.pro
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

## 📊 Data Sources

VeloSpot retrieves bike parking data from public WFS/WMS services and displays it on OpenStreetMap tiles:

- **Bike Parking Data Provider**: Trier Geoportal
- **Data format**: GeoJSON, GML-based WFS
- **Update frequency**: Real-time from server
- **Map Tiles**: OpenStreetMap contributors
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
- Error handling and loading states

### Parking Details Sheet
- Bottom sheet with parking information
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
- Check internet connectivity
- Verify WFS/WMS service is accessible
- If you denied location once, use the map without recentering or grant permission when Android prompts again

### Map shows blank
- Ensure OSM tiles are loading (check network tab)
- Verify `userAgentValue` is set in `BaseApplication.kt`

### My Location does not work
- Confirm location permission is granted for the app
- Make sure location services are enabled on the device
- Try tapping the floating action button again after Android shows the permission dialog

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

**Important**: The MIT License applies to the VeloSpot source code only. Map data from OpenStreetMap is licensed under the **Open Data Commons Open Database License (ODbL)** - see [ATTRIBUTIONS.md](ATTRIBUTIONS.md) for full attribution details.

## 👨‍💻 Author

**Michael** - Initial development & maintenance

## 📞 Support & Contact

For issues, suggestions, or questions:
- Open an [Issue](https://github.com/drzeeb/VeloSpot/issues)
- Start a [Discussion](https://github.com/drzeeb/VeloSpot/discussions)

## 🚴 Happy Cycling! 🚲

Navigate with confidence and never miss a parking spot again!

---

**Last Updated**: 2026-06-07  
**Status**: Active Development
