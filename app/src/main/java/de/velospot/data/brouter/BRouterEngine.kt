package de.velospot.data.brouter

import android.content.Context
import android.util.Log
import btools.router.OsmNodeNamed
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
        profile: BRouterProfile = BRouterProfile.TREKKING
    ): BikeRoute = withContext(Dispatchers.Default) {
        ensureProfiles()

        val profilePath = File(profilesDir, "${profile.fileName}.brf").absolutePath
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
            var points = runBrouter(profilePath, from, to, gpsStartDir)

            // Standstill start (no GPS heading): if the route still opens with a
            // reversal, recompute once telling BRouter the route's own forward
            // direction. BRouter then drops the spur itself — the geometry stays real
            // on-road (we never edit the path), unlike a post-hoc trim.
            if (gpsStartDir == null) {
                startUTurnForwardDir(points, to)?.let { forwardDir ->
                    points = runBrouter(profilePath, from, to, forwardDir)
                }
            }


            val distanceMeters  = haversineDistanceMeters(points)
            val durationSeconds = distanceMeters / profile.typicalSpeedMs

            BikeRoute(
                points          = points,
                distanceMeters  = distanceMeters,
                durationSeconds = durationSeconds,
                source          = RoutingSource.BROUTER_OFFLINE
            )
        } catch (e: NoRouteFoundException)      { throw e }
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
        directionDeg: Int? = null
    ): BikeRoute = withContext(Dispatchers.Default) {
        ensureProfiles()
        val profilePath = File(profilesDir, "${profile.fileName}.brf").absolutePath

        // BRouter's roundTripDistance is the *search radius* of the waypoint ring,
        // not the total length. Empirically the looped result is ~3× the radius, so
        // derive the radius from the requested trip length.
        val searchRadius = (targetDistanceMeters / ROUND_TRIP_LENGTH_TO_RADIUS)
            .toInt().coerceIn(MIN_ROUND_TRIP_RADIUS_M, MAX_ROUND_TRIP_RADIUS_M)

        val rc = RoutingContext().apply {
            localFunction = profilePath
            roundTripDistance = searchRadius
            roundTripPoints = ROUND_TRIP_POINTS
            if (directionDeg != null) {
                startDirection = ((directionDeg % 360) + 360) % 360
            }
        }
        val engine = RoutingEngine(
            /* baseUrl     = */ null,
            /* outfileBase = */ null,
            /* segmentDir  = */ segmentsDir,
            /* waypoints   = */ mutableListOf(osmNodeNamed("start", start.longitude, start.latitude)),
            /* rc          = */ rc
        )
        engine.doRoundTrip()

        val track = engine.foundTrack ?: throw NoRouteFoundException()
        if (track.nodes.isNullOrEmpty()) throw EmptyRouteGeometryException()

        val points = track.nodes.map { node ->
            RoutePoint(
                latitude  = node.getCoordField("ilat") / 1_000_000.0 - 90.0,
                longitude = node.getCoordField("ilon") / 1_000_000.0 - 180.0,
                elevationMeters = node.getElevMetersOrNull()
            )
        }
        val distanceMeters  = haversineDistanceMeters(points)
        val durationSeconds = distanceMeters / profile.typicalSpeedMs
        BikeRoute(
            points          = points,
            distanceMeters  = distanceMeters,
            durationSeconds = durationSeconds,
            source          = RoutingSource.BROUTER_OFFLINE
        )
    }

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
         */
        private const val PROFILES_VERSION = "4"
        private const val PROFILES_VERSION_FILE = ".profiles_version"

        // ── Start-reversal detection (see startUTurnForwardDir) ───────────────
        /** First segment vs destination-direction difference that counts as a start U-turn. */
        private const val REVERSAL_MIN_ANGLE_DEG = 110.0

        // ── Round-trip generation (see calculateRoundTrip) ────────────────────
        /** Approximate ratio of looped trip length to BRouter's search radius. */
        private const val ROUND_TRIP_LENGTH_TO_RADIUS = 3.0
        /** Number of waypoints BRouter spreads around the ring. */
        private const val ROUND_TRIP_POINTS = 5
        private const val MIN_ROUND_TRIP_RADIUS_M = 500
        private const val MAX_ROUND_TRIP_RADIUS_M = 40_000
    }


    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Runs one BRouter calculation from [from] to [to] with an optional
     * [startDir] (compass degrees) hint. Fresh waypoint nodes are created per call
     * because BRouter mutates their matching state, so they must not be reused
     * across the two passes. Returns the decoded route points.
     */
    private fun runBrouter(
        profilePath: String,
        from: GeoCoordinate,
        to: GeoCoordinate,
        startDir: Int?
    ): List<RoutePoint> {
        val rc = RoutingContext().apply {
            localFunction = profilePath          // path to .brf WITHOUT extension
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
        engine.doRun(0L)

        val track = engine.foundTrack
        if (BuildConfig.DEBUG) Log.d(TAG, "foundTrack = $track  nodes=${track?.nodes?.size}")
        if (track == null) throw NoRouteFoundException()
        if (track.nodes.isNullOrEmpty()) throw EmptyRouteGeometryException()


        return track.nodes.map { node ->
            RoutePoint(
                latitude  = node.getCoordField("ilat") / 1_000_000.0 - 90.0,
                longitude = node.getCoordField("ilon") / 1_000_000.0 - 180.0,
                elevationMeters = node.getElevMetersOrNull()
            )
        }
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
     * Reads a private integer coordinate field (ilat / ilon) from an
     * [OsmPathElement] by walking the class hierarchy with reflection.
     * This is necessary because BRouter declares those fields as private
     * in the concrete subclass while the public API offers no getter.
     */
    private fun Any.getCoordField(fieldName: String): Int {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.getInt(this)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("Field '$fieldName' not found in ${javaClass.name} or any superclass")
    }

    /**
     * Reads the node's terrain elevation in metres via BRouter's public
     * `OsmPos.getElev()` (which returns `selev / 4.0`). Reflection is used so the
     * engine stays decoupled from the exact `btools` class hierarchy. Returns
     * `null` when no elevation is available (the sentinel short maps to an absurd
     * value) so callers can fall back to GPS altitude.
     */
    private fun Any.getElevMetersOrNull(): Double? {
        return try {
            val m = javaClass.getMethod("getElev")
            val v = (m.invoke(this) as? Number)?.toDouble() ?: return null
            // BRouter uses a short sentinel for "no elevation"; reject absurd values.
            if (v < -1_000.0 || v > 9_000.0) null else v
        } catch (_: Exception) {
            null
        }
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

