# VeloSpot – Attributions & Licenses

This file lists all data sources, services and libraries used by VeloSpot,
including their respective licenses.

---

## 🗺️ Map Data & Tiles

### OpenStreetMap (OSM)
- **Source**: https://www.openstreetmap.org/
- **License**: Open Data Commons Open Database License (ODbL) 1.0
- **License URL**: https://opendatacommons.org/licenses/odbl/1.0/
- **Required attribution**: © OpenStreetMap contributors

OSM data is available under the ODbL. This means:
- You are free to copy, distribute, transmit and adapt the data
- OpenStreetMap and its contributors must be credited
- Derived databases must also be published under the ODbL
- VeloSpot source code is licensed separately under MIT and is not affected by the ODbL

### OpenFreeMap (vector tiles)
- **Source**: https://openfreemap.org/
- **Style**: Liberty
- **License**: Open Data Commons Open Database License (ODbL) 1.0 (map data) / MIT (style definition)
- **No API key required**

---

## 🚲 Bike Parking Data

### OpenStreetMap – Germany extract
- **Source**: Geofabrik GmbH, https://download.geofabrik.de/europe/germany.html
- **Base data**: OpenStreetMap contributors (© OpenStreetMap contributors)
- **License**: ODbL 1.0
- **Format**: Pre-processed SQLite database (Room asset, ~20 MB), generated with `scripts/extract_osm_parking.py`

---

## 🧭 Services (network, on demand only)

### Nominatim (geocoding)
- **Operator**: OpenStreetMap Foundation / community instances
- **URL**: https://nominatim.openstreetmap.org/
- **License**: ODbL 1.0 (data), Nominatim software under GPL v2+
- **Usage**: Address resolution on first marker tap (then cached locally), forward geocoding for address search
- **Privacy**: No personal data is transmitted – only coordinates or search terms

### OSRM (online routing, fallback)
- **Operator**: Project OSRM / OpenStreetMap Foundation infrastructure
- **URL**: https://router.project-osrm.org/
- **License**: BSD 2-Clause
- **Source code**: https://github.com/Project-OSRM/osrm-backend
- **Usage**: Online bicycle routing as fallback when BRouter segments are unavailable

---

## 📦 Libraries

### BRouter (offline routing engine)
- **Version**: 1.6.3
- **License**: MIT License
- **Source code**: https://github.com/abrensch/brouter
- **Binary in repo**: `app/libs/brouter-1.6.3-all.jar`
  - The JAR is a pre-built all-in-one archive compiled from the source above.
  - It contains no proprietary dependencies.
  - For F-Droid the binary is declared under `scanignore`; license and source code are publicly available.
- **Usage**: Offline bicycle route calculation entirely on-device

### MapLibre Android SDK
- **Version**: 13.3.0
- **License**: BSD 2-Clause License
- **Source code**: https://github.com/maplibre/maplibre-native
- **Maven**: `org.maplibre.gl:android-sdk`

### Jetpack Compose / AndroidX
- **License**: Apache License 2.0
- **Source**: https://developer.android.com/jetpack/compose

### Hilt / Dagger (dependency injection)
- **Version**: 2.59.2
- **License**: Apache License 2.0
- **Source code**: https://github.com/google/dagger

### Room (SQLite abstraction)
- **Version**: 2.8.4
- **License**: Apache License 2.0
- **Source code**: https://developer.android.com/training/data-storage/room

### Retrofit & OkHttp (HTTP client)
- **Retrofit**: 3.0.0 – Apache License 2.0 – https://github.com/square/retrofit
- **OkHttp**: 5.4.0 – Apache License 2.0 – https://github.com/square/okhttp

### Moshi (JSON serialisation)
- **Version**: 1.15.2
- **License**: Apache License 2.0
- **Source code**: https://github.com/square/moshi

### Coil (image loading)
- **Version**: 2.7.0
- **License**: Apache License 2.0
- **Source code**: https://github.com/coil-kt/coil

### Kotlin (language & standard library)
- **Version**: 2.4.0
- **License**: Apache License 2.0
- **Source code**: https://github.com/JetBrains/kotlin

---

## 📝 VeloSpot Project License

**VeloSpot** is licensed under the MIT License – see [LICENSE](LICENSE).

The MIT License applies to the VeloSpot source code only.
Map data from OpenStreetMap remains under the ODbL.

---

**Last updated**: 2026-06-11
