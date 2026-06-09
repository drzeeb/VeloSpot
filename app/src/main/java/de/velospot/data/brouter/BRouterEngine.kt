package de.velospot.data.brouter

import android.content.Context
import android.util.Log
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import de.velospot.BuildConfig
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
 * Wraps the BRouter offline routing engine (brouter.jar) and exposes a
 * coroutine-friendly API to calculate bike routes entirely on-device.
 *
 * ## Setup
 * 1. Place `brouter.jar` (downloaded from https://brouter.de) into `app/libs/`.
 * 2. Copy the `.brf` profile files from the BRouter ZIP into
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

        val rc = RoutingContext().apply {
            localFunction = profilePath          // path to .brf WITHOUT extension
        }

        try {
            val engine = RoutingEngine(
                /* baseUrl       = */ null,
                /* outfileBase   = */ null,
                /* segmentDir    = */ segmentsDir,
                /* waypoints     = */ listOf(fromPos, toPos),
                /* rc            = */ rc
            )
            engine.doRun(0L)

            val track = engine.foundTrack
            if (BuildConfig.DEBUG) Log.d(TAG, "foundTrack = $track  nodes=${track?.nodes?.size}")
            if (track == null) throw NoRouteFoundException()
            if (track.nodes.isNullOrEmpty()) throw EmptyRouteGeometryException()

            val points = track.nodes.map { node ->
                RoutePoint(
                    latitude  = node.getCoordField("ilat") / 1_000_000.0 - 90.0,
                    longitude = node.getCoordField("ilon") / 1_000_000.0 - 180.0
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
        } catch (e: NoRouteFoundException)      { throw e }
        catch (e: EmptyRouteGeometryException)  { throw e }
        catch (e: Exception) {
            Log.e(TAG, "BRouter routing failed", e)
            throw e
        }
    }

    companion object {
        private const val TAG = "BRouterEngine"
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Copies `.brf` profile files and `lookups.dat` from app assets to
     * [profilesDir] in internal storage so BRouter can read them at runtime.
     * Throws [BRouterProfilesMissingException] when the assets folder is empty,
     * i.e. the user hasn't placed the profile files yet.
     */
    private fun ensureProfiles() {
        profilesDir.mkdirs()
        val assetManager = context.assets
        val profileAssets = assetManager.list("brouter/profiles").orEmpty()
        if (profileAssets.isEmpty()) {
            throw BRouterProfilesMissingException()
        }
        for (asset in profileAssets) {
            val dest = File(profilesDir, asset)
            if (!dest.exists()) {
                assetManager.open("brouter/profiles/$asset").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
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

