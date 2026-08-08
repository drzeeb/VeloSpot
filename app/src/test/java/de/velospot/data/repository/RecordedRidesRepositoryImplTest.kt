package de.velospot.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.velospot.core.tracking.RideTracker
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.dao.RecordedRideMetaRow
import de.velospot.data.local.dao.RecordedRideSummaryRow
import de.velospot.data.local.dao.RecordedRideTrackKeyRow
import de.velospot.data.local.entity.RecordedRideEntity
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import de.velospot.domain.model.WeatherSnapshot
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

        override fun getSummariesFlow(): Flow<List<RecordedRideSummaryRow>> =
            store.map { list -> list.sortedByDescending { it.startedAt }.map { it.toRow() } }

        override fun getAllMetaFlow(): Flow<List<RecordedRideMetaRow>> =
            store.map { list -> list.sortedByDescending { it.startedAt }.map { it.toMetaRow() } }

        override fun getTrackKeysFlow(): Flow<List<RecordedRideTrackKeyRow>> =
            store.map { list ->
                list.sortedByDescending { it.startedAt }
                    .map { RecordedRideTrackKeyRow(it.id, it.isMock, it.pointsJson.length) }
            }

        override suspend fun getMetaById(id: String): RecordedRideMetaRow? =
            store.value.firstOrNull { it.id == id }?.toMetaRow()

        override suspend fun getMetaByIds(ids: List<String>): List<RecordedRideMetaRow> =
            store.value.filter { it.id in ids }.map { it.toMetaRow() }

        override suspend fun getPointsJsonLength(id: String): Int? =
            store.value.firstOrNull { it.id == id }?.pointsJson?.length

        override suspend fun getPointsJsonChunk(id: String, start: Int, count: Int): String? {
            // Mirror SQLite substr: 1-based [start], clamped to the string bounds.
            val json = store.value.firstOrNull { it.id == id }?.pointsJson ?: return null
            if (start > json.length) return ""
            val from = (start - 1).coerceIn(0, json.length)
            val to = (from + count).coerceIn(from, json.length)
            return json.substring(from, to)
        }

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

        private fun RecordedRideEntity.toMetaRow() = RecordedRideMetaRow(
            id, startedAt, endedAt, distanceMeters, elapsedSeconds, movingSeconds,
            avgSpeedMps, maxSpeedMps, elevationGainMeters, elevationLossMeters,
            name, isMock, archivedAt, bikeProfileId, sourceRouteId, weatherJson
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
    fun `saveRide persists the captured weather snapshot and reads it back`() = runTest {
        val repo = repo()
        val weather = WeatherSnapshot(
            temperatureC = 20.0,
            apparentTemperatureC = 17.0,
            humidityPct = 38,
            precipitationMm = 0.0,
            weatherCode = 0,
            windSpeedMps = 2.5,
            windDirectionDeg = 180,
            observedAt = 5_000L,
            latitude = 49.75,
            longitude = 6.64,
        )
        repo.saveRide(ride("w1").copy(weather = weather))

        // Reloaded from the DAO (not the in-memory object), the weather must survive.
        assertEquals(weather, repo.getRide("w1")!!.weather)
        assertEquals(weather, repo.getRidesWithTracksFlow().first().single().weather)
    }

    @Test
    fun `saveRide without weather leaves it null`() = runTest {
        val repo = repo()
        repo.saveRide(ride("w0"))
        assertNull(repo.getRide("w0")!!.weather)
    }

    /**
     * Builds a ride whose serialised `pointsJson` comfortably exceeds the 256 KB
     * chunk size (spanning several chunks), reproducing the dense imported GPX that
     * used to blow the ~2 MB `CursorWindow` limit on a `SELECT *`. Proves the
     * chunked `substr` reassembly reconstructs every point, in order.
     */
    private fun largeRide(id: String, pointCount: Int) = RecordedRide(
        id = id,
        startedAt = 1_000L,
        endedAt = 4_600L,
        distanceMeters = 42_000.0,
        elapsedSeconds = 3_600,
        movingSeconds = 3_000,
        avgSpeedMps = 4.1,
        maxSpeedMps = 9.9,
        elevationGainMeters = 120.0,
        elevationLossMeters = 90.0,
        points = List(pointCount) { i ->
            TrackPoint(
                49.0 + i * 0.00001,
                6.0 + i * 0.00001,
                1_000L + i * 1_000L,
                speedMps = 3.5f,
                altitudeMeters = 180.0 + i,
                accuracyMeters = 4f,
            )
        },
        name = "Dense import",
    )

    @Test
    fun `large track spanning multiple chunks round-trips through getRide`() = runTest {
        val repo = repo()
        // ~40k points serialise well over 512 KB, i.e. multiple 256 KB chunks.
        val ride = largeRide("dense", pointCount = 40_000)
        repo.saveRide(ride)

        val loaded = repo.getRide("dense")!!
        assertEquals(ride.points.size, loaded.points.size)
        assertEquals(ride.points.first().latitude, loaded.points.first().latitude, 0.0)
        assertEquals(ride.points.last().latitude, loaded.points.last().latitude, 0.0)
        assertEquals(ride.points.last().timestamp, loaded.points.last().timestamp)
    }

    @Test
    fun `large track round-trips through the ridesWithTracks flow`() = runTest {
        val repo = repo()
        val ride = largeRide("dense", pointCount = 40_000)
        repo.saveRide(ride)

        val rides = repo.getRidesWithTracksFlow().first()
        assertEquals(1, rides.size)
        assertEquals(ride.points.size, rides.first().points.size)
        assertEquals(ride.points.last().timestamp, rides.first().points.last().timestamp)
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
    fun `track geometries flow yields lat-lon-only points and preserves isMock`() = runTest {
        val repo = repo()
        repo.saveRide(ride("real"))
        repo.saveRide(ride("mock", isMock = true))

        val geometries = repo.getRideTrackGeometriesFlow().first()
        assertEquals(2, geometries.size)
        // Newest-first is undefined here (same startedAt); assert by lookup instead.
        val real = geometries.single { !it.isMock }
        val mock = geometries.single { it.isMock }
        assertEquals(2, real.points.size)
        assertEquals(2, mock.points.size)
        // The coordinates round-trip …
        assertEquals(49.75, real.points[0].latitude, 0.0)
        assertEquals(6.64, real.points[0].longitude, 0.0)
        // … but the geometry parse never carries the heavier per-point fields.
        assertNull(real.points[0].speedMps)
        assertNull(real.points[0].altitudeMeters)
        assertNull(real.points[0].accuracyMeters)
    }

    @Test
    fun `track geometries flow is empty for a ride without a track`() = runTest {
        val repo = repo()
        repo.saveRide(ride("empty").copy(points = emptyList()))
        val geometries = repo.getRideTrackGeometriesFlow().first()
        assertEquals(1, geometries.size)
        assertTrue(geometries.single().points.isEmpty())
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
