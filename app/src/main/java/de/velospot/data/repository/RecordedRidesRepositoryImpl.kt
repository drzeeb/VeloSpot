package de.velospot.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import de.velospot.core.tracking.AltitudeSample
import de.velospot.core.tracking.ElevationAccumulator
import de.velospot.core.tracking.RideTracker
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.dao.RecordedRideMetaRow
import de.velospot.data.local.dao.RecordedRideSummaryRow
import de.velospot.data.local.entity.RecordedRideEntity
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.model.RideTrackGeometry
import de.velospot.domain.model.TrackPoint
import de.velospot.domain.model.WeatherSnapshot
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [RecordedRidesRepository].
 *
 * The aggregate ride statistics live in dedicated columns; the full GPS track is
 * serialised to a compact JSON array (via the shared [Moshi]) so the polyline can
 * be redrawn and the speed/elevation timeline rebuilt when a ride is reopened.
 *
 * The timeline reads the track-free [getRideSummariesFlow]; tracks are only ever
 * deserialised on demand (single ride / export) or by the dedicated
 * [getRidesWithTracksFlow], and always off the main thread ([Dispatchers.Default]).
 */
@Singleton
class RecordedRidesRepositoryImpl @Inject constructor(
    private val recordedRideDao: RecordedRideDao,
    moshi: Moshi
) : RecordedRidesRepository {

    private val pointsAdapter = moshi.adapter<List<TrackPoint>>(
        Types.newParameterizedType(List::class.java, TrackPoint::class.java)
    )

    private val weatherAdapter = moshi.adapter(WeatherSnapshot::class.java)

    /**
     * Geometry-only track adapter: parses just the `latitude`/`longitude` of each
     * stored point (Moshi silently ignores the speed/altitude/accuracy/timestamp
     * keys), so the overlays never pay to deserialise or hold fields they never
     * draw.
     */
    private val geometryAdapter = moshi.adapter<List<LatLonPoint>>(
        Types.newParameterizedType(List::class.java, LatLonPoint::class.java)
    )

    override fun getRideSummariesFlow(): Flow<List<RecordedRideSummary>> =
        recordedRideDao.getSummariesFlow().map { rows -> rows.map { it.toDomain() } }

    override fun getRidesWithTracksFlow(): Flow<List<RecordedRide>> =
        recordedRideDao.getAllMetaFlow()
            .map { rows -> rows.map { it.toDomainWith(readPointsJson(it.id)) } }
            // Reassembling + deserialising every ride's track is CPU-bound; keep it
            // off the main thread so collectors (the map overlays / analysis) never
            // jank. Also never selects `pointsJson` whole (chunked reads) so a dense
            // imported track cannot blow the ~2 MB `CursorWindow` limit.
            .flowOn(Dispatchers.Default)

    override fun getRideTrackGeometriesFlow(): Flow<List<RideTrackGeometry>> =
        recordedRideDao.getTrackKeysFlow()
            // Gate on the track *set* alone: Room re-runs the query on any write to
            // the table, but the key rows (id, isMock, track length) don't change on
            // a rename / archive / bike-reassign, so `distinctUntilChanged` avoids
            // re-deserialising the whole history while a layer is visible.
            .distinctUntilChanged()
            .map { keys -> keys.map { key -> RideTrackGeometry(key.isMock, readTrackGeometry(key.id)) } }
            // Chunked reads + a lat/lon-only parse are CPU-bound; keep them off the
            // main thread so the overlay collectors never jank.
            .flowOn(Dispatchers.Default)

    override suspend fun getRide(id: String): RecordedRide? =
        withContext(Dispatchers.Default) {
            val meta = recordedRideDao.getMetaById(id) ?: return@withContext null
            meta.toDomainWith(readPointsJson(id))
        }

    override suspend fun getRides(ids: List<String>): List<RecordedRide> {
        if (ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            // Re-order to match the requested ids (SQLite's `IN` ignores order).
            val byId = recordedRideDao.getMetaByIds(ids).associateBy { it.id }
            ids.mapNotNull { id -> byId[id]?.toDomainWith(readPointsJson(id)) }
        }
    }

    override suspend fun saveRide(ride: RecordedRide) =
        recordedRideDao.upsert(ride.toEntity())

    override suspend fun removeRide(id: String) =
        recordedRideDao.delete(id)

    override suspend fun clearAll() =
        recordedRideDao.deleteAll()

    override suspend fun updateRideName(id: String, name: String?) =
        recordedRideDao.updateName(id, name?.trim()?.takeIf { it.isNotBlank() })

    override suspend fun setRideArchived(id: String, archived: Boolean) =
        recordedRideDao.updateArchivedAt(id, if (archived) System.currentTimeMillis() else null)

    override suspend fun setRideBikeProfile(id: String, bikeProfileId: String?) =
        recordedRideDao.updateBikeProfile(id, bikeProfileId)

    override suspend fun setSourceRoute(id: String, routeId: String?) =
        recordedRideDao.updateSourceRoute(id, routeId)

    override suspend fun clearBikeProfileFromRides(bikeProfileId: String) =
        recordedRideDao.clearBikeProfile(bikeProfileId)

    override suspend fun totalDistanceForBike(bikeProfileId: String): Double =
        recordedRideDao.totalDistanceForBike(bikeProfileId)

    override suspend fun recomputeStoredElevation() = withContext(Dispatchers.Default) {
        // Recompute gain/loss for every stored ride from its raw altitudes using the
        // shared integrator, then write only the two derived columns back. This
        // self-corrects both the summary flow (a direct column projection) and the
        // detail view. Rides without altitude points keep 0/0 and are skipped.
        recordedRideDao.getAllMetaFlow().first().forEach { meta ->
            val points = runCatching { pointsAdapter.fromJson(readPointsJson(meta.id)) }
                .getOrNull().orEmpty()
            if (points.none { it.altitudeMeters != null }) return@forEach
            val result = ElevationAccumulator.compute(
                points.map { AltitudeSample(it.altitudeMeters, it.accuracyMeters) }
            )
            if (result.gainMeters != meta.elevationGainMeters ||
                result.lossMeters != meta.elevationLossMeters
            ) {
                recordedRideDao.updateElevation(meta.id, result.gainMeters, result.lossMeters)
            }
        }
    }

    override suspend fun recomputeStoredMaxSpeed() = withContext(Dispatchers.Default) {
        // Recompute the peak speed for every stored ride from its raw per-point
        // Doppler speeds and write only that derived column back. The stored speeds
        // were already accepted through the recorder's gates when recorded, so they
        // are trusted here: the aggregate is simply the max over in-range samples,
        // guarded by a non-negative floor and the shared physical ceiling. This
        // self-corrects both the summary flow (a direct column projection) and the
        // detail view for historical rides understated by the old corroboration
        // gate. Rides without any in-range speed sample are skipped.
        recordedRideDao.getAllMetaFlow().first().forEach { meta ->
            val points = runCatching { pointsAdapter.fromJson(readPointsJson(meta.id)) }
                .getOrNull().orEmpty()
            val maxSpeed = points
                .mapNotNull { it.speedMps?.toDouble() }
                .filter { it in 0.0..RideTracker.MAX_PLAUSIBLE_SPEED_MPS }
                .maxOrNull() ?: return@forEach
            if (maxSpeed != meta.maxSpeedMps) {
                recordedRideDao.updateMaxSpeed(meta.id, maxSpeed)
            }
        }
    }

    /**
     * Reassembles the full `pointsJson` for [id] with bounded [CHUNK]-sized
     * `substr` reads so the huge cell is never squeezed into a single cursor row —
     * a dense imported track can exceed SQLite's ~2 MB `CursorWindow` limit and
     * would otherwise throw `SQLiteBlobTooBigException` on a `SELECT *`. Returns an
     * empty string when the ride has no track (length null or 0).
     */
    private suspend fun readPointsJson(id: String): String {
        val length = recordedRideDao.getPointsJsonLength(id) ?: 0
        if (length <= 0) return ""
        val builder = StringBuilder(length)
        var start = 1 // SQLite substr is 1-based.
        while (start <= length) {
            val chunk = recordedRideDao.getPointsJsonChunk(id, start, CHUNK) ?: break
            builder.append(chunk)
            start += CHUNK
        }
        return builder.toString()
    }

    /**
     * Reads [id]'s track (chunked, via [readPointsJson]) and parses **only** the
     * lat/lon of each point into bare [TrackPoint]s for the geometry-only overlay
     * source. Speeds, altitudes, accuracies and timestamps are never parsed or
     * retained. Returns an empty list for a ride without a track.
     */
    private suspend fun readTrackGeometry(id: String): List<TrackPoint> {
        val json = readPointsJson(id)
        if (json.isEmpty()) return emptyList()
        val raw = runCatching { geometryAdapter.fromJson(json) }.getOrNull().orEmpty()
        return raw.map { TrackPoint(latitude = it.latitude, longitude = it.longitude, timestamp = 0L) }
    }

    /** Minimal JSON view of a stored track point: only the drawn coordinates. */
    private class LatLonPoint(val latitude: Double, val longitude: Double)

    private fun RecordedRideSummaryRow.toDomain() = RecordedRideSummary(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        distanceMeters = distanceMeters,
        elapsedSeconds = elapsedSeconds,
        movingSeconds = movingSeconds,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        name = name,
        isMock = isMock,
        archivedAt = archivedAt,
        bikeProfileId = bikeProfileId
    )

    private fun RecordedRideMetaRow.toDomainWith(pointsJson: String) = RecordedRide(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        distanceMeters = distanceMeters,
        elapsedSeconds = elapsedSeconds,
        movingSeconds = movingSeconds,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        points = runCatching { pointsAdapter.fromJson(pointsJson) }.getOrNull().orEmpty(),
        name = name,
        isMock = isMock,
        archivedAt = archivedAt,
        bikeProfileId = bikeProfileId,
        sourceRouteId = sourceRouteId,
        weather = weatherJson?.let { runCatching { weatherAdapter.fromJson(it) }.getOrNull() }
    )

    private fun RecordedRide.toEntity() = RecordedRideEntity(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        distanceMeters = distanceMeters,
        elapsedSeconds = elapsedSeconds,
        movingSeconds = movingSeconds,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        pointsJson = pointsAdapter.toJson(points),
        name = name?.trim()?.takeIf { it.isNotBlank() },
        isMock = isMock,
        archivedAt = archivedAt,
        bikeProfileId = bikeProfileId,
        sourceRouteId = sourceRouteId,
        // Persist the captured Open-Meteo snapshot so weather survives a reload — it
        // is shown by the (DB-backed) analysis screen and by re-opening the detail
        // sheet, not just the in-memory object right after recording. Null stays null.
        weatherJson = weather?.let { weatherAdapter.toJson(it) }
    )

    private companion object {
        /**
         * Track slice size (chars) for the chunked `pointsJson` reassembly. 256 KB
         * stays comfortably under SQLite's ~2 MB `CursorWindow` per-row limit while
         * keeping the number of round-trips low for typical tracks.
         */
        private const val CHUNK = 262_144
    }
}

