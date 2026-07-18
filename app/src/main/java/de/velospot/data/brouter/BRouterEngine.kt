package de.velospot.data.brouter

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import btools.router.OsmNodeNamed
import btools.router.OsmTrack
import btools.router.RoutingContext
import btools.router.RoutingEngine
import de.velospot.BuildConfig
import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.BRouterProfilesMissingException
import de.velospot.domain.model.EmptyRouteGeometryException
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.NoRouteFoundException
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RoutingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wraps the BRouter offline routing engine and exposes a coroutine-friendly
 * API to calculate bike routes entirely on-device.
 *
 * The BRouter classes (`btools.*`) are compiled from source by the `:brouter`
 * Gradle module (pinned `brouter-upstream` submodule); no JAR is bundled.
 *
 * ## Setup
 * 1. Initialise the BRouter source submodule once: `git submodule update --init`.
 * 2. The `.brf` profile files and `lookups.dat` are bundled under
 *    `app/src/main/assets/brouter/profiles/`.
 * 3. Make sure the required `.rd5` segment files have been downloaded via
 *    [BRouterSegmentManager] before calling [calculateRoute].
 *
 * ## Coordinate encoding used by BRouter
 * BRouter stores all coordinates as integers using the formula:
 * ```
 * ilon = round((longitude + 180) * 1_000_000)
 * ilat = round((latitude  +  90) * 1_000_000)
 * ```
 * Reverse: `longitude = ilon / 1_000_000.0 − 180.0`
 */
class BRouterEngine(
    private val context: Context,
    private val segmentsDir: File
) {
    /**
     * Directory in internal storage where profile `.brf` files are kept at
     * runtime. Populated from assets on first use by [ensureProfiles].
     */
    private val profilesDir: File = File(context.filesDir, "brouter/profiles")

    /**
     * Size (MB) of BRouter's in-memory segment node cache (`RoutingContext.memoryclass`).
     * BRouter defaults to a conservative 64 MB; on long routes that span more decoded
     * segment data than fits, the cache thrashes (evict → re-read → re-decode from disk),
     * which dominates the runtime. We raise it to a device-aware value (≈ half the app's
     * heap budget, clamped), so long routes stop reloading segments — the single biggest
     * win for 100 km+ trips — while staying clear of OOM on low-end devices.
     */
    private val nodesCacheMemoryMb: Int by lazy {
        val heapMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB
        (heapMb / 2).coerceIn(MIN_MEMORY_CLASS_MB, MAX_MEMORY_CLASS_MB)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Calculates a bike route between [from] and [to] using [profile] entirely
     * on-device. Must be called **after** the required segment files have been
     * downloaded via [BRouterSegmentManager].
     *
     * @throws NoRouteFoundException if BRouter cannot find a route.
     * @throws EmptyRouteGeometryException if the route contains no nodes.
     */
    suspend fun calculateRoute(
        from: GeoCoordinate,
        to: GeoCoordinate,
        profile: BRouterProfile = BRouterProfile.TREKKING,
        elevation: ElevationPreference = ElevationPreference.DEFAULT
    ): BikeRoute = withContext(Dispatchers.Default) {
        ensureProfiles()

        val profilePath = profilePathFor(profile)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "segmentsDir = $segmentsDir  exists=${segmentsDir.exists()}")
            Log.d(TAG, "profilePath = $profilePath  exists=${File(profilePath).exists()}")
            Log.d(TAG, "profiles in dir: ${profilesDir.listFiles()?.map { it.name }}")
        }

        val fromPos = osmNodeNamed("from", from.longitude, from.latitude)
        val toPos   = osmNodeNamed("to",   to.longitude,   to.latitude)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "from ilon=${fromPos.ilon} ilat=${fromPos.ilat}")
            Log.d(TAG, "to   ilon=${toPos.ilon}   ilat=${toPos.ilat}")
        }

        try {
            // First pass. If the rider has a live GPS heading, hand it to BRouter as
            // the start direction so it never opens the route with a hairpin "U-turn"
            // (BRouter otherwise connects the start to the nearest network node, which
            // can sit *behind* the rider, and routes out-and-back).
            val gpsStartDir: Int? = from.bearing?.let { ((it.toInt() % 360) + 360) % 360 }
            var route = runBrouterWithStartWayFallback(profilePath, from, to, gpsStartDir, elevation)

            // Standstill start (no GPS heading): if the route still opens with a
            // reversal, recompute once telling BRouter the route's own forward
            // direction. BRouter then drops the spur itself — the geometry stays real
            // on-road (we never edit the path), unlike a post-hoc trim.
            //
            // Skip this on long routes: the second pass recomputes the *entire* route,
            // which is very expensive on 100 km+ trips, while the start spur is
            // negligible relative to the total distance — not worth doubling the work.
            if (gpsStartDir == null && haversineDistanceMeters(route.points) <= START_UTURN_MAX_ROUTE_M) {
                startUTurnForwardDir(route.points, to)?.let { forwardDir ->
                    // The second pass forces a start direction, which BRouter may not be
                    // able to satisfy (one-way nets, dead-ends). If it can't find a route
                    // it returns an empty/absent track — don't let that discard the valid
                    // first-pass result and fail the whole request ("route data
                    // incomplete"); only adopt the recomputed route when it succeeded.
                    runCatching { runBrouter(profilePath, from, to, forwardDir, elevation) }
                        .onSuccess { recomputed -> if (recomputed.points.isNotEmpty()) route = recomputed }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            Log.w(TAG, "Start-U-turn second pass failed; keeping first-pass route", e)
                        }
                }
            }

            bikeRouteFrom(route, profile)
        } catch (e: CancellationException)      { throw e }
        catch (e: NoRouteFoundException)        { throw e }
        catch (e: EmptyRouteGeometryException)  { throw e }
        catch (e: Exception) {
            Log.e(TAG, "BRouter routing failed", e)
            throw e
        }
    }

    /**
     * Generates a circular **round-trip** route that starts and ends at [start],
     * roughly [targetDistanceMeters] long, entirely on-device. BRouter builds a
     * ring of waypoints around the start (radius derived from the target length)
     * and routes through them back to the origin via [RoutingEngine.doRoundTrip].
     *
     * An optional [directionDeg] (compass degrees) biases which way the loop heads
     * out; when `null` BRouter picks a direction itself. The resulting length is
     * approximate — round-trip routing trades exactness for a pleasant loop.
     *
     * @throws NoRouteFoundException if BRouter cannot build a loop (e.g. missing
     *  segment tiles around the start).
     */
    suspend fun calculateRoundTrip(
        start: GeoCoordinate,
        targetDistanceMeters: Double,
        profile: BRouterProfile = BRouterProfile.TREKKING,
        directionDeg: Int? = null,
        elevation: ElevationPreference = ElevationPreference.DEFAULT
    ): BikeRoute = withContext(Dispatchers.Default) {
        ensureProfiles()
        val profilePath = profilePathFor(profile)

        // BRouter's roundTripDistance is the *search radius* of the waypoint ring,
        // not the total length. With ROUND_TRIP_POINTS = 5, BRouter routes
        // start → 4 points on a circle of this radius → back, whose straight-line
        // length is ≈ 3.85 × radius; real roads add a detour on top, so the ridden
        // loop is empirically ≈ 6.5 × radius. Derive the radius from the request.
        val searchRadius = (targetDistanceMeters / ROUND_TRIP_LENGTH_TO_RADIUS)
            .toInt().coerceIn(MIN_ROUND_TRIP_RADIUS_M, MAX_ROUND_TRIP_RADIUS_M)

        // The waypoint ring is placed *geometrically* in a single direction, so a
        // random heading can aim the loop straight at a hill no matter how high the
        // uphill cost is — which is why "flatter routes" barely helped round trips.
        // When the rider asked for flatter routes (and didn't pin a direction), try
        // several evenly-spread ring directions and keep the loop that climbs the
        // least. Otherwise a single (given or random) direction is enough.
        val preferFlat = elevation.uphillExtraCost > 0 && directionDeg == null
        val directions: List<Int> = when {
            directionDeg != null -> listOf(normalizeDegrees(directionDeg))
            preferFlat -> {
                val base = (0 until 360).random()
                (0 until ROUND_TRIP_FLAT_DIRECTION_SAMPLES).map {
                    normalizeDegrees(base + it * (360 / ROUND_TRIP_FLAT_DIRECTION_SAMPLES))
                }
            }
            else -> listOf((0 until 360).random())
        }

        var best: DecodedTrack? = null
        var bestAscent = Double.MAX_VALUE
        for (direction in directions) {
            val candidate = runCatching {
                roundTripOnce(start, searchRadius, profilePath, direction, elevation)
            }.getOrNull() ?: continue
            if (!preferFlat) {
                best = candidate
                break
            }
            val ascent = totalAscentMeters(candidate.points)
            if (best == null || ascent < bestAscent) {
                best = candidate
                bestAscent = ascent
            }
        }

        bikeRouteFrom(best ?: throw NoRouteFoundException(), profile)
    }

    /**
     * Runs one BRouter round-trip pass: builds the waypoint ring in [direction]
     * (compass degrees) around [start] at [searchRadius] and routes through it back
     * to the origin. Returns the decoded track so the caller can compare candidates
     * (e.g. by climb) before wrapping the winner into a [BikeRoute].
     */
    private suspend fun roundTripOnce(
        start: GeoCoordinate,
        searchRadius: Int,
        profilePath: String,
        direction: Int,
        elevation: ElevationPreference
    ): DecodedTrack {
        val rc = RoutingContext().apply {
            localFunction = profilePath
            memoryclass = nodesCacheMemoryMb
            roundTripDistance = searchRadius
            roundTripPoints = ROUND_TRIP_POINTS
            keyValues = elevationKeyValues(elevation)
            // Always hand BRouter a concrete start heading. When the caller doesn't pick
            // one we choose a random direction ourselves rather than leaving it null:
            // a null startDirection makes doRoundTrip() derive a heading via
            // getRandomDirectionFromData(), which — for profiles that enable
            // consider_elevation/forest/river (trekking, fastbike, mtb) — reads area-info
            // data and parses a `dummy.brf` we don't bundle, so the loop silently
            // produces no route ("route data incomplete"). Passing a direction skips that
            // path entirely, so round trips work for every profile.
            startDirection = normalizeDegrees(direction)
        }
        val engine = RoutingEngine(
            /* baseUrl     = */ null,
            /* outfileBase = */ null,
            /* segmentDir  = */ segmentsDir,
            /* waypoints   = */ mutableListOf(osmNodeNamed("start", start.longitude, start.latitude)),
            /* rc          = */ rc
        )
        runEngine(engine, roundTrip = true)
        return decodeTrack(engine.foundTrack)
    }

    /** Total positive elevation gain (m) over the route's points (0 when no elevation data). */
    private fun totalAscentMeters(points: List<RoutePoint>): Double {
        var gain = 0.0
        for (i in 1 until points.size) {
            val prev = points[i - 1].elevationMeters ?: continue
            val curr = points[i].elevationMeters ?: continue
            val delta = curr - prev
            if (delta > 0) gain += delta
        }
        return gain
    }

    /** Normalises a compass heading to the 0–359° range. */
    private fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

    companion object {
        private const val TAG = "BRouterEngine"

        /**
         * Bump this whenever a bundled `.brf` profile (or `lookups.dat`) changes so
         * [ensureProfiles] refreshes the copies in internal storage on existing installs.
         *
         * History:
         * - 1: initial bundled profiles.
         * - 2: trunk-road hardening across the bundled profiles (gravel, mtb, trekking,
         *      fastbike, shortest) — keep bikes off trunk roads / motorway feeders.
         * - 3: de-prefer footways/sidewalks vs the carriageway in town and make quiet
         *      streets cheaper (gravel, trekking), so urban routes stop hugging the
         *      pavement next to the road. Dedicated cycleways stay preferred.
         * - 4: don't snap the route start/end onto sidewalks (noStartWay=footway,sidewalk)
         *      so the route starts on the carriageway instead of a pavement+crossing
         *      detour (gravel, trekking).
         * - 5: expose a uniform `uphill_extra` parameter across all profiles, added to
         *      the final uphill cost so the "route hilliness" slider can favour flatter
         *      routes (default 0 = unchanged).
         * - 6: extend the start-on-carriageway guard (`check_start_way` +
         *      `noStartWay=footway,sidewalk`) to the remaining profiles (fastbike, mtb,
         *      shortest) so no profile opens the route on a sidewalk.
         * - 7: penalise cycling a `footway=sidewalk` (even when bicycle=yes) in trekking
         *      and shortest so they stop hugging the pavement next to the carriageway,
         *      matching gravel/fastbike/mtb.
         * - 8: extend that sidewalk penalty to fastbike and mtb — the MTB profile
         *      heavily penalises paved roads, so a sidewalk used to be cheaper than the
         *      carriageway; a strong `footway=sidewalk` surcharge keeps both on the road.
         * - 9: prefer cycleways over motorways/trunks (all five profiles hard-block
         *      motorway/trunk for bikes unless cycling is permitted / bike infra is
         *      present, and discount highway=cycleway + roads with cycleway=track/lane);
         *      also drops the invalid `bicycle=…|official` value from shortest.brf that
         *      made the profile fail to parse. This bump is required so the corrected
         *      profiles actually replace the stale copies in internal storage on existing
         *      installs — otherwise [ensureProfiles] keeps the old (possibly broken) files
         *      and routing fails ("route data incomplete") for every profile.
         */
        private const val PROFILES_VERSION = "11"
        private const val PROFILES_VERSION_FILE = ".profiles_version"

        // ── Start-reversal detection (see startUTurnForwardDir) ───────────────
        /** First segment vs destination-direction difference that counts as a start U-turn. */
        private const val REVERSAL_MIN_ANGLE_DEG = 110.0

        /**
         * Above this route length the start-U-turn fix is skipped: its second pass
         * recomputes the whole route (expensive on long trips) to remove a spur that's
         * negligible at this scale.
         */
        private const val START_UTURN_MAX_ROUTE_M = 30_000.0

        // ── Node-cache sizing (RoutingContext.memoryclass, see nodesCacheMemoryMb) ──
        private const val DEFAULT_MEMORY_CLASS_MB = 64
        private const val MIN_MEMORY_CLASS_MB = 96
        private const val MAX_MEMORY_CLASS_MB = 256

        // ── Cancellable engine run (see runEngine) ────────────────────────────
        /** How often the worker thread is polled for coroutine cancellation. */
        private const val ENGINE_POLL_MS = 50L
        /** Grace period to let BRouter unwind after [RoutingEngine.terminate]. */
        private const val ENGINE_TERMINATE_GRACE_MS = 2_000L

        // ── Round-trip generation (see calculateRoundTrip) ────────────────────
        /**
         * Approximate ratio of the ridden loop length to BRouter's search radius.
         *
         * With [ROUND_TRIP_POINTS] = 5, BRouter routes start → 4 waypoints on a
         * circle of `searchRadius` → back to start. The straight-line length of that
         * polygon is `2·r + 3·(2·r·sin18°) ≈ 3.85·r`; real roads add a routing detour
         * on top, so the ridden length works out empirically to ≈ 6.5·r. Tuned from
         * real generated loops (the earlier 3.0 / 5.0 values still came out well over
         * the requested distance).
         */
        private const val ROUND_TRIP_LENGTH_TO_RADIUS = 6.5
        /** Number of waypoints BRouter spreads around the ring. */
        private const val ROUND_TRIP_POINTS = 5
        private const val MIN_ROUND_TRIP_RADIUS_M = 500
        private const val MAX_ROUND_TRIP_RADIUS_M = 40_000

        /**
         * How many evenly-spread ring directions to try when the rider asked for a
         * flatter round trip, keeping the loop that climbs the least. The waypoint
         * ring is placed geometrically, so trying several headings is the only way to
         * actually dodge a hill (BRouter's uphill cost can't move the fixed ring).
         * A modest count keeps the extra routing cost bounded (only paid for flat
         * preferences, and cancellable).
         */
        private const val ROUND_TRIP_FLAT_DIRECTION_SAMPLES = 4
    }


    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds BRouter profile parameters ([RoutingContext.keyValues]) for the chosen
     * [elevation] preference. Passes the `uphill_extra` parameter (added to every
     * bundled profile) so flatter levels penalise climbs.
     *
     * The map is **always** returned — even for [ElevationPreference.ANY], where it
     * carries `uphill_extra = 0`. Returning `null` for ANY used to send routing down a
     * different BRouter code path (no key-values injection, a different profile-cache
     * key) than the non-ANY case, which left some profiles (e.g. gravel) unable to
     * produce a route on ANY while every other level worked. Sending a uniform map
     * makes ANY behave exactly like the working levels, just with a zero penalty.
     */
    private fun elevationKeyValues(elevation: ElevationPreference): MutableMap<String, String> =
        hashMapOf("uphill_extra" to elevation.uphillExtraCost.coerceAtLeast(0).toString())

    /**
     * Runs the first BRouter pass and, if it yields no usable route, retries once
     * with the profile's start-way guard disabled (`check_start_way = 0`).
     *
     * Every bundled profile declares `noStartWay=footway,sidewalk` so the route never
     * *snaps its start onto a pavement*. BRouter enforces this by refusing to match the
     * **start** waypoint to such a way (`WaypointMatcherImpl` skips it for index 0).
     * That's the right default — but when the rider actually stands on / next to a
     * `footway=sidewalk` and no carriageway is within the catching range, the start
     * waypoint matches *nothing*, BRouter throws "from-position not mapped" internally,
     * and the caller only sees an empty track ("route data incomplete"). This happened
     * on every profile the moment navigation started from such a spot.
     *
     * The fallback keeps the no-sidewalk-start preference for the normal case but
     * guarantees the rider still gets a route from a pavement: on the retry the start
     * may snap to any way. A genuinely impossible route (e.g. missing segment tile)
     * still fails on the retry, which is correct.
     */
    private suspend fun runBrouterWithStartWayFallback(
        profilePath: String,
        from: GeoCoordinate,
        to: GeoCoordinate,
        startDir: Int?,
        elevation: ElevationPreference
    ): DecodedTrack = try {
        runBrouter(profilePath, from, to, startDir, elevation)
    } catch (e: NoRouteFoundException) {
        Log.w(TAG, "No route on first pass; retrying without the start-way guard", e)
        runBrouter(profilePath, from, to, startDir, elevation, allowStartOnAnyWay = true)
    } catch (e: EmptyRouteGeometryException) {
        Log.w(TAG, "Empty route on first pass; retrying without the start-way guard", e)
        runBrouter(profilePath, from, to, startDir, elevation, allowStartOnAnyWay = true)
    }

    /**
     * Runs one BRouter calculation from [from] to [to] with an optional
     * [startDir] (compass degrees) hint. Fresh waypoint nodes are created per call
     * because BRouter mutates their matching state, so they must not be reused
     * across the two passes. Returns the decoded route (geometry + BRouter's own
     * physics-based travel time and energy).
     */
    private suspend fun runBrouter(
        profilePath: String,
        from: GeoCoordinate,
        to: GeoCoordinate,
        startDir: Int?,
        elevation: ElevationPreference,
        allowStartOnAnyWay: Boolean = false
    ): DecodedTrack {
        val rc = RoutingContext().apply {
            localFunction = profilePath          // path to .brf WITHOUT extension
            memoryclass = nodesCacheMemoryMb     // bigger node cache → fewer disk reloads
            keyValues = elevationKeyValues(elevation).apply {
                // Disable the profile's no-sidewalk-start guard for this pass so the
                // start/end may snap to any way (used as a fallback when the strict
                // matching found nothing — see runBrouterWithStartWayFallback).
                if (allowStartOnAnyWay) put("check_start_way", "0")
            }
            if (startDir != null) {
                startDirection = startDir
                forceUseStartDirection = true
            }
        }
        val engine = RoutingEngine(
            /* baseUrl     = */ null,
            /* outfileBase = */ null,
            /* segmentDir  = */ segmentsDir,
            /* waypoints   = */ listOf(
                osmNodeNamed("from", from.longitude, from.latitude),
                osmNodeNamed("to",   to.longitude,   to.latitude)
            ),
            /* rc          = */ rc
        )
        runEngine(engine)
        return decodeTrack(engine.foundTrack)
    }

    /**
     * Runs BRouter's blocking search ([RoutingEngine.doRun] / [RoutingEngine.doRoundTrip])
     * on a daemon worker thread while observing coroutine cancellation. When the calling
     * coroutine is cancelled (e.g. the user taps "Cancel"), [RoutingEngine.terminate] is
     * signalled so BRouter aborts its A* search at the next loop check instead of running
     * to completion, and the [CancellationException] is propagated.
     */
    private suspend fun runEngine(engine: RoutingEngine, roundTrip: Boolean = false) {
        val worker = Thread(
            { if (roundTrip) engine.doRoundTrip() else engine.doRun(0L) },
            "brouter-route"
        ).apply { isDaemon = true }
        worker.start()
        try {
            while (worker.isAlive) {
                coroutineContext.ensureActive()      // throws if the coroutine was cancelled
                worker.join(ENGINE_POLL_MS)
            }
        } catch (ce: CancellationException) {
            engine.terminate()                       // ask BRouter to stop its search
            runCatching { worker.join(ENGINE_TERMINATE_GRACE_MS) }
            throw ce
        }
    }

    /**
     * Decodes BRouter's [OsmTrack] into a [DecodedTrack]: the geometry nodes
     * ([RoutePoint]s with terrain elevation) plus the track-level figures BRouter's
     * kinematic model produced — the total travel time ([OsmTrack.getTotalSeconds])
     * and the mechanical energy ([OsmTrack.energy], Joules). Shared by the
     * point-to-point and round-trip passes.
     *
     * @throws NoRouteFoundException when no track was produced.
     * @throws EmptyRouteGeometryException when the track carries no nodes.
     */
    private fun decodeTrack(track: OsmTrack?): DecodedTrack {
        if (BuildConfig.DEBUG) Log.d(TAG, "foundTrack = $track  nodes=${track?.nodes?.size}")
        if (track == null) throw NoRouteFoundException()
        val nodes = track.nodes
        if (nodes.isNullOrEmpty()) throw EmptyRouteGeometryException()
        // BRouter emits every node as the same concrete subclass, so resolve the
        // private coordinate fields + `getElev()` accessor ONCE for the track instead
        // of reflecting per node (a long route has thousands of nodes).
        val accessor = nodeAccessorFor(nodes.first())
        val points = nodes.map { node ->
            RoutePoint(
                latitude  = accessor.ilat.getInt(node) / 1_000_000.0 - 90.0,
                longitude = accessor.ilon.getInt(node) / 1_000_000.0 - 180.0,
                elevationMeters = accessor.elevMetersOrNull(node)
            )
        }
        // `energy` and `getTotalSeconds()` are public on OsmTrack — read directly
        // (no reflection). They are only meaningful when the profile drives BRouter's
        // kinematic model (all bundled `.brf`s set totalMass/bikerPower/maxSpeed), so
        // we treat non-positive values as "unavailable" in [bikeRouteFrom].
        return DecodedTrack(
            points = points,
            totalSeconds = track.getTotalSeconds(),
            energyJoules = track.energy.toDouble()
        )
    }

    /**
     * One decoded BRouter result: the route geometry plus the track-level figures
     * from BRouter's kinematic model (travel time and mechanical energy).
     */
    private class DecodedTrack(
        val points: List<RoutePoint>,
        /** BRouter's physics-based total travel time in seconds (0 when unavailable). */
        val totalSeconds: Int,
        /** Mechanical work for the whole route in Joules (0 when unavailable). */
        val energyJoules: Double
    )

    /** Absolute path to a profile's `.brf` file in internal storage. */
    private fun profilePathFor(profile: BRouterProfile): String =
        File(profilesDir, "${profile.fileName}.brf").absolutePath

    /**
     * Wraps a [decoded] BRouter result into a [BikeRoute]. The ETA prefers BRouter's
     * own kinematic travel time (which accounts for rider+bike mass, power, drag,
     * rolling resistance and the climb profile) and only falls back to the flat
     * per-profile speed when BRouter produced no time. The mechanical energy is
     * carried through for the calorie estimate.
     */
    private fun bikeRouteFrom(decoded: DecodedTrack, profile: BRouterProfile): BikeRoute {
        val distanceMeters = haversineDistanceMeters(decoded.points)
        val durationSeconds = if (decoded.totalSeconds > 0) {
            decoded.totalSeconds.toDouble()
        } else {
            distanceMeters / profile.typicalSpeedMs
        }
        return BikeRoute(
            points          = decoded.points,
            distanceMeters  = distanceMeters,
            durationSeconds = durationSeconds,
            source          = RoutingSource.BROUTER_OFFLINE,
            energyJoules    = decoded.energyJoules.takeIf { it > 0.0 }
        )
    }

    /**
     * Detects a hairpin/U-turn at the very start of [points] and returns the
     * direction (compass degrees) to feed back as a `startDirection` hint, or
     * `null` when the start is already clean.
     *
     * The reference for "forward" is the straight-line bearing from the start to
     * the **destination** [to] — robust regardless of how long the spurious
     * out-and-back spur is (an earlier attempt sampled a fixed distance ahead and
     * missed spurs longer than that). A first segment heading more than
     * [REVERSAL_MIN_ANGLE_DEG] away from the destination is the unmistakable signal
     * BRouter opened the route by turning around. The hint is only a finite turn
     * penalty, so a genuinely required backtrack (one-way, cul-de-sac) is still
     * routed correctly — it just discourages a needless reversal.
     */
    private fun startUTurnForwardDir(points: List<RoutePoint>, to: GeoCoordinate): Int? {
        if (points.size < 2) return null
        val origin = points.first()
        val firstBearing = GeoMath.bearingDegrees(
            origin.latitude, origin.longitude, points[1].latitude, points[1].longitude
        )
        val destBearing = GeoMath.bearingDegrees(
            origin.latitude, origin.longitude, to.latitude, to.longitude
        )
        if (GeoMath.angularDistance(firstBearing, destBearing) < REVERSAL_MIN_ANGLE_DEG) {
            return null
        }
        return ((destBearing.toInt() % 360) + 360) % 360
    }

    /**
     * Copies `.brf` profile files and `lookups.dat` from app assets to
     * [profilesDir] in internal storage so BRouter can read them at runtime.
     * Throws [BRouterProfilesMissingException] when the assets folder is empty,
     * i.e. the user hasn't placed the profile files yet.
     *
     * Profiles are re-copied whenever [PROFILES_VERSION] changes, so bundled
     * profile tweaks (e.g. routing-cost fixes) actually reach existing installs
     * — a plain "copy only when missing" check would keep stale copies forever.
     */
    private fun ensureProfiles() {
        profilesDir.mkdirs()
        val assetManager = context.assets
        val profileAssets = assetManager.list("brouter/profiles").orEmpty()
        if (profileAssets.isEmpty()) {
            throw BRouterProfilesMissingException()
        }
        val versionFile = File(profilesDir, PROFILES_VERSION_FILE)
        val isUpToDate = versionFile.takeIf { it.exists() }?.readText()?.trim() == PROFILES_VERSION
        for (asset in profileAssets) {
            val dest = File(profilesDir, asset)
            if (!dest.exists() || !isUpToDate) {
                assetManager.open("brouter/profiles/$asset").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        if (!isUpToDate) {
            runCatching { versionFile.writeText(PROFILES_VERSION) }
        }
    }

    /**
     * Creates an [OsmNodeNamed] in BRouter's integer coordinate space.
     *
     * BRouter encoding:
     * - `ilon = round((longitude + 180) * 1_000_000)`
     * - `ilat = round((latitude  +  90) * 1_000_000)`
     */
    private fun osmNodeNamed(name: String, longitude: Double, latitude: Double) =
        OsmNodeNamed().apply {
            this.name = name
            ilon = ((longitude + 180.0) * 1_000_000.0 + 0.5).toInt()
            ilat = ((latitude  +  90.0) * 1_000_000.0 + 0.5).toInt()
        }

    /**
     * Per-class cache of the reflective accessors used to decode BRouter nodes.
     * Resolving the private `ilat`/`ilon` fields and the `getElev()` method is done
     * once per concrete node class and then reused for every node of every track.
     */
    private val nodeAccessors = ConcurrentHashMap<Class<*>, NodeAccessor>()

    private fun nodeAccessorFor(node: Any): NodeAccessor =
        nodeAccessors.getOrPut(node.javaClass) {
            NodeAccessor(
                ilat = declaredFieldInHierarchy(node.javaClass, "ilat"),
                ilon = declaredFieldInHierarchy(node.javaClass, "ilon"),
                getElev = runCatching { node.javaClass.getMethod("getElev") }.getOrNull(),
            )
        }

    /**
     * Pre-resolved reflective accessors for a single BRouter node class.
     *
     * BRouter declares the integer coordinate fields (`ilat`/`ilon`) as private in
     * a concrete `OsmPathElement` subclass with no public getter, and exposes
     * elevation via `OsmPos.getElev()` (which returns `selev / 4.0`). Reflection
     * keeps the engine decoupled from the exact `btools` class hierarchy.
     */
    private class NodeAccessor(
        val ilat: Field,
        val ilon: Field,
        val getElev: Method?,
    ) {
        /**
         * Terrain elevation (m) of [node], or `null` when unavailable (BRouter uses
         * a short sentinel that maps to an absurd value) so callers fall back to GPS.
         */
        fun elevMetersOrNull(node: Any): Double? {
            val m = getElev ?: return null
            return try {
                val v = (m.invoke(node) as? Number)?.toDouble() ?: return null
                if (v < -1_000.0 || v > 9_000.0) null else v
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Resolves a (possibly private) field by [name] by walking the class hierarchy
     * of [start], making it accessible. Used to read BRouter's private coordinate
     * fields that have no public getter.
     */
    private fun declaredFieldInHierarchy(start: Class<*>, name: String): Field {
        var cls: Class<*>? = start
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("Field '$name' not found in ${start.name} or any superclass")
    }


    /**
     * Calculates total great-circle distance along [points] using the
     * Haversine formula.
     */
    private fun haversineDistanceMeters(points: List<RoutePoint>): Double {
        val earthRadiusM = 6_371_000.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val dLat = Math.toRadians(p2.latitude  - p1.latitude)
            val dLon = Math.toRadians(p2.longitude - p1.longitude)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(p1.latitude)) *
                    cos(Math.toRadians(p2.latitude)) *
                    sin(dLon / 2).pow(2)
            total += 2 * earthRadiusM * atan2(sqrt(a), sqrt(1 - a))
        }
        return total
    }
}
