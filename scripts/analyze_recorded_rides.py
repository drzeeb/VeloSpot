#!/usr/bin/env python3
"""
Analyse recorded VeloSpot rides for GPS outliers and threshold tuning.

Reads the app's ``velospot_rides`` Room database (table ``recorded_rides``) and,
for the most recent rides, reports the distributions that drive the GPS-filter
thresholds in ``RideTracker.kt``:

  * reported horizontal accuracy (drives ``MAX_ACCURACY_METERS``)
  * position-derived segment speed        (drives ``MAX_PLAUSIBLE_SPEED_MPS``)
  * segment-to-segment acceleration       (drives ``MAX_PLAUSIBLE_ACCEL_MPS2``)
  * inter-fix interval                     (drives ``MIN_FIX_INTERVAL_MILLIS``)
  * altitude jumps / vertical velocity     (drives ``MAX_ALTITUDE_STEP_METERS``)

It also shows the effect of the altitude-outlier gate on the recorded ascent so a
threshold can be picked with evidence. No coordinates are printed — only
aggregate statistics — so the output carries no personal location data.

Pull the database from a connected, debuggable device with adb:

    adb exec-out run-as de.velospot cat databases/velospot_rides.db > rides.db
    # (also pull the -wal / -shm files if you want the very latest, un-checkpointed rows)

Then run:

    python analyze_recorded_rides.py --db rides.db --limit 3

This is a read-only, offline analysis tool; it never modifies the database.
"""
from __future__ import annotations

import argparse
import json
import math
import sqlite3
import statistics
from datetime import datetime

# Mirrors of the constants in
# app/src/main/java/de/velospot/core/tracking/RideTracker.kt — kept here only so
# the report can flag how many samples each gate would reject.
MAX_ACCURACY_METERS = 25.0
MAX_PLAUSIBLE_SPEED_MPS = 22.0
MAX_PLAUSIBLE_ACCEL_MPS2 = 4.0
MIN_FIX_INTERVAL_MILLIS = 250
MAX_ALTITUDE_STEP_METERS = 12.0
ALT_SMOOTHING_ALPHA = 0.3
ELEVATION_THRESHOLD_METERS = 3.0


def haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in metres."""
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def pctl(data: list[float], q: float) -> float:
    if not data:
        return float("nan")
    s = sorted(data)
    k = (len(s) - 1) * q
    f, c = math.floor(k), math.ceil(k)
    if f == c:
        return s[int(k)]
    return s[f] * (c - k) + s[c] * (k - f)


def recompute_elevation(points: list[dict], clamp: float | None) -> tuple[float, float]:
    """Re-derive ascent/descent, optionally rejecting altitude spikes > ``clamp``."""
    gain = loss = 0.0
    smoothed = base = None
    for p in points:
        alt = p.get("altitudeMeters")
        if alt is None:
            continue
        if smoothed is not None and clamp is not None and abs(alt - smoothed) > clamp:
            continue  # implausible altitude spike — ignored
        smoothed = alt if smoothed is None else smoothed + ALT_SMOOTHING_ALPHA * (alt - smoothed)
        if base is None:
            base = smoothed
        else:
            d = smoothed - base
            if abs(d) >= ELEVATION_THRESHOLD_METERS:
                gain += d if d > 0 else 0.0
                loss += -d if d < 0 else 0.0
                base = smoothed
    return gain, loss


def analyse_ride(row: sqlite3.Row) -> None:
    pts = json.loads(row["pointsJson"])
    n = len(pts)
    started = datetime.fromtimestamp(row["startedAt"] / 1000).strftime("%Y-%m-%d %H:%M")
    print("=" * 72)
    print(f"ride {row['id'][:8]}  {started}  mock={row['isMock']}  points={n}")
    print(f"  stored: dist={row['distanceMeters']:.0f} m  "
          f"maxSpeed={row['maxSpeedMps']:.1f} m/s  "
          f"gain/loss={row['elevationGainMeters']:.0f}/{row['elevationLossMeters']:.0f} m")
    if n < 2:
        return

    accs = [p["accuracyMeters"] for p in pts if p.get("accuracyMeters") is not None]
    seg_speeds, accels, dts, vert_vel, alt_jumps = [], [], [], [], []
    bursts = 0
    prev = prev_v = None
    for p in pts:
        if prev is not None:
            dtms = p["timestamp"] - prev["timestamp"]
            dts.append(dtms)
            if dtms < MIN_FIX_INTERVAL_MILLIS:
                bursts += 1
            if dtms > 0:
                v = haversine(prev["latitude"], prev["longitude"], p["latitude"], p["longitude"]) / (dtms / 1000.0)
                seg_speeds.append(v)
                if prev_v is not None:
                    accels.append(abs(v - prev_v) / (dtms / 1000.0))
                prev_v = v
                a0, a1 = prev.get("altitudeMeters"), p.get("altitudeMeters")
                if a0 is not None and a1 is not None:
                    alt_jumps.append(abs(a1 - a0))
                    vert_vel.append(abs(a1 - a0) / (dtms / 1000.0))
        prev = p

    if accs:
        over = sum(1 for a in accs if a > MAX_ACCURACY_METERS)
        print(f"  accuracy(m): med={statistics.median(accs):.1f} p99={pctl(accs, 0.99):.1f} "
              f"max={max(accs):.1f}  > {MAX_ACCURACY_METERS:.0f}m rejects {over}")
    if seg_speeds:
        over = sum(1 for v in seg_speeds if v > MAX_PLAUSIBLE_SPEED_MPS)
        print(f"  segSpeed(m/s): med={statistics.median(seg_speeds):.1f} p99={pctl(seg_speeds, 0.99):.1f} "
              f"max={max(seg_speeds):.1f}  > {MAX_PLAUSIBLE_SPEED_MPS:.0f} rejects {over}")
    if accels:
        over = sum(1 for a in accels if a > MAX_PLAUSIBLE_ACCEL_MPS2)
        print(f"  |accel|(m/s^2): p95={pctl(accels, 0.95):.2f} max={max(accels):.1f}  "
              f"> {MAX_PLAUSIBLE_ACCEL_MPS2:.0f} rejects {over}")
    if dts:
        print(f"  fixInterval(ms): med={int(statistics.median(dts))} min={min(dts)}  "
              f"bursts < {MIN_FIX_INTERVAL_MILLIS}ms: {bursts}")
    if vert_vel:
        print(f"  vertVel(m/s): p99={pctl(vert_vel, 0.99):.1f} max={max(vert_vel):.1f}  "
              f"altJump > {MAX_ALTITUDE_STEP_METERS:.0f}m: {sum(1 for j in alt_jumps if j > MAX_ALTITUDE_STEP_METERS)}")

    raw = recompute_elevation(pts, clamp=None)
    gated = recompute_elevation(pts, clamp=MAX_ALTITUDE_STEP_METERS)
    print(f"  elevation gain/loss  no gate: {raw[0]:.0f}/{raw[1]:.0f} m  "
          f"-> with {MAX_ALTITUDE_STEP_METERS:.0f}m gate: {gated[0]:.0f}/{gated[1]:.0f} m")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default="rides.db", help="path to the pulled velospot_rides.db (default: rides.db)")
    ap.add_argument("--limit", type=int, default=3, help="how many of the most recent rides to analyse (default: 3)")
    args = ap.parse_args()

    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row
    total = con.execute("SELECT COUNT(*) FROM recorded_rides").fetchone()[0]
    print(f"database: {args.db}  total rides: {total}  analysing latest {args.limit}\n")
    rows = con.execute(
        "SELECT id, startedAt, distanceMeters, maxSpeedMps, elevationGainMeters, "
        "elevationLossMeters, isMock, pointsJson "
        "FROM recorded_rides ORDER BY startedAt DESC LIMIT ?",
        (args.limit,),
    ).fetchall()
    for row in rows:
        analyse_ride(row)
    con.close()


if __name__ == "__main__":
    main()

