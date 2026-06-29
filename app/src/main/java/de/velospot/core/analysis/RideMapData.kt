package de.velospot.core.analysis

import de.velospot.core.map.RideMaxSpeedPoint
import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint

/** A bare WGS84 coordinate, used by the analysis map layer. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** Kind of point-of-interest drawn on the ride analysis map. */
enum class RideMarkerType { START, FINISH, TOP_SPEED, STOP, KILOMETRE }

/** A single marker on the analysis map (optionally carrying a short label). */
data class RideMarker(
    val point: GeoPoint,
    val type: RideMarkerType,
    /** Short label, e.g. the kilometre number; `null` for unlabelled markers. */
    val label: String? = null
)

/**
 * Everything the **ride analysis map** needs, derived purely from the captured
 * track (no Android dependency, fully JVM-unit-testable, built off the main
 * thread): the polyline, the points-of-interest to mark, and an evenly-time-spaced
 * set of positions driving the **animated replay** dot.
 *
 * Because the replay frames are sampled by **elapsed time** (not distance), the dot
 * naturally **slows and pauses** where the rider did — a stop reads as the dot
 * lingering in place.
 */
data class RideMapData(
    val track: List<GeoPoint>,
    val markers: List<RideMarker>,
    val replayFrames: List<GeoPoint>
)

private const val STOP_SPEED_MPS = 0.7
private const val MIN_STOP_SECONDS = 5L
private const val MAX_GAP_MILLIS = 60_000L

/**
 * Builds the [RideMapData] for [ride]. [replayFrameCount] controls how many
 * positions the replay animation steps through (more = smoother, slightly more
 * work). Returns empty marker/frame lists for a degenerate (< 2 point) ride.
 */
fun buildRideMapData(ride: RecordedRide, replayFrameCount: Int = 600): RideMapData {
    val pts = ride.points
    val track = pts.map { GeoPoint(it.latitude, it.longitude) }
    if (pts.size < 2) return RideMapData(track, emptyList(), emptyList())

    val markers = ArrayList<RideMarker>()
    markers += RideMarker(track.first(), RideMarkerType.START)
    markers += RideMarker(track.last(), RideMarkerType.FINISH)
    RideMaxSpeedPoint.find(ride)?.let {
        markers += RideMarker(GeoPoint(it.latitude, it.longitude), RideMarkerType.TOP_SPEED)
    }
    markers += buildStopMarkers(pts)

    return RideMapData(track, markers, buildReplayFrames(pts, replayFrameCount))
}


/** A marker at the midpoint of every sustained standstill on the ride. */
private fun buildStopMarkers(pts: List<TrackPoint>): List<RideMarker> {
    val out = ArrayList<RideMarker>()
    var runStart = -1
    var runEnd = -1
    var runMillis = 0L

    fun flush() {
        if (runStart >= 0 && runMillis >= MIN_STOP_SECONDS * 1000) {
            val mid = pts[(runStart + runEnd) / 2]
            out += RideMarker(GeoPoint(mid.latitude, mid.longitude), RideMarkerType.STOP)
        }
        runStart = -1; runEnd = -1; runMillis = 0L
    }

    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        val dt = b.timestamp - a.timestamp
        if (dt !in 1..MAX_GAP_MILLIS) { flush(); continue }
        val seg = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        val speed = a.speedMps?.toDouble() ?: (seg / (dt / 1000.0))
        if (speed < STOP_SPEED_MPS) {
            if (runStart < 0) runStart = i - 1
            runEnd = i
            runMillis += dt
        } else {
            flush()
        }
    }
    flush()
    return out
}

/**
 * Samples [frameCount] positions evenly spaced in **elapsed ride time** (GPS gaps
 * capped), interpolating between fixes. Falls back to even **distance** sampling
 * when the track carries no usable timing.
 */
private fun buildReplayFrames(pts: List<TrackPoint>, frameCount: Int): List<GeoPoint> {
    val n = pts.size
    if (n < 2 || frameCount < 2) return pts.map { GeoPoint(it.latitude, it.longitude) }

    val cum = DoubleArray(n)
    for (i in 1 until n) {
        val dt = (pts[i].timestamp - pts[i - 1].timestamp).coerceIn(0L, MAX_GAP_MILLIS).toDouble()
        cum[i] = cum[i - 1] + dt
    }
    var total = cum[n - 1]
    val byDistance = total <= 0.0
    if (byDistance) {
        // No timing → distribute by cumulative distance instead.
        for (i in 1 until n) {
            val a = pts[i - 1]; val b = pts[i]
            cum[i] = cum[i - 1] + GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        total = cum[n - 1]
    }
    if (total <= 0.0) return pts.map { GeoPoint(it.latitude, it.longitude) }

    val frames = ArrayList<GeoPoint>(frameCount)
    var seg = 1
    for (fIdx in 0 until frameCount) {
        val target = fIdx.toDouble() / (frameCount - 1) * total
        while (seg < n && cum[seg] < target) seg++
        if (seg >= n) {
            val last = pts[n - 1]
            frames += GeoPoint(last.latitude, last.longitude)
            continue
        }
        val t0 = cum[seg - 1]; val t1 = cum[seg]
        val f = if (t1 > t0) (target - t0) / (t1 - t0) else 0.0
        val a = pts[seg - 1]; val b = pts[seg]
        frames += GeoPoint(
            latitude = GeoMath.lerp(a.latitude, b.latitude, f),
            longitude = GeoMath.lerp(a.longitude, b.longitude, f)
        )
    }
    return frames
}

