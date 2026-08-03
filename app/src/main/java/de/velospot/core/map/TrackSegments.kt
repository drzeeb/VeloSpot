package de.velospot.core.map

import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.TrackPoint

/**
 * Splits a recorded track into **continuous segments**, breaking it at every point
 * flagged [TrackPoint.segmentStart] — i.e. the first fix captured after the rider
 * resumed from a pause (e.g. a train/ferry leg of a commute).
 *
 * Each returned inner list is one uninterrupted stretch; the gaps between them are
 * the paused legs, which must not be drawn as a straight connecting line. A track
 * without any pause yields a single segment. Pure and JVM-unit-testable.
 */
fun List<TrackPoint>.splitIntoSegments(): List<List<RoutePoint>> {
    if (isEmpty()) return emptyList()
    val segments = mutableListOf<MutableList<RoutePoint>>()
    for ((index, p) in withIndex()) {
        val rp = RoutePoint(p.latitude, p.longitude)
        if (index == 0 || p.segmentStart || segments.isEmpty()) {
            segments += mutableListOf(rp)
        } else {
            segments.last() += rp
        }
    }
    return segments
}

