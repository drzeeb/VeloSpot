package de.velospot.core.navigation

import de.velospot.domain.model.RoutePoint

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
     * @property turnSharpnessDegrees Largest heading change found within the
     *  next [TURN_LOOKAHEAD_METERS]; `0` on straight stretches, up to `180` for
     *  a U-turn. Drives the "zoom in before a turn" behaviour.
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
     * @property distanceMeters distance from the snapped point to the turn vertex.
     * @property angleDegrees signed heading change: negative = left, positive = right.
     */
    data class TurnHint(val distanceMeters: Double, val angleDegrees: Double)

    /** Heading change (deg) at a single vertex that counts as a real turn. */
    private const val TURN_MIN_ANGLE_DEG = 32.0
    /** Don't look further than this for the next turn (keeps the banner relevant). */
    private const val NEXT_TURN_MAX_DISTANCE_M = 500.0

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
     * Largest heading change within [TURN_LOOKAHEAD_METERS] ahead of the snapped
     * point, relative to the current segment heading.
     */
    private fun turnSharpness(points: List<RoutePoint>, index: Int, t: Double): Double {        if (index + 1 >= points.size - 1) return 0.0
        val currentBearing = GeoMath.bearingDegrees(
            points[index].latitude, points[index].longitude,
            points[index + 1].latitude, points[index + 1].longitude
        )
        var distance = GeoMath.distanceMeters(
            points[index].latitude, points[index].longitude,
            points[index + 1].latitude, points[index + 1].longitude
        ) * (1.0 - t)

        var maxDelta = 0.0
        var i = index + 1
        while (i < points.size - 1 && distance < TURN_LOOKAHEAD_METERS) {
            val segBearing = GeoMath.bearingDegrees(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
            val delta = GeoMath.angularDistance(currentBearing, segBearing)
            if (delta > maxDelta) maxDelta = delta
            distance += GeoMath.distanceMeters(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
            i++
        }
        return maxDelta
    }

    /**
     * Finds the next notable turn ahead of the snapped point (segment [index],
     * fraction [t]) for the turn-by-turn banner: the first vertex within
     * [NEXT_TURN_MAX_DISTANCE_M] whose heading change exceeds [TURN_MIN_ANGLE_DEG].
     * Returns its distance and signed angle (negative = left, positive = right),
     * or `null` when the road runs straight ahead.
     */
    fun nextTurn(points: List<RoutePoint>, index: Int, t: Double): TurnHint? {
        if (index + 1 >= points.size - 1) return null
        // Distance from the snapped point to the end of the current segment.
        var distance = GeoMath.distanceMeters(
            points[index].latitude, points[index].longitude,
            points[index + 1].latitude, points[index + 1].longitude
        ) * (1.0 - t)

        var i = index + 1
        while (i < points.size - 1 && distance <= NEXT_TURN_MAX_DISTANCE_M) {
            val inBearing = GeoMath.bearingDegrees(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
            val outBearing = GeoMath.bearingDegrees(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
            val signed = signedAngle(inBearing, outBearing)
            if (kotlin.math.abs(signed) >= TURN_MIN_ANGLE_DEG) {
                return TurnHint(distanceMeters = distance, angleDegrees = signed)
            }
            distance += GeoMath.distanceMeters(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
            i++
        }
        return null
    }

    /** Signed heading change from [a] to [b], normalised to (-180, 180]. */
    private fun signedAngle(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }
}

