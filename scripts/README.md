# OSM Bicycle Parking Extractor

Extracts all `amenity=bicycle_parking` nodes from an OpenStreetMap PBF file and writes
them into a pre-populated SQLite database that matches the VeloSpot Room schema v3.

Place the generated file at:

```
app/src/main/assets/bike_parking_germany.db
```

## Requirements

```bash
pip install osmium requests
```

> **Note**: `osmium` (pyosmium) requires native build tools.
> On Windows, installation via conda or WSL is recommended.

## Usage

### 1. Obtain the Germany PBF (optional)

The script can download the file automatically (~4 GB):

```bash
python extract_osm_parking.py
# Prompts: "Download germany-latest.osm.pbf from Geofabrik? [y/N]"
```

Or download it manually from https://download.geofabrik.de/europe/germany-latest.osm.pbf
and pass the path explicitly:

```bash
python extract_osm_parking.py --pbf /path/to/germany-latest.osm.pbf
```

### 2. Generate the database

```bash
python extract_osm_parking.py \
  --pbf germany-latest.osm.pbf \
  --out ../app/src/main/assets/bike_parking_germany.db
```

Default paths are set so that a plain invocation from the `scripts/` directory works without arguments.

### 3. Rebuild the app

```bash
# From the project root:
./gradlew assembleDebug
```

Room copies the asset into the app's data directory on the very first launch.

## Runtime & File Size

| Step | Typical Duration |
|------|-----------------|
| Download Germany PBF | 10–30 min (≈ 4 GB) |
| Parsing (fast path — C++ TagFilter) | **< 2 min** |
| Parsing (fallback — Python SimpleHandler) | 30–50 min |
| SQLite write | < 1 min |

The generated database is typically **15–25 MB** for all of Germany
(≈ 80 000–130 000 bicycle parking nodes).

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

Geofabrik updates the Germany extract daily. To refresh the bundled data:

1. Download a fresh PBF
2. Re-run the script
3. Rebuild and redistribute the app

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
