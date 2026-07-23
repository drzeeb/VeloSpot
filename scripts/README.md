# VeloSpot helper scripts

This directory holds offline developer tooling. See the sections below.

---

# Recorded-ride GPS analysis (`analyze_recorded_rides.py`)

Read-only analysis of the app's `velospot_rides` database, used to tune the GPS
outlier thresholds in `app/.../core/tracking/RideTracker.kt`. It reports the
distributions behind each gate (accuracy, segment speed, acceleration, fix
interval, altitude jumps) and shows how the altitude-outlier gate affects the
recorded ascent. Only aggregate statistics are printed — no coordinates — so the
output contains no personal location data. The script never modifies the database.

Pull the database from a connected, debuggable device:

```bash
adb exec-out run-as de.velospot cat databases/velospot_rides.db > rides.db
```

Then analyse the most recent rides:

```bash
python analyze_recorded_rides.py --db rides.db --limit 3
```

---

# OSM Bicycle Parking Extractor

Extracts all `amenity=bicycle_parking` nodes from OpenStreetMap PBF files and writes
them into pre-populated SQLite databases that match the VeloSpot Room schema v3.

By default the script rebuilds **all bundled countries — Germany, France and
Luxembourg** — in one run and writes them to:

```
app/src/main/assets/bike_parking_germany.db
app/src/main/assets/bike_parking_france.db
app/src/main/assets/bike_parking_luxembourg.db
```

## Requirements

```bash
pip install osmium requests
```

> **Note**: `osmium` (pyosmium) requires native build tools.
> On Windows, installation via conda or WSL is recommended.

## Usage

### Refresh everything (recommended)

```bash
python extract_osm_parking.py
```

For each configured country this:

1. downloads the **latest** Geofabrik extract (if not already present locally),
2. filters the `amenity=bicycle_parking` nodes via the fast C++ path,
3. writes the country's asset DB into `app/src/main/assets/`,
4. deletes the multi-GB PBF download afterwards, and finally
5. rewrites the `DATA_DATE_*` constants on the in-app About sheet
   (`AboutSheet.kt`) to today's date, so the displayed "data status" always
   matches what was just built.

### Useful options

```bash
# Only rebuild a subset of countries:
python extract_osm_parking.py --countries germany luxembourg

# Reuse an already-downloaded PBF for a single country:
python extract_osm_parking.py --countries germany --pbf germany-latest.osm.pbf

# Keep the downloaded PBF files instead of deleting them:
python extract_osm_parking.py --keep-pbf

# Do not touch the About-sheet dates:
python extract_osm_parking.py --no-about-update
```

PBF extracts are downloaded from `https://download.geofabrik.de/europe/<country>-latest.osm.pbf`.

### Rebuild the app

```bash
# From the project root:
./gradlew assembleDebug
```

Room copies the assets into the app's data directory on the very first launch.

## Runtime & File Size

| Step | Typical Duration |
|------|-----------------|
| Download Germany / France PBF | 10–30 min each (several GB) |
| Download Luxembourg PBF | seconds (~45 MB) |
| Parsing (fast path — C++ TagFilter) | **< 2 min** per country |
| Parsing (fallback — Python SimpleHandler) | 30–50 min for large countries |
| SQLite write | < 1 min |

The generated databases are typically **15–25 MB** for large countries
(≈ 100 000–160 000 bicycle parking nodes) and well under 1 MB for Luxembourg.

### Why only nodes?

Ways and relations require a full node-coordinate index
(`locations=True`, `idx="flex_mem"`), which loads several GB into RAM and
takes 30–60+ minutes for Germany. Since > 95 % of all `bicycle_parking`
features are tagged as nodes, the overhead is not worthwhile.

### Fast path vs. fallback

When `osmium.FileProcessor` and `osmium.filter.TagFilter` are available
(pyosmium ≥ 3.x), the script runs the tag filter entirely in compiled C++.
Python is only called for the ≈ 100 000 matching nodes instead of all
≈ 900 000 000 nodes in the Germany extract — reducing parse time from
30–50 minutes to under 2 minutes.

If the fast path is unavailable, the script falls back to `SimpleHandler`
with a progress line printed every 500 000 nodes.

## OSM Tag Mapping

| OSM tag | Room column | Notes |
|---------|-------------|-------|
| `amenity=bicycle_parking` | — | Filter criterion |
| `name` | `name` | |
| `capacity` | `capacity` | Parsed as integer |
| `covered=yes` | `isCovered = 1` | |
| `bicycle_parking=shed/lockers/…` | `type = GARAGE` | |
| `bicycle_parking=stands/rack/…` | `type = BIKE_RACK` | |
| No `bicycle_parking` tag | `type = UNKNOWN` | |
| `operator` | `operator` | |
| `addr:street` + `addr:housenumber` | `address` | |
| Node ID | `id = osm_n_{id}` | |

## Database Updates

Geofabrik updates the country extracts daily. To refresh the bundled data:

1. Run `python extract_osm_parking.py` (downloads the latest PBFs, rebuilds all
   asset DBs and bumps the About-sheet dates automatically)
2. Rebuild and redistribute the app

> **Important — database versioning**: Room's `createFromAsset` copies the asset
> **only when the target database file does not yet exist** on the device.
> After the initial copy, Room treats the database as a normal writable file.
>
> The app uses the database name `velospot_osm.db`.  Users who installed a
> release prior to the Germany update had a database named `velospot_database.db`;
> because the name changed, all users automatically receive a fresh copy of the
> OSM asset on their first launch of the new version.
>
> If the database schema or version changes in the future, bump
> `BikeParkingDatabase.version` and add a Room migration.

## Nominatim Address Enrichment

Addresses that are not present in the OSM tags are resolved lazily at runtime
via the [Nominatim](https://nominatim.openstreetmap.org/reverse) reverse geocoding API.
The result is cached permanently in the local database so the network is only
queried once per location.

The Nominatim [Usage Policy](https://operations.osmfoundation.org/policies/nominatim/)
requires a descriptive `User-Agent` header and at most one request per second.
Both conditions are met: the `User-Agent` is set in `NominatimApi.kt` and requests
are only sent on explicit user interaction (tapping a marker).
