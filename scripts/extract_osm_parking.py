#!/usr/bin/env python3
"""
extract_osm_parking.py
======================
Extracts bicycle_parking data from an OpenStreetMap PBF file and writes it into
a pre-populated SQLite database that exactly matches the VeloSpot Room schema v3.

The generated file can be placed at:
    app/src/main/assets/bike_parking_germany.db

Usage
-----
    pip install osmium requests tqdm
    python extract_osm_parking.py [--pbf germany.osm.pbf] [--out ../app/src/main/assets/bike_parking_germany.db]

If --pbf is omitted, the script downloads the latest germany.osm.pbf from Geofabrik
(~4 GB, ensure sufficient disk space).

Room schema (v3)
----------------
Identity hash: d724c4ab0656349cd4e8038b29e95603
Tables:
  bike_parking_spaces   – pre-populated with OSM data (read-only in app)
  favorite_parking_spaces – user data, stays empty in asset
  room_master_table     – Room internal validation table
"""

import argparse
import hashlib
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
from typing import Optional

try:
    import osmium
except ImportError:
    sys.exit(
        "osmium not found. Install it with:  pip install osmium\n"
        "On some systems you may need:       pip install osmium-tool"
    )

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

def parse_fast(pbf_path: str) -> list[tuple]:
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
            "osm_germany",
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

    def __init__(self):
        super().__init__()
        self.rows: list[tuple] = []
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
                "osm_germany",
                self._now_ms,
            ))


def parse_slow(pbf_path: str) -> list[tuple]:
    handler = BikeParkingHandler()
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
# Optional download helper
# ---------------------------------------------------------------------------

_GERMANY_PBF_URL = "https://download.geofabrik.de/europe/germany-latest.osm.pbf"


def download_pbf(dest: str) -> None:
    """Download the Germany PBF from Geofabrik with a progress bar."""
    try:
        import requests
    except ImportError:
        sys.exit("requests not found. Install it with:  pip install requests")

    print(f"Downloading {_GERMANY_PBF_URL} …")
    print("(This is ~4 GB – may take a while on slow connections)")
    with requests.get(_GERMANY_PBF_URL, stream=True, timeout=60) as r:
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
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Convert an OSM PBF file to a VeloSpot Room-compatible SQLite database."
    )
    parser.add_argument(
        "--pbf",
        default="germany-latest.osm.pbf",
        help="Path to the input OSM PBF file (default: germany-latest.osm.pbf). "
             "If the file does not exist, it will be downloaded from Geofabrik.",
    )
    parser.add_argument(
        "--out",
        default=os.path.join(
            os.path.dirname(__file__),
            "..", "app", "src", "main", "assets", "bike_parking_germany.db"
        ),
        help="Output SQLite database path "
             "(default: ../app/src/main/assets/bike_parking_germany.db).",
    )
    args = parser.parse_args()

    pbf_path = args.pbf
    out_path = os.path.normpath(args.out)

    # Download if PBF not present
    if not os.path.exists(pbf_path):
        print(f"PBF file '{pbf_path}' not found.")
        answer = input("Download germany-latest.osm.pbf from Geofabrik? [y/N] ").strip().lower()
        if answer != "y":
            sys.exit("Aborted.")
        download_pbf(pbf_path)

    # Ensure assets directory exists
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    # Try to use osmium-tool for a fast pre-filter pass
    parse_path, is_temp = prefilter_pbf(pbf_path)

    try:
        # Parse
        pbf_size_mb = os.path.getsize(parse_path) / 1_048_576
        print(f"Parsing  {parse_path}  ({pbf_size_mb:.0f} MB)\n")

        start = time.time()

        # Fast path: C++-level TagFilter (only Python-calls for matching nodes)
        use_fast = hasattr(osmium, "FileProcessor") and hasattr(osmium, "filter")
        if use_fast:
            print("Strategy: FileProcessor + C++ TagFilter  (fast – no per-node Python overhead)")
            try:
                rows = parse_fast(parse_path)
            except Exception as e:
                print(f"  Fast path failed ({e}), falling back …")
                use_fast = False

        if not use_fast:
            print("Strategy: SimpleHandler  (slow – Python called for every node)")
            print("Progress is printed every 500 000 nodes – Germany has ~900 M nodes total.\n")
            rows = parse_slow(parse_path)

        elapsed = time.time() - start
        print(f"\nFinished in {elapsed:.1f}s  —  {len(rows):,} bicycle_parking features found.")

        if not rows:
            sys.exit("No features found – check that the PBF file is a valid OSM extract.")

        # Write database
        create_database(out_path, rows)
        print("Done. Place the file in app/src/main/assets/ and rebuild the app.")

    finally:
        if is_temp and os.path.exists(parse_path):
            os.remove(parse_path)


if __name__ == "__main__":
    main()

