package de.velospot.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.dao.RecordedRideSummaryRow
import de.velospot.data.local.entity.RecordedRideEntity
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.model.TrackPoint
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
        archivedAt = archivedAt
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
        archivedAt = archivedAt
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
        archivedAt = archivedAt
    )
}

