package de.velospot.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.velospot.core.tracking.RideTracker
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.dao.RecordedRideSummaryRow
import de.velospot.data.local.entity.RecordedRideEntity
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RecordedRidesRepositoryImpl] using an in-memory fake DAO and the
 * production Moshi setup, so the entity ⇄ domain mapping and JSON track (de)serialisation
 * are exercised end to end.
 */
class RecordedRidesRepositoryImplTest {

    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private class FakeRecordedRideDao : RecordedRideDao {
        val store = MutableStateFlow<List<RecordedRideEntity>>(emptyList())

        private fun sorted() = store.value.sortedByDescending { it.startedAt }

        override fun getSummariesFlow(): Flow<List<RecordedRideSummaryRow>> =
            store.map { list -> list.sortedByDescending { it.startedAt }.map { it.toRow() } }

        override fun getAllFlow(): Flow<List<RecordedRideEntity>> = store.map { sorted() }
        override suspend fun getById(id: String): RecordedRideEntity? = store.value.firstOrNull { it.id == id }
        override suspend fun getByIds(ids: List<String>): List<RecordedRideEntity> = store.value.filter { it.id in ids }

        override suspend fun upsert(ride: RecordedRideEntity) {
            store.value = store.value.filterNot { it.id == ride.id } + ride
        }

        override suspend fun updateName(id: String, name: String?) = mutate(id) { it.copy(name = name) }
        override suspend fun updateArchivedAt(id: String, archivedAt: Long?) = mutate(id) { it.copy(archivedAt = archivedAt) }
        override suspend fun updateElevation(id: String, gain: Double, loss: Double) =
            mutate(id) { it.copy(elevationGainMeters = gain, elevationLossMeters = loss) }
        override suspend fun updateMaxSpeed(id: String, maxSpeedMps: Double) =
            mutate(id) { it.copy(maxSpeedMps = maxSpeedMps) }
        override suspend fun updateBikeProfile(id: String, bikeProfileId: String?) = mutate(id) { it.copy(bikeProfileId = bikeProfileId) }
        override suspend fun updateSourceRoute(id: String, sourceRouteId: String?) = mutate(id) { it.copy(sourceRouteId = sourceRouteId) }

        override suspend fun clearBikeProfile(bikeProfileId: String) {
            store.value = store.value.map { if (it.bikeProfileId == bikeProfileId) it.copy(bikeProfileId = null) else it }
        }

        override suspend fun totalDistanceForBike(bikeProfileId: String): Double =
            store.value.filter { it.bikeProfileId == bikeProfileId && !it.isMock }.sumOf { it.distanceMeters }

        override suspend fun delete(id: String) { store.value = store.value.filterNot { it.id == id } }
        override suspend fun deleteAll() { store.value = emptyList() }

        private inline fun mutate(id: String, transform: (RecordedRideEntity) -> RecordedRideEntity) {
            store.value = store.value.map { if (it.id == id) transform(it) else it }
        }

        private fun RecordedRideEntity.toRow() = RecordedRideSummaryRow(
            id, startedAt, endedAt, distanceMeters, elapsedSeconds, movingSeconds,
            avgSpeedMps, maxSpeedMps, elevationGainMeters, elevationLossMeters,
            name, isMock, archivedAt, bikeProfileId
        )
    }

    private fun ride(
        id: String,
        startedAt: Long = 1_000L,
        isMock: Boolean = false,
        bikeProfileId: String? = null,
        distanceMeters: Double = 1_234.0,
    ) = RecordedRide(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + 3_600,
        distanceMeters = distanceMeters,
        elapsedSeconds = 3_600,
        movingSeconds = 3_000,
        avgSpeedMps = 4.1,
        maxSpeedMps = 9.9,
        elevationGainMeters = 120.0,
        elevationLossMeters = 90.0,
        points = listOf(
            TrackPoint(49.75, 6.64, 1_000L, speedMps = 3.5f, altitudeMeters = 180.0, accuracyMeters = 4f),
            TrackPoint(49.76, 6.65, 2_000L),
        ),
        name = "Evening loop",
        isMock = isMock,
        bikeProfileId = bikeProfileId,
    )

    private fun repo(dao: RecordedRideDao = FakeRecordedRideDao()) = RecordedRidesRepositoryImpl(dao, moshi)

    @Test
    fun `saveRide then getRide round-trips including the GPS track`() = runTest {
        val repo = repo()
        repo.saveRide(ride("r1"))

        val loaded = repo.getRide("r1")!!
        assertEquals("r1", loaded.id)
        assertEquals("Evening loop", loaded.name)
        assertEquals(2, loaded.points.size)
        assertEquals(49.75, loaded.points[0].latitude, 0.0)
        assertEquals(3.5f, loaded.points[0].speedMps)
        assertEquals(180.0, loaded.points[0].altitudeMeters)
        // Second point had null optional fields.
        assertNull(loaded.points[1].speedMps)
    }

    @Test
    fun `getRide returns null for an unknown id`() = runTest {
        assertNull(repo().getRide("nope"))
    }

    @Test
    fun `summaries flow is track-free and newest first`() = runTest {
        val repo = repo()
        repo.saveRide(ride("old", startedAt = 1_000L))
        repo.saveRide(ride("new", startedAt = 5_000L))

        val summaries = repo.getRideSummariesFlow().first()
        assertEquals(listOf("new", "old"), summaries.map { it.id })
        assertEquals(1_234.0, summaries.first().distanceMeters, 0.0)
    }

    @Test
    fun `ridesWithTracks flow maps every entity back to domain`() = runTest {
        val repo = repo()
        repo.saveRide(ride("r1"))
        val rides = repo.getRidesWithTracksFlow().first()
        assertEquals(1, rides.size)
        assertEquals(2, rides.first().points.size)
    }

    @Test
    fun `getRides preserves the requested id order and skips missing`() = runTest {
        val repo = repo()
        repo.saveRide(ride("a", startedAt = 1_000L))
        repo.saveRide(ride("b", startedAt = 2_000L))
        repo.saveRide(ride("c", startedAt = 3_000L))

        val result = repo.getRides(listOf("c", "a", "missing"))
        assertEquals(listOf("c", "a"), result.map { it.id })
    }

    @Test
    fun `getRides returns empty for empty input`() = runTest {
        assertTrue(repo().getRides(emptyList()).isEmpty())
    }

    @Test
    fun `updateRideName blanks are stored as null`() = runTest {
        val repo = repo()
        repo.saveRide(ride("r1"))
        repo.updateRideName("r1", "   ")
        assertNull(repo.getRide("r1")!!.name)

        repo.updateRideName("r1", "  Renamed ")
        assertEquals("Renamed", repo.getRide("r1")!!.name)
    }

    @Test
    fun `setRideArchived toggles archivedAt`() = runTest {
        val repo = repo()
        repo.saveRide(ride("r1"))
        repo.setRideArchived("r1", true)
        assertTrue(repo.getRide("r1")!!.isArchived)
        repo.setRideArchived("r1", false)
        assertFalse(repo.getRide("r1")!!.isArchived)
    }

    @Test
    fun `bike profile assignment and clearing`() = runTest {
        val repo = repo()
        repo.saveRide(ride("r1"))
        repo.setRideBikeProfile("r1", "bike-1")
        assertEquals("bike-1", repo.getRide("r1")!!.bikeProfileId)

        repo.clearBikeProfileFromRides("bike-1")
        assertNull(repo.getRide("r1")!!.bikeProfileId)
    }

    @Test
    fun `setSourceRoute persists the source route id`() = runTest {
        val repo = repo()
        repo.saveRide(ride("r1"))
        repo.setSourceRoute("r1", "route-9")
        assertEquals("route-9", repo.getRide("r1")!!.sourceRouteId)
    }

    @Test
    fun `totalDistanceForBike sums real rides only`() = runTest {
        val repo = repo()
        repo.saveRide(ride("real1", bikeProfileId = "b", distanceMeters = 1_000.0))
        repo.saveRide(ride("real2", bikeProfileId = "b", distanceMeters = 2_000.0))
        repo.saveRide(ride("mock", bikeProfileId = "b", isMock = true, distanceMeters = 9_999.0))
        repo.saveRide(ride("other", bikeProfileId = "x", distanceMeters = 500.0))

        assertEquals(3_000.0, repo.totalDistanceForBike("b"), 0.0)
    }

    @Test
    fun `removeRide and clearAll`() = runTest {
        val repo = repo()
        repo.saveRide(ride("a")); repo.saveRide(ride("b"))
        repo.removeRide("a")
        assertNull(repo.getRide("a"))
        assertEquals("b", repo.getRide("b")!!.id)

        repo.clearAll()
        assertTrue(repo.getRidesWithTracksFlow().first().isEmpty())
    }

    // ── Historical max-speed backfill (recompute from the stored track) ────────

    /**
     * Builds a ride whose aggregate [RecordedRide.maxSpeedMps] is deliberately set
     * low while its track points carry the given per-point Doppler speeds, so the
     * backfill's job (recompute the aggregate from the points) is observable.
     */
    private fun rideWithPointSpeeds(
        id: String,
        storedMax: Double,
        pointSpeeds: List<Float?>,
    ) = RecordedRide(
        id = id,
        startedAt = 1_000L,
        endedAt = 4_600L,
        distanceMeters = 1_234.0,
        elapsedSeconds = 3_600,
        movingSeconds = 3_000,
        avgSpeedMps = 4.1,
        maxSpeedMps = storedMax,
        elevationGainMeters = 0.0,
        elevationLossMeters = 0.0,
        points = pointSpeeds.mapIndexed { i, sp ->
            TrackPoint(49.75 + i * 0.001, 6.64, 1_000L + i * 1_000L, speedMps = sp, altitudeMeters = 180.0, accuracyMeters = 4f)
        },
        name = "Descent",
    )

    @Test
    fun `recomputeStoredMaxSpeed corrects an understated peak from the stored track`() = runTest {
        val repo = repo()
        // Aggregate stored at a bogus low 9.9 m/s (the old corroboration gate would
        // have clamped it), while the track actually peaked at 20 m/s (72 km/h),
        // well within the 27 m/s ceiling.
        repo.saveRide(rideWithPointSpeeds("descent", storedMax = 9.9, pointSpeeds = listOf(5f, 12f, 20f, 8f)))

        repo.recomputeStoredMaxSpeed()

        assertEquals("peak recomputed from the stored track", 20.0, repo.getRide("descent")!!.maxSpeedMps, 0.001)
    }

    @Test
    fun `recomputeStoredMaxSpeed ignores an out-of-range point speed`() = runTest {
        val repo = repo()
        // One outlier point above the physical ceiling (30 m/s ≈ 108 km/h) must be
        // excluded by the `<= MAX_PLAUSIBLE_SPEED_MPS` (27 m/s) filter, so it cannot
        // inflate the backfilled peak. The real in-range peak here is 18 m/s.
        assertEquals(27.0, RideTracker.MAX_PLAUSIBLE_SPEED_MPS, 0.0)
        repo.saveRide(rideWithPointSpeeds("outlier", storedMax = 9.9, pointSpeeds = listOf(6f, 18f, 30f)))

        repo.recomputeStoredMaxSpeed()

        assertEquals("out-of-range outlier excluded from the peak", 18.0, repo.getRide("outlier")!!.maxSpeedMps, 0.001)
    }

    @Test
    fun `recomputeStoredMaxSpeed is idempotent`() = runTest {
        val repo = repo()
        repo.saveRide(rideWithPointSpeeds("idem", storedMax = 9.9, pointSpeeds = listOf(5f, 16f, 21f)))

        repo.recomputeStoredMaxSpeed()
        val first = repo.getRide("idem")!!.maxSpeedMps
        repo.recomputeStoredMaxSpeed()
        val second = repo.getRide("idem")!!.maxSpeedMps

        assertEquals(21.0, first, 0.001)
        assertEquals("running the backfill twice yields the same value", first, second, 0.0)
    }
}
