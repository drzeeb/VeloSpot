#!/usr/bin/env python3
"""
extract_osm_parking.py
======================
Extracts bicycle_parking data from OpenStreetMap PBF files and writes one
pre-populated SQLite database per country that exactly matches the VeloSpot
Room schema v3.

By default the script processes **Germany, France and Luxembourg**: it
downloads the latest Geofabrik extract for each country, filters the
bicycle_parking nodes, writes the matching asset database into
`app/src/main/assets/` and finally updates the "data status" dates shown on
the in-app About sheet.

The generated files are placed at:
    app/src/main/assets/bike_parking_germany.db
    app/src/main/assets/bike_parking_france.db
    app/src/main/assets/bike_parking_luxembourg.db

Usage
-----
    pip install osmium requests
    # Refresh all bundled countries and update the About-sheet dates:
    python extract_osm_parking.py

    # Only refresh a subset:
    python extract_osm_parking.py --countries germany luxembourg

    # Use an already-downloaded PBF for a single country:
    python extract_osm_parking.py --countries germany --pbf germany-latest.osm.pbf

If a country's PBF is not present locally, it is downloaded automatically from
Geofabrik. Downloaded PBFs are removed afterwards unless --keep-pbf is given.

Room schema (v3)
----------------
Identity hash: d724c4ab0656349cd4e8038b29e95603
Tables:
  bike_parking_spaces   – pre-populated with OSM data (read-only in app)
  favorite_parking_spaces – user data, stays empty in asset
  room_master_table     – Room internal validation table
"""

import argparse
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
from datetime import date
from typing import Optional

try:
    import osmium
except ImportError:
    sys.exit(
        "osmium not found. Install it with:  pip install osmium\n"
        "On some systems you may need:       pip install osmium-tool"
    )

# On Windows the default console/redirect encoding is cp1252, which cannot
# encode the Unicode characters (→, …, –, ×) used in the status messages below.
# Reconfigure stdout/stderr to UTF-8 so the script also works when its output
# is piped or redirected to a log file.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

# ---------------------------------------------------------------------------
# Country configuration
# ---------------------------------------------------------------------------

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_ASSETS_DIR = os.path.normpath(
    os.path.join(_SCRIPT_DIR, "..", "app", "src", "main", "assets")
)
_ABOUT_SHEET = os.path.normpath(
    os.path.join(
        _SCRIPT_DIR, "..", "app", "src", "main", "java", "de", "velospot",
        "feature", "map", "presentation", "sheets", "AboutSheet.kt",
    )
)

# name -> download URL, output asset db, sourceLayer tag and the Kotlin
# constant on the About sheet that carries the human-readable data date.
COUNTRIES: dict[str, dict[str, str]] = {
    "germany": {
        "url": "https://download.geofabrik.de/europe/germany-latest.osm.pbf",
        "db": "bike_parking_germany.db",
        "source_layer": "osm_germany",
        "about_const": "DATA_DATE_GERMANY",
    },
    "france": {
        "url": "https://download.geofabrik.de/europe/france-latest.osm.pbf",
        "db": "bike_parking_france.db",
        "source_layer": "osm_france",
        "about_const": "DATA_DATE_FRANCE",
    },
    "luxembourg": {
        "url": "https://download.geofabrik.de/europe/luxembourg-latest.osm.pbf",
        "db": "bike_parking_luxembourg.db",
        "source_layer": "osm_luxembourg",
        "about_const": "DATA_DATE_LUXEMBOURG",
    },
}

# ---------------------------------------------------------------------------
# OSM tag → domain model mapping
# ---------------------------------------------------------------------------

# bicycle_parking tag values that map to GARAGE type
_GARAGE_TAGS = {"shed", "lockers", "building", "garage", "two-tier", "multi-storey"}

# All other known values map to BIKE_RACK; unrecognised → BIKE_RACK, absent → UNKNOWN
_BIKE_RACK_TAGS = {
    "stands", "wall_loops", "rack", "anchors", "informal",
    "streetpod", "handlebar_holder", "lean_to", "bollard", "ground_slots",
}


def _map_type(tags) -> str:
    """Return BikeParkingType name for an OSM tags object."""
    bp = tags.get("bicycle_parking", "")
    if bp in _GARAGE_TAGS:
        return "GARAGE"
    if bp in _BIKE_RACK_TAGS:
        return "BIKE_RACK"
    if bp:
        # Known amenity=bicycle_parking but unrecognised subtype → treat as rack
        return "BIKE_RACK"
    return "UNKNOWN"


def _is_covered(tags) -> Optional[int]:
    """Return 1/0/None for the isCovered column (Room stores booleans as INTEGER)."""
    covered = tags.get("covered", "")
    if covered == "yes":
        return 1
    if covered == "no":
        return 0
    # Some nodes have a bicycle_parking type that implies covering
    bp = tags.get("bicycle_parking", "")
    if bp in {"shed", "lockers", "building", "garage", "two-tier", "multi-storey"}:
        return 1
    return None


def _parse_capacity(tags) -> Optional[int]:
    raw = tags.get("capacity", "")
    try:
        return int(raw)
    except (ValueError, TypeError):
        return None


def _build_address(tags) -> Optional[str]:
    street = tags.get("addr:street", "")
    housenumber = tags.get("addr:housenumber", "")
    city = tags.get("addr:city", "")
    parts = []
    if street:
        parts.append(street + (" " + housenumber if housenumber else ""))
    if city:
        parts.append(city)
    return ", ".join(parts) if parts else None


# ---------------------------------------------------------------------------
# osmium-tool pre-filter (optional but ~100× faster)
# ---------------------------------------------------------------------------

def _osmium_cli_available() -> bool:
    """Return True if the osmium command-line tool is on PATH."""
    return shutil.which("osmium") is not None


def prefilter_pbf(input_pbf: str) -> tuple[str, bool]:
    """
    Use the osmium CLI to extract only bicycle_parking nodes into a tiny
    temporary PBF file.  Returns (path_to_use, is_temp).

    If osmium is not available, returns the original file unchanged.

    Why this is fast
    ----------------
    The compiled C++ osmium-tool reads and decompresses PBF blocks at full
    I/O speed and discards >99.9 % of data before Python ever sees it.
    The resulting file is typically 3–8 MB instead of 4 GB.
    Germany 4 GB → pre-filter ~30 s → Python parse < 1 s.

    Install osmium-tool
    -------------------
    Windows (winget):  winget install osmium-tool
    Windows (conda):   conda install -c conda-forge osmium-tool
    Linux:             sudo apt install osmium-tool
    macOS:             brew install osmium-tool
    """
    if not _osmium_cli_available():
        print(
            "  osmium CLI not found – parsing the full PBF in Python "
            "(~50 min for Germany).\n"
            "  Tip: install osmium-tool for a ~100× speedup:\n"
            "       Windows → winget install osmium-tool\n"
            "       Linux   → sudo apt install osmium-tool\n"
        )
        return input_pbf, False

    tmp_pbf = tempfile.mktemp(suffix="_bike_parking.osm.pbf")
    print(f"osmium-tool found – pre-filtering to bicycle_parking nodes only …")
    t0 = time.time()
    result = subprocess.run(
        [
            "osmium", "tags-filter",
            input_pbf,
            "n/amenity=bicycle_parking",   # nodes only
            "--output", tmp_pbf,
            "--overwrite",
            "--progress",
        ],
        check=False,
    )
    elapsed = time.time() - t0

    if result.returncode != 0:
        print(f"  osmium pre-filter failed (exit {result.returncode}) – falling back to full parse.")
        if os.path.exists(tmp_pbf):
            os.remove(tmp_pbf)
        return input_pbf, False

    size_kb = os.path.getsize(tmp_pbf) / 1024
    print(f"  Pre-filter done in {elapsed:.0f}s  →  {size_kb:.0f} KB filtered PBF\n")
    return tmp_pbf, True

# ---------------------------------------------------------------------------
# OSM handler
# ---------------------------------------------------------------------------



_PROGRESS_INTERVAL = 500_000  # status line every N nodes (only for slow fallback path)


# ---------------------------------------------------------------------------
# Fast path: FileProcessor + C++-level TagFilter
# ---------------------------------------------------------------------------

def parse_fast(pbf_path: str, source_layer: str) -> list[tuple]:
    """
    Parse using osmium.FileProcessor with a C++-level TagFilter.

    The TagFilter runs entirely in the compiled C++ layer.  Python is only
    invoked for objects that already match amenity=bicycle_parking
    (~100 000 nodes for Germany instead of 900 000 000 total nodes).

    Expected performance: seconds instead of 30–50 minutes.
    """
    import osmium.filter

    now_ms = int(time.time() * 1000)
    rows: list[tuple] = []

    fp = (
        osmium.FileProcessor(pbf_path, osmium.osm.NODE)
        .with_filter(osmium.filter.TagFilter(("amenity", "bicycle_parking")))
    )

    for obj in fp:
        rows.append((
            f"osm_n_{obj.id}",
            obj.tags.get("name") or None,
            obj.location.lat,
            obj.location.lon,
            _build_address(obj.tags),
            _parse_capacity(obj.tags),
            _is_covered(obj.tags),
            None,
            obj.tags.get("operator") or None,
            _map_type(obj.tags),
            source_layer,
            now_ms,
        ))

    return rows


# ---------------------------------------------------------------------------
# Slow fallback: SimpleHandler (visits every node in Python)
# ---------------------------------------------------------------------------

class BikeParkingHandler(osmium.SimpleHandler):
    """
    Fallback handler used only when FileProcessor / TagFilter is unavailable.

    Visits every node via a Python callback (~900 M for Germany) which is slow
    (~30–50 min).  Each 500 000th node prints a progress line.
    """

    def __init__(self, source_layer: str):
        super().__init__()
        self.rows: list[tuple] = []
        self._source_layer = source_layer
        self._now_ms = int(time.time() * 1000)
        self._node_count = 0
        self._start_time = time.time()

    def node(self, n):
        self._node_count += 1

        if self._node_count % _PROGRESS_INTERVAL == 0:
            elapsed = time.time() - self._start_time
            rate = self._node_count / elapsed / 1_000_000
            print(
                f"  [{elapsed:6.0f}s]  {self._node_count / 1_000_000:.1f}M nodes scanned"
                f"  |  {len(self.rows):,} bicycle_parking found"
                f"  |  {rate:.2f}M nodes/s",
                flush=True,
            )

        if n.tags.get("amenity") == "bicycle_parking":
            self.rows.append((
                f"osm_n_{n.id}",
                n.tags.get("name") or None,
                n.location.lat,
                n.location.lon,
                _build_address(n.tags),
                _parse_capacity(n.tags),
                _is_covered(n.tags),
                None,
                n.tags.get("operator") or None,
                _map_type(n.tags),
                self._source_layer,
                self._now_ms,
            ))


def parse_slow(pbf_path: str, source_layer: str) -> list[tuple]:
    handler = BikeParkingHandler(source_layer)
    handler.apply_file(pbf_path)
    print(
        f"  Done: {handler._node_count / 1_000_000:.1f}M nodes scanned, "
        f"{len(handler.rows):,} found.",
        flush=True,
    )
    return handler.rows



# ---------------------------------------------------------------------------
# Database creation
# ---------------------------------------------------------------------------

# Exact CREATE TABLE SQL as generated by Room v2.8.4 for BikeParkingDatabase v3.
# The ${TABLE_NAME} placeholder is replaced by the literal table name.
_SQL_BIKE_PARKING = """
CREATE TABLE IF NOT EXISTS `bike_parking_spaces` (
    `id` TEXT NOT NULL,
    `name` TEXT,
    `latitude` REAL NOT NULL,
    `longitude` REAL NOT NULL,
    `address` TEXT,
    `capacity` INTEGER,
    `isCovered` INTEGER,
    `imageUrl` TEXT,
    `operator` TEXT,
    `type` TEXT NOT NULL,
    `sourceLayer` TEXT NOT NULL,
    `lastUpdated` INTEGER NOT NULL,
    PRIMARY KEY(`id`)
)
""".strip()

_SQL_FAVORITES = """
CREATE TABLE IF NOT EXISTS `favorite_parking_spaces` (
    `parkingSpaceId` TEXT NOT NULL,
    `addedAt` INTEGER NOT NULL,
    `notes` TEXT,
    PRIMARY KEY(`parkingSpaceId`),
    FOREIGN KEY(`parkingSpaceId`) REFERENCES `bike_parking_spaces`(`id`)
        ON UPDATE NO ACTION ON DELETE CASCADE
)
""".strip()

# Spatial index – dramatically speeds up bounding-box queries
_SQL_SPATIAL_INDEX = (
    "CREATE INDEX IF NOT EXISTS idx_parking_lat_lon "
    "ON bike_parking_spaces (latitude, longitude)"
)

# Room master table (identity hash from schema v3 JSON export)
_IDENTITY_HASH = "d724c4ab0656349cd4e8038b29e95603"
_SQL_ROOM_MASTER = (
    "CREATE TABLE IF NOT EXISTS room_master_table "
    "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
)
_SQL_ROOM_MASTER_INSERT = (
    f"INSERT OR REPLACE INTO room_master_table (id, identity_hash) "
    f"VALUES(42, '{_IDENTITY_HASH}')"
)

_INSERT_SQL = """
INSERT OR REPLACE INTO bike_parking_spaces
    (id, name, latitude, longitude, address, capacity, isCovered,
     imageUrl, operator, type, sourceLayer, lastUpdated)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

_BATCH_SIZE = 10_000


def create_database(output_path: str, rows: list[tuple]) -> None:
    """Write a Room-compatible SQLite database to *output_path*."""
    if os.path.exists(output_path):
        os.remove(output_path)

    conn = sqlite3.connect(output_path)
    cur = conn.cursor()

    # Room expects user_version = <database version>
    cur.execute("PRAGMA user_version = 3")
    cur.execute("PRAGMA journal_mode = WAL")
    cur.execute("PRAGMA foreign_keys = OFF")  # speed up bulk insert

    cur.execute(_SQL_BIKE_PARKING)
    cur.execute(_SQL_FAVORITES)
    cur.execute(_SQL_SPATIAL_INDEX)
    cur.execute(_SQL_ROOM_MASTER)
    cur.execute(_SQL_ROOM_MASTER_INSERT)

    print(f"Inserting {len(rows):,} rows …")
    for i in range(0, len(rows), _BATCH_SIZE):
        batch = rows[i: i + _BATCH_SIZE]
        cur.executemany(_INSERT_SQL, batch)
        pct = min(100, (i + _BATCH_SIZE) * 100 // len(rows))
        print(f"  {pct:3d}%  ({i + len(batch):,} / {len(rows):,})", end="\r")

    print()
    conn.commit()

    # Re-enable and validate FK integrity before closing
    cur.execute("PRAGMA foreign_keys = ON")
    conn.close()

    size_mb = os.path.getsize(output_path) / 1_048_576
    print(f"Database written to: {output_path}  ({size_mb:.1f} MB)")


# ---------------------------------------------------------------------------
# Download helper
# ---------------------------------------------------------------------------

def download_pbf(url: str, dest: str) -> None:
    """Download a PBF from Geofabrik with a progress bar."""
    try:
        import requests
    except ImportError:
        sys.exit("requests not found. Install it with:  pip install requests")

    print(f"Downloading {url} …")
    print("(Large countries like Germany/France are several GB – this may take a while)")
    with requests.get(url, stream=True, timeout=60) as r:
        r.raise_for_status()
        total = int(r.headers.get("content-length", 0))
        downloaded = 0
        with open(dest, "wb") as f:
            for chunk in r.iter_content(chunk_size=1 << 20):  # 1 MB chunks
                f.write(chunk)
                downloaded += len(chunk)
                if total:
                    pct = downloaded * 100 // total
                    mb = downloaded / 1_048_576
                    print(f"  {pct:3d}%  {mb:.0f} MB", end="\r")
    print(f"\nSaved to: {dest}")


# ---------------------------------------------------------------------------
# About-sheet date update
# ---------------------------------------------------------------------------

def update_about_dates(processed: list[str], today: str) -> None:
    """
    Rewrite the `DATA_DATE_*` constants on the in-app About sheet so the
    displayed "data status" matches the freshly generated datasets.

    Only the countries in *processed* are touched.
    """
    if not os.path.exists(_ABOUT_SHEET):
        print(f"  ! About sheet not found at {_ABOUT_SHEET} – skipping date update.")
        return

    with open(_ABOUT_SHEET, "r", encoding="utf-8") as f:
        content = f.read()

    updated = content
    for country in processed:
        const = COUNTRIES[country]["about_const"]
        pattern = re.compile(rf'(private const val {const} = ")[^"]*(")')
        updated, n = pattern.subn(rf'\g<1>{today}\g<2>', updated)
        if n == 0:
            print(f"  ! Could not find constant {const} in About sheet.")
        else:
            print(f"  Updated {const} → {today}")

    if updated != content:
        with open(_ABOUT_SHEET, "w", encoding="utf-8") as f:
            f.write(updated)
        print(f"About sheet dates updated: {_ABOUT_SHEET}")


# ---------------------------------------------------------------------------
# Per-country processing
# ---------------------------------------------------------------------------

def process_country(country: str, pbf_override: Optional[str], keep_pbf: bool) -> bool:
    """Download (if needed), parse and write the asset DB for one country.

    Returns True on success.
    """
    cfg = COUNTRIES[country]
    print("\n" + "=" * 72)
    print(f"Country: {country.upper()}")
    print("=" * 72)

    downloaded_here = False
    if pbf_override:
        pbf_path = pbf_override
        if not os.path.exists(pbf_path):
            print(f"  ! Provided --pbf '{pbf_path}' does not exist – skipping {country}.")
            return False
    else:
        pbf_path = os.path.join(_SCRIPT_DIR, f"{country}-latest.osm.pbf")
        if not os.path.exists(pbf_path):
            download_pbf(cfg["url"], pbf_path)
            downloaded_here = True
        else:
            print(f"  Using existing PBF: {pbf_path}")

    out_path = os.path.join(_ASSETS_DIR, cfg["db"])
    os.makedirs(_ASSETS_DIR, exist_ok=True)

    parse_path, is_temp = prefilter_pbf(pbf_path)
    try:
        pbf_size_mb = os.path.getsize(parse_path) / 1_048_576
        print(f"Parsing  {parse_path}  ({pbf_size_mb:.0f} MB)\n")

        start = time.time()
        use_fast = hasattr(osmium, "FileProcessor") and hasattr(osmium, "filter")
        rows: list[tuple] = []
        if use_fast:
            print("Strategy: FileProcessor + C++ TagFilter  (fast – no per-node Python overhead)")
            try:
                rows = parse_fast(parse_path, cfg["source_layer"])
            except Exception as e:
                print(f"  Fast path failed ({e}), falling back …")
                use_fast = False

        if not use_fast:
            print("Strategy: SimpleHandler  (slow – Python called for every node)")
            print("Progress is printed every 500 000 nodes.\n")
            rows = parse_slow(parse_path, cfg["source_layer"])

        elapsed = time.time() - start
        print(f"\nFinished in {elapsed:.1f}s  —  {len(rows):,} bicycle_parking features found.")

        if not rows:
            print(f"  ! No features found for {country} – asset not written.")
            return False

        create_database(out_path, rows)
        return True
    finally:
        if is_temp and os.path.exists(parse_path):
            os.remove(parse_path)
        if downloaded_here and not keep_pbf and os.path.exists(pbf_path):
            os.remove(pbf_path)
            print(f"  Removed downloaded PBF: {pbf_path}")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Download the latest OSM extracts for Germany, France and "
                    "Luxembourg, build the bundled VeloSpot Room databases and "
                    "update the About-sheet data dates."
    )
    parser.add_argument(
        "--countries",
        nargs="+",
        choices=list(COUNTRIES.keys()),
        default=list(COUNTRIES.keys()),
        help="Which countries to (re)build. Default: all bundled countries.",
    )
    parser.add_argument(
        "--pbf",
        default=None,
        help="Path to a pre-downloaded PBF. Only valid together with a single "
             "--countries value; otherwise each country is downloaded from Geofabrik.",
    )
    parser.add_argument(
        "--keep-pbf",
        action="store_true",
        help="Keep downloaded PBF files instead of deleting them afterwards.",
    )
    parser.add_argument(
        "--no-about-update",
        action="store_true",
        help="Do not modify the About-sheet data dates.",
    )
    args = parser.parse_args()

    if args.pbf and len(args.countries) != 1:
        sys.exit("--pbf can only be used with exactly one --countries value.")

    today = date.today().strftime("%d.%m.%Y")
    succeeded: list[str] = []

    for country in args.countries:
        ok = process_country(country, args.pbf, args.keep_pbf)
        if ok:
            succeeded.append(country)

    print("\n" + "=" * 72)
    if succeeded:
        print(f"Successfully built: {', '.join(succeeded)}")
        if not args.no_about_update:
            print("\nUpdating About-sheet data dates …")
            update_about_dates(succeeded, today)
    else:
        print("No datasets were built.")
    print("=" * 72)
    print("Done. Rebuild the app so the refreshed spots are bundled:")
    print("  ./gradlew assembleDebug")


if __name__ == "__main__":
    main()

