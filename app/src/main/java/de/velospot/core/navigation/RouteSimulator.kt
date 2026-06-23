package de.velospot.core.navigation

import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.RoutePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A tiny **GPS mock simulator** that "drives" along a BRouter route so the live
 * navigation can be tested from the couch — no real movement (or even a phone)
 * required.
 *
 * Every [intervalMs] it advances [speedMps] · interval metres along the
 * polyline, interpolates the exact position, derives the heading from the current
 * segment and emits a fully-formed [GeoCoordinate] (with `bearing` + `speed`),
 * exactly like a real fix from `LocationRepository`. Optional [jitterMeters] adds
 * a little lateral noise so you can watch snap-to-route / off-route handling work.
 *
 * Android-free and coroutine-driven → unit testable; the caller owns the
 * [CoroutineScope] (e.g. `viewModelScope`).
 */
class RouteSimulator {

    private var job: Job? = null

    /**
     * Distance (m) travelled along the route so far. Survives [stop] so the
     * simulation can be **paused and resumed** from the same spot (pass it back as
     * `startOffsetMeters`); [reset] (or reaching the end) zeroes it again.
     */
    var travelledMeters: Double = 0.0
        private set

    /** True while a simulation is currently running. */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts emitting simulated fixes along [route].
     *
     * @param scope coroutine scope that owns the ticking loop.
     * @param route the BRouter route points to follow (needs ≥ 2 points).
     * @param speedMps simulated ground speed in metres per second (default ≈ 18 km/h).
     * @param intervalMs delay between emitted fixes (default 1 s — "im Sekundentakt").
     * @param jitterMeters max random lateral offset added to each fix (0 = exact).
     * @param startOffsetMeters distance along the route to begin from (default 0 =
     *  the start). Pass [travelledMeters] to **resume** a paused run.
     * @param onFix invoked on the scope's thread with each simulated [GeoCoordinate].
     * @param onFinished invoked once the end of the route is reached.
     */
    fun start(
        scope: CoroutineScope,
        route: List<RoutePoint>,
        speedMps: Double = 5.0,
        intervalMs: Long = 1_000L,
        jitterMeters: Double = 0.0,
        startOffsetMeters: Double = 0.0,
        onFix: (GeoCoordinate) -> Unit,
        onFinished: () -> Unit = {}
    ) {
        stop()
        if (route.size < 2) return

        // Pre-compute cumulative distances so we can map "distance travelled" to a
        // position on the polyline.
        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cumulative[i] = cumulative[i - 1] + GeoMath.distanceMeters(
                route[i - 1].latitude, route[i - 1].longitude,
                route[i].latitude, route[i].longitude
            )
        }
        val totalLength = cumulative.last()

        job = scope.launch {
            var travelled = startOffsetMeters.coerceIn(0.0, totalLength)
            travelledMeters = travelled
            // Emit the start (or resume) point immediately so the camera tilts in at t=0.
            emitAt(route, cumulative, travelled, speedMps, jitterMeters, onFix)

            while (isActive && travelled < totalLength) {
                delay(intervalMs)
                travelled = (travelled + speedMps * (intervalMs / 1000.0)).coerceAtMost(totalLength)
                travelledMeters = travelled
                emitAt(route, cumulative, travelled, speedMps, jitterMeters, onFix)
            }
            onFinished()
        }
    }

    /** Pauses the run, **keeping** [travelledMeters] so it can be resumed. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Fully stops the run and rewinds [travelledMeters] back to the start. */
    fun reset() {
        stop()
        travelledMeters = 0.0
    }

    /** Interpolates the position at [travelled] metres and emits a fix. */
    private fun emitAt(
        route: List<RoutePoint>,
        cumulative: DoubleArray,
        travelled: Double,
        speedMps: Double,
        jitterMeters: Double,
        onFix: (GeoCoordinate) -> Unit
    ) {
        // Find the segment containing [travelled].
        var seg = 1
        while (seg < cumulative.size - 1 && cumulative[seg] < travelled) seg++
        val a = route[seg - 1]
        val b = route[seg]
        val segStart = cumulative[seg - 1]
        val segLen = (cumulative[seg] - segStart).coerceAtLeast(1e-6)
        val t = ((travelled - segStart) / segLen).coerceIn(0.0, 1.0)

        var lat = a.latitude + (b.latitude - a.latitude) * t
        var lon = a.longitude + (b.longitude - a.longitude) * t
        val bearing = GeoMath.bearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude)

        if (jitterMeters > 0.0) {
            // Offset perpendicular-ish to travel by a small random amount.
            val offset = (Random.nextDouble() * 2.0 - 1.0) * jitterMeters
            val perpRad = Math.toRadians(bearing + 90.0)
            val dLat = (offset * Math.cos(perpRad)) / 111_320.0
            val dLon = (offset * Math.sin(perpRad)) /
                (111_320.0 * Math.cos(Math.toRadians(lat)))
            lat += dLat
            lon += dLon
        }

        onFix(
            GeoCoordinate(
                latitude = lat,
                longitude = lon,
                bearing = bearing.toFloat(),
                speedMetersPerSecond = speedMps.toFloat()
            )
        )
    }
}

