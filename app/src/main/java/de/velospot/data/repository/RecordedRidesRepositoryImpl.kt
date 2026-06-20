package de.velospot.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.entity.RecordedRideEntity
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [RecordedRidesRepository].
 *
 * The aggregate ride statistics live in dedicated columns; the full GPS track is
 * serialised to a compact JSON array (via the shared [Moshi]) so the polyline can
 * be redrawn and the speed/elevation timeline rebuilt when a ride is reopened.
 */
@Singleton
class RecordedRidesRepositoryImpl @Inject constructor(
    private val recordedRideDao: RecordedRideDao,
    moshi: Moshi
) : RecordedRidesRepository {

    private val pointsAdapter = moshi.adapter<List<TrackPoint>>(
        Types.newParameterizedType(List::class.java, TrackPoint::class.java)
    )

    override fun getRidesFlow(): Flow<List<RecordedRide>> =
        recordedRideDao.getAllFlow().map { entities -> entities.map { it.toDomain() } }

    override suspend fun saveRide(ride: RecordedRide) =
        recordedRideDao.upsert(ride.toEntity())

    override suspend fun removeRide(id: String) =
        recordedRideDao.delete(id)

    override suspend fun clearAll() =
        recordedRideDao.deleteAll()

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
        points = runCatching { pointsAdapter.fromJson(pointsJson) }.getOrNull().orEmpty()
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
        pointsJson = pointsAdapter.toJson(points)
    )
}

