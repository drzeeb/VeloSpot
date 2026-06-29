package de.velospot.core.analysis

import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import de.velospot.core.map.RideMaxSpeedPoint
import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import kotlin.math.roundToInt

/** A bare WGS84 coordinate, used by the analysis map layer. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** Kind of point-of-interest drawn on the ride analysis map. */
enum class RideMarkerType { START, FINISH, TOP_SPEED, MAX_GRADIENT, HIGH_POINT, STOP }

/** A single marker on the analysis map (optionally carrying a short value label). */
data class RideMarker(
    val point: GeoPoint,
    val type: RideMarkerType,
    /** Short value label (e.g. `"31 km/h"`, `"12%"`, `"1:20"`); `null` for plain dots. */
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
private const val MAX_GAP_MILLIS = 60_000L
/** Only stops at least this long get a map marker (shorter ones would clutter). */
private const val MIN_STOP_MARKER_SECONDS = 20L
/** Window length (m) over which the steepest sustained gradient is measured. */
private const val GRADIENT_WINDOW_METERS = 100.0
/** Don't mark a "steepest point" below this gradient (a flat ride has none). */
private const val MIN_MARKED_GRADIENT_PERCENT = 4.0
/** Don't mark a "high point" unless the ride climbs at least this much overall. */
private const val MIN_ELEVATION_RANGE_METERS = 20.0

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
        markers += RideMarker(
            point = GeoPoint(it.latitude, it.longitude),
            type = RideMarkerType.TOP_SPEED,
            label = formatRideSpeed(ride.maxSpeedMps)
        )
    }
    buildMaxGradientMarker(pts)?.let { markers += it }
    buildHighPointMarker(pts)?.let { markers += it }
    markers += buildStopMarkers(pts)

    return RideMapData(track, markers, buildReplayFrames(pts, replayFrameCount))
}

/** The steepest sustained (~[GRADIENT_WINDOW_METERS]) uphill stretch, with its %. */
private fun buildMaxGradientMarker(pts: List<TrackPoint>): RideMarker? {
    var bestGradient = 0.0
    var bestPoint: TrackPoint? = null
    for (i in pts.indices) {
        if (pts[i].altitudeMeters == null) continue
        var windowM = 0.0
        var gain = 0.0
        var k = i + 1
        while (k < pts.size && windowM < GRADIENT_WINDOW_METERS) {
            val a = pts[k - 1]; val b = pts[k]
            windowM += GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val altA = a.altitudeMeters; val altB = b.altitudeMeters
            if (altA != null && altB != null) gain += altB - altA
            k++
        }
        if (windowM >= GRADIENT_WINDOW_METERS * 0.5) {
            val gradient = gain / windowM * 100.0
            if (gradient > bestGradient) {
                bestGradient = gradient
                bestPoint = pts[(i + k) / 2] // middle of the window
            }
        }
    }
    val p = bestPoint ?: return null
    if (bestGradient < MIN_MARKED_GRADIENT_PERCENT) return null
    return RideMarker(
        point = GeoPoint(p.latitude, p.longitude),
        type = RideMarkerType.MAX_GRADIENT,
        label = "${bestGradient.roundToInt()}%"
    )
}

/** The highest point of the ride (when it has meaningful elevation variation). */
private fun buildHighPointMarker(pts: List<TrackPoint>): RideMarker? {
    val withAlt = pts.filter { it.altitudeMeters != null }
    if (withAlt.size < 2) return null
    val highest = withAlt.maxByOrNull { it.altitudeMeters!! } ?: return null
    val highestAlt = highest.altitudeMeters ?: return null
    val lowest = withAlt.minOf { it.altitudeMeters!! }
    if (highestAlt - lowest < MIN_ELEVATION_RANGE_METERS) return null
    return RideMarker(
        point = GeoPoint(highest.latitude, highest.longitude),
        type = RideMarkerType.HIGH_POINT,
        label = formatRideElevation(highestAlt)
    )
}

/** A marker (with its duration) at the midpoint of every notable standstill. */
private fun buildStopMarkers(pts: List<TrackPoint>): List<RideMarker> {
    val out = ArrayList<RideMarker>()
    var runStart = -1
    var runEnd = -1
    var runMillis = 0L

    fun flush() {
        if (runStart >= 0 && runMillis >= MIN_STOP_MARKER_SECONDS * 1000) {
            val mid = pts[(runStart + runEnd) / 2]
            out += RideMarker(
                point = GeoPoint(mid.latitude, mid.longitude),
                type = RideMarkerType.STOP,
                label = formatRideDuration(runMillis / 1000)
            )
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

