# 🚲 VeloSpot

**Find bike parking spaces effortlessly**

VeloSpot is an Android application that helps cyclists discover and locate bike parking facilities in Trier and surrounding areas. With real-time data from local services and an intuitive map interface, finding a safe place to park your bike has never been easier.

## 🌟 Features

- 📍 **Interactive Map** - Browse bike parking spaces on an interactive OSM map
- 🚲 **Real-time Data** - Access current bike parking information via WFS/WMS services
- 🎯 **Quick Navigation** - Open parking locations directly in your navigation app (Google Maps, OsmAnd, etc.)
- 📊 **Detailed Information** - View capacity, address, and coverage information for each location
- 🔄 **Auto-refresh** - Data updates automatically as you navigate
- 🎨 **Modern UI** - Clean and intuitive Jetpack Compose-based interface

## 📱 Target Platform

- **Android 8.0 (API 26)** and above
- Minimum: API 26 | Target: API 34

## 🛠 Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: Clean Architecture with MVVM
- **Dependency Injection**: Hilt
- **Data**: Retrofit, Moshi, OSMDroid
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

## 🚀 Getting Started

### Prerequisites

- Android Studio (Jellyfish or newer)
- Java Development Kit (JDK 17+)
- Android SDK 34+
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
- Marker clustering at lower zoom levels
- Error handling and loading states

### Parking Details Sheet
- Bottom sheet with parking information
- Quick-access navigation button
- Capacity and coverage indicators

## 🧪 Testing

Run unit tests:
```bash
./gradlew test
```

Run instrumented tests:
```bash
./gradlew connectedAndroidTest
```

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

### Map shows blank
- Ensure OSM tiles are loading (check network tab)
- Verify `userAgentValue` is set in `BaseApplication.kt`

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

**Last Updated**: 2026  
**Status**: Active Development

