package de.velospot.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import de.velospot.core.tracking.AltitudeSample
import de.velospot.core.tracking.ElevationAccumulator
import de.velospot.core.tracking.RideTracker
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.dao.RecordedRideSummaryRow
import de.velospot.data.local.entity.RecordedRideEntity
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.model.TrackPoint
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

    override fun getRideSummariesFlow(): Flow<List<RecordedRideSummary>> =
        recordedRideDao.getSummariesFlow().map { rows -> rows.map { it.toDomain() } }

    override fun getRidesWithTracksFlow(): Flow<List<RecordedRide>> =
        recordedRideDao.getAllFlow()
            .map { entities -> entities.map { it.toDomain() } }
            // Deserialising every ride's track is CPU-bound; keep it off the main
            // thread so collectors (the map overlays / analysis) never jank.
            .flowOn(Dispatchers.Default)

    override suspend fun getRide(id: String): RecordedRide? =
        withContext(Dispatchers.Default) {
            recordedRideDao.getById(id)?.toDomain()
        }

    override suspend fun getRides(ids: List<String>): List<RecordedRide> {
        if (ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            // Re-order to match the requested ids (SQLite's `IN` ignores order).
            val byId = recordedRideDao.getByIds(ids).associateBy { it.id }
            ids.mapNotNull { byId[it]?.toDomain() }
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
        recordedRideDao.getAllFlow().first().forEach { entity ->
            val points = runCatching { pointsAdapter.fromJson(entity.pointsJson) }
                .getOrNull().orEmpty()
            if (points.none { it.altitudeMeters != null }) return@forEach
            val result = ElevationAccumulator.compute(
                points.map { AltitudeSample(it.altitudeMeters, it.accuracyMeters) }
            )
            if (result.gainMeters != entity.elevationGainMeters ||
                result.lossMeters != entity.elevationLossMeters
            ) {
                recordedRideDao.updateElevation(entity.id, result.gainMeters, result.lossMeters)
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
        recordedRideDao.getAllFlow().first().forEach { entity ->
            val points = runCatching { pointsAdapter.fromJson(entity.pointsJson) }
                .getOrNull().orEmpty()
            val maxSpeed = points
                .mapNotNull { it.speedMps?.toDouble() }
                .filter { it in 0.0..RideTracker.MAX_PLAUSIBLE_SPEED_MPS }
                .maxOrNull() ?: return@forEach
            if (maxSpeed != entity.maxSpeedMps) {
                recordedRideDao.updateMaxSpeed(entity.id, maxSpeed)
            }
        }
    }

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

    private fun RecordedRideEntity.toDomain() = RecordedRide(
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
        sourceRouteId = sourceRouteId
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
        sourceRouteId = sourceRouteId
    )
}

