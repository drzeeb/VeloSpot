package de.velospot.core.navigation

import de.velospot.domain.model.RoutePoint
import kotlin.math.abs

/**
 * Snaps raw GPS fixes onto the active BRouter polyline ("map matching") and
 * derives the data the camera needs: the on-route position, the heading along
 * the route, the remaining distance and how sharp the next turn is.
 *
 * Pure and Android-free → unit testable. State (the last matched segment) is
 * passed in/out via [Match.segmentIndex] so the caller can keep matching forward
 * along the route and avoid snapping back to an earlier, geometrically-close
 * segment (e.g. on a hairpin or a road crossing itself).
 */
internal object RouteMatcher {

    /**
     * @property latitude Snapped latitude on the route.
     * @property longitude Snapped longitude on the route.
     * @property bearing Heading of the matched segment in degrees `[0, 360)`.
     * @property segmentIndex Index of the matched segment's start vertex.
     * @property t Position within the matched segment (0..1).
     * @property distanceFromRouteMeters Perpendicular distance of the raw fix
     *  from the route — large values indicate the user is off-route.
     * @property remainingMeters Distance from the snapped point to the
     *  destination along the route.
     * @property turnSharpnessDegrees Sharpness of the sharpest maneuver whose
     *  apex falls within the next [TURN_LOOKAHEAD_METERS], measured as the
     *  **cumulative** heading change over the whole corner (so a rounded curve
     *  spread over several vertices reads as one real turn, not a series of tiny
     *  bends); `0` on straight stretches, up to `180` for a U-turn. Drives the
     *  "zoom in before a turn" behaviour.
     */
    data class Match(
        val latitude: Double,
        val longitude: Double,
        val bearing: Double,
        val segmentIndex: Int,
        val t: Double,
        val distanceFromRouteMeters: Double,
        val remainingMeters: Double,
        val turnSharpnessDegrees: Double
    )

    /** How far ahead we look to detect an upcoming turn. */
    const val TURN_LOOKAHEAD_METERS = 35.0

    /**
     * Next-turn detection for the turn-by-turn banner.
     * @property distanceMeters distance from the snapped point to the turn's apex
     *  (the vertex of maximum curvature within the corner).
     * @property angleDegrees signed **cumulative** heading change across the whole
     *  corner: negative = left, positive = right.
     */
    data class TurnHint(val distanceMeters: Double, val angleDegrees: Double)

    /**
     * Cumulative heading change (deg) across a corner that counts as a real turn.
     * A rounded 90° corner emitted by BRouter as 4–6 vertices of ~15–20° each has
     * no single vertex above this, but its accumulated total does — so it still
     * fires exactly one maneuver instead of being silently skipped.
     */
    private const val TURN_MIN_ANGLE_DEG = 32.0
    /** Don't look further than this for the next turn (keeps the banner relevant). */
    private const val NEXT_TURN_MAX_DISTANCE_M = 500.0

    /**
     * Per-vertex heading change (deg) below which a vertex is treated as
     * effectively "straight" — GPS/geometry jitter that must not open or feed a
     * corner accumulation on its own.
     */
    private const val TURN_VERTEX_NOISE_DEG = 6.0

    /**
     * Once a corner accumulation is open, this much along-route distance (m) of
     * near-straight travel closes it. Keeps the vertices of one rounded corner
     * grouped into a single maneuver while preventing an unrelated later bend
     * from being merged into the same turn.
     */
    private const val TURN_ACCUMULATE_GAP_M = 20.0

    /**
     * Look-ahead distance for the camera/marker heading. The route heading is
     * taken from the snapped point towards a vertex at least this far along the
     * route, instead of from the single matched segment — so a degenerate sub-metre
     * stub (BRouter occasionally emits one at the start) can't yield a skewed
     * heading. Small enough not to noticeably anticipate real turns.
     */
    private const val BEARING_LOOKAHEAD_METERS = 15.0

    /**
     * Only segments within `[fromSegment, fromSegment + SEARCH_WINDOW]` are
     * considered, plus a small look-back, so matching stays forward-biased and
     * O(window) instead of O(route length) on every fix.
     */
    private const val SEARCH_WINDOW = 60
    private const val SEARCH_LOOKBACK = 4

    /**
     * Matches ([lat], [lon]) to [points], searching forward from [fromSegment].
     *
     * @return the [Match], or `null` when the route has fewer than two points.
     */
    fun match(
        points: List<RoutePoint>,
        lat: Double,
        lon: Double,
        fromSegment: Int = 0
    ): Match? {
        if (points.size < 2) return null

        val start = (fromSegment - SEARCH_LOOKBACK).coerceAtLeast(0)
        val end = (fromSegment + SEARCH_WINDOW).coerceAtMost(points.size - 2)

        var bestIdx = start
        var bestProj = GeoMath.projectOntoSegment(
            lat, lon,
            points[start].latitude, points[start].longitude,
            points[start + 1].latitude, points[start + 1].longitude
        )

        for (i in (start + 1)..end) {
            val proj = GeoMath.projectOntoSegment(
                lat, lon,
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
            if (proj.distanceMeters < bestProj.distanceMeters) {
                bestProj = proj
                bestIdx = i
            }
        }

        val bearing = forwardBearing(points, bestIdx, bestProj.latitude, bestProj.longitude)

        return Match(
            latitude = bestProj.latitude,
            longitude = bestProj.longitude,
            bearing = bearing,
            segmentIndex = bestIdx,
            t = bestProj.t,
            distanceFromRouteMeters = bestProj.distanceMeters,
            remainingMeters = remainingMeters(points, bestIdx, bestProj.t),
            turnSharpnessDegrees = turnSharpness(points, bestIdx, bestProj.t)
        )
    }

    /**
     * Stable forward heading from the snapped point ([snapLat]/[snapLon] on
     * segment [index]). Walks the route until a vertex at least
     * [BEARING_LOOKAHEAD_METERS] ahead and returns the bearing to it, so tiny
     * (sub-metre) segments don't skew the heading. Falls back to the matched
     * segment's own bearing for a degenerate route end.
     */
    private fun forwardBearing(
        points: List<RoutePoint>,
        index: Int,
        snapLat: Double,
        snapLon: Double
    ): Double {
        var prevLat = snapLat
        var prevLon = snapLon
        var cumulative = 0.0
        var targetLat = points[index + 1].latitude
        var targetLon = points[index + 1].longitude
        var i = index + 1
        while (i < points.size) {
            targetLat = points[i].latitude
            targetLon = points[i].longitude
            cumulative += GeoMath.distanceMeters(prevLat, prevLon, targetLat, targetLon)
            if (cumulative >= BEARING_LOOKAHEAD_METERS) break
            prevLat = targetLat
            prevLon = targetLon
            i++
        }
        // Guard against a zero-length result (snapped right on the target vertex).
        if (GeoMath.distanceMeters(snapLat, snapLon, targetLat, targetLon) < 0.5) {
            return GeoMath.bearingDegrees(
                points[index].latitude, points[index].longitude,
                points[index + 1].latitude, points[index + 1].longitude
            )
        }
        return GeoMath.bearingDegrees(snapLat, snapLon, targetLat, targetLon)
    }

    /** Distance from the snapped point (segment [index], fraction [t]) to the route end. */
    fun remainingMeters(points: List<RoutePoint>, index: Int, t: Double): Double {        if (points.size < 2) return 0.0
        val a = points[index]
        val b = points[index + 1]
        val segLen = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        var total = segLen * (1.0 - t)
        for (i in index + 1 until points.size - 1) {
            total += GeoMath.distanceMeters(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
        }
        return total
    }

    /**
     * Sharpness of the sharpest upcoming maneuver whose apex lies within
     * [TURN_LOOKAHEAD_METERS] of the snapped point, measured as the cumulative
     * heading change over the whole corner (see [scanManeuvers]). `0` when the
     * road runs straight ahead.
     */
    private fun turnSharpness(points: List<RoutePoint>, index: Int, t: Double): Double =
        scanManeuvers(points, index, t, TURN_LOOKAHEAD_METERS)
            .maxOfOrNull { abs(it.angleDegrees) } ?: 0.0

    /**
     * A detected turn: its apex distance from the snapped point and its signed
     * cumulative heading change (negative = left, positive = right).
     */
    private data class Maneuver(val distanceMeters: Double, val angleDegrees: Double)

    /**
     * Scans the route ahead of the snapped point (segment [index], fraction [t])
     * and returns the turns within [maxDistance], in the order they are reached.
     *
     * Instead of testing the heading change at a single vertex, it **accumulates**
     * the signed per-vertex heading change across consecutive same-direction
     * vertices, so a real corner that BRouter sampled as a rounded curve (several
     * gentle vertices) collapses into one maneuver. An accumulation is:
     *  - opened by the first vertex whose turn exceeds [TURN_VERTEX_NOISE_DEG];
     *  - grown while subsequent vertices keep turning the **same** way;
     *  - closed — and emitted if its total reaches [TURN_MIN_ANGLE_DEG] — when the
     *    heading change **reverses** sign (so a zig-zag yields two turns, not a
     *    cancelled one) or after [TURN_ACCUMULATE_GAP_M] of near-straight travel
     *    (so a gentle S-bend / jitter can't drift into a false turn).
     *
     * Each maneuver is anchored at its apex — the vertex of maximum local
     * curvature within the accumulated span — so the reported distance and banner
     * point stay on the actual corner.
     */
    private fun scanManeuvers(
        points: List<RoutePoint>,
        index: Int,
        t: Double,
        maxDistance: Double
    ): List<Maneuver> {
        val result = mutableListOf<Maneuver>()
        if (index + 1 >= points.size - 1) return result

        // Distance from the snapped point to the current segment's end vertex.
        var distToVertex = GeoMath.distanceMeters(
            points[index].latitude, points[index].longitude,
            points[index + 1].latitude, points[index + 1].longitude
        ) * (1.0 - t)

        // Open accumulation state.
        var open = false
        var sum = 0.0
        var sign = 0
        var apexDistance = 0.0
        var apexAbsAngle = 0.0
        var straightGap = 0.0

        fun closeRun() {
            if (open && abs(sum) >= TURN_MIN_ANGLE_DEG) {
                result.add(Maneuver(apexDistance, sum))
            }
            open = false
            sum = 0.0
            sign = 0
            apexAbsAngle = 0.0
            straightGap = 0.0
        }

        var i = index + 1
        while (i < points.size - 1 && distToVertex <= maxDistance) {
            val inBearing = GeoMath.bearingDegrees(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
            val outBearing = GeoMath.bearingDegrees(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
            val vertexAngle = signedAngle(inBearing, outBearing)
            val absAngle = abs(vertexAngle)
            val vertexSign = if (vertexAngle >= 0.0) 1 else -1

            if (absAngle >= TURN_VERTEX_NOISE_DEG) {
                // Reversal ends the current corner before this one starts.
                if (open && vertexSign != sign) closeRun()
                if (!open) {
                    open = true
                    sign = vertexSign
                    sum = 0.0
                    apexAbsAngle = 0.0
                }
                sum += vertexAngle
                straightGap = 0.0
                if (absAngle > apexAbsAngle) {
                    apexAbsAngle = absAngle
                    apexDistance = distToVertex
                }
            } else if (open) {
                // Near-straight vertex: a long enough gap closes the corner.
                straightGap += GeoMath.distanceMeters(
                    points[i].latitude, points[i].longitude,
                    points[i + 1].latitude, points[i + 1].longitude
                )
                if (straightGap > TURN_ACCUMULATE_GAP_M) closeRun()
            }

            distToVertex += GeoMath.distanceMeters(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
            i++
        }
        closeRun()
        return result
    }

    /**
     * Finds the next notable turn ahead of the snapped point (segment [index],
     * fraction [t]) for the turn-by-turn banner: the first accumulated corner
     * within [NEXT_TURN_MAX_DISTANCE_M] whose cumulative heading change exceeds
     * [TURN_MIN_ANGLE_DEG]. Returns its apex distance and signed angle
     * (negative = left, positive = right), or `null` when the road runs straight
     * ahead.
     */
    fun nextTurn(points: List<RoutePoint>, index: Int, t: Double): TurnHint? {
        val turn = scanManeuvers(points, index, t, NEXT_TURN_MAX_DISTANCE_M).firstOrNull()
            ?: return null
        return TurnHint(distanceMeters = turn.distanceMeters, angleDegrees = turn.angleDegrees)
    }

    /** Signed heading change from [a] to [b], normalised to (-180, 180]. */
    private fun signedAngle(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }
}

