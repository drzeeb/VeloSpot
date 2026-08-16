package de.velospot.feature.map.presentation.ride

import android.content.Context
import de.velospot.core.location.LocationController
import de.velospot.core.tracking.RideRecordingManager
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.repository.LocationRepository
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for the "open a recorded ride" loading flag added to
 * [RideTrackingController.isLoadingRide]: it must toggle true→false around the
 * off-main track load in [RideTrackingController.selectRide], including the
 * ride-not-found path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideTrackingControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun controller(repository: RecordedRidesRepository): RideTrackingController {
        val ctx = mock<Context> { whenever(it.filesDir).doReturn(tmp.root) }
        val locationRepo = mock<LocationRepository> {
            whenever(it.getCurrentLocationFlow()).doReturn(emptyFlow())
        }
        val manager = RideRecordingManager(
            context = ctx,
            locationController = LocationController(locationRepo),
            recordedRidesRepository = repository,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        return RideTrackingController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = repository,
            manager = manager,
            currentLocation = { null },
            onUserMessage = {},
            clearOtherSelections = {},
            moveCamera = {},
            overlayTracksNeeded = flowOf(false),
        )
    }

    @Test
    fun `selectRide clears the loading flag after a found ride`() = runTest {
        val ride = RecordedRide(
            id = "r1",
            startedAt = 0L,
            endedAt = 1L,
            distanceMeters = 0.0,
            elapsedSeconds = 1L,
            movingSeconds = 1L,
            avgSpeedMps = 0.0,
            maxSpeedMps = 0.0,
            elevationGainMeters = 0.0,
            elevationLossMeters = 0.0,
            points = emptyList(),
        )
        val controller = controller(FakeRepo(listOf(ride)))

        controller.selectRide(ride.toSummary())

        // Unconfined scope runs the coroutine synchronously, so by the time selectRide
        // returns the load has completed and the flag is back to false.
        assertFalse(controller.isLoadingRide.value)
        assertEquals("r1", controller.selectedRide.value?.id)
    }

    @Test
    fun `selectRide clears the loading flag when the ride is not found`() = runTest {
        val controller = controller(FakeRepo(emptyList()))

        controller.selectRide(
            RecordedRideSummary(
                id = "missing",
                startedAt = 0L,
                endedAt = 1L,
                distanceMeters = 0.0,
                elapsedSeconds = 1L,
                movingSeconds = 1L,
                avgSpeedMps = 0.0,
                maxSpeedMps = 0.0,
                elevationGainMeters = 0.0,
                elevationLossMeters = 0.0,
            )
        )

        assertFalse(controller.isLoadingRide.value)
        assertNull(controller.selectedRide.value)
    }

    @Test
    fun `mergeRides saves the merged ride, archives the sources and opens it`() = runTest {
        val a = simpleRide("a", startedAt = 100L, endedAt = 200L, distance = 1000.0)
        val b = simpleRide("b", startedAt = 300L, endedAt = 400L, distance = 2000.0)
        val repo = FakeRepo(listOf(a, b))
        val controller = controller(repo)

        controller.mergeRides(listOf("a", "b"), name = "Combined")

        val stored = repo.snapshot()
        val merged = stored.firstOrNull { it.id !in setOf("a", "b") }
        assertEquals("Combined", merged?.name)
        assertEquals(3000.0, merged?.distanceMeters)
        // Sources are archived (reversible), not deleted.
        assertNull(merged?.archivedAt)
        assertEquals(1L, stored.first { it.id == "a" }.archivedAt)
        assertEquals(1L, stored.first { it.id == "b" }.archivedAt)
        // The merged ride's detail sheet is opened.
        assertEquals(merged?.id, controller.selectedRide.value?.id)
        // An Undo is offered referencing the sources.
        assertEquals(setOf("a", "b"), controller.mergeUndo.value?.sourceIds?.toSet())
    }

    @Test
    fun `undoMerge restores the sources and deletes the merged ride`() = runTest {
        val a = simpleRide("a", startedAt = 100L, endedAt = 200L, distance = 1000.0)
        val b = simpleRide("b", startedAt = 300L, endedAt = 400L, distance = 2000.0)
        val repo = FakeRepo(listOf(a, b))
        val controller = controller(repo)

        controller.mergeRides(listOf("a", "b"), name = "Combined")
        val mergedId = controller.mergeUndo.value?.mergedRideId
        controller.undoMerge()

        val stored = repo.snapshot()
        assertNull(stored.firstOrNull { it.id == mergedId })
        assertNull(stored.first { it.id == "a" }.archivedAt)
        assertNull(stored.first { it.id == "b" }.archivedAt)
        assertNull(controller.mergeUndo.value)
    }

    private fun simpleRide(
        id: String,
        startedAt: Long,
        endedAt: Long,
        distance: Double
    ) = RecordedRide(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        distanceMeters = distance,
        elapsedSeconds = 10L,
        movingSeconds = 10L,
        avgSpeedMps = distance / 10.0,
        maxSpeedMps = 5.0,
        elevationGainMeters = 0.0,
        elevationLossMeters = 0.0,
        points = emptyList(),
    )

    private fun RecordedRide.toSummary() = RecordedRideSummary(
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
    )

    private class FakeRepo(initial: List<RecordedRide>) : RecordedRidesRepository {
        private val rides = MutableStateFlow(initial)

        fun snapshot(): List<RecordedRide> = rides.value

        override fun getRideSummariesFlow(): Flow<List<RecordedRideSummary>> =
            rides.map { list ->
                list.map {
                    RecordedRideSummary(
                        id = it.id,
                        startedAt = it.startedAt,
                        endedAt = it.endedAt,
                        distanceMeters = it.distanceMeters,
                        elapsedSeconds = it.elapsedSeconds,
                        movingSeconds = it.movingSeconds,
                        avgSpeedMps = it.avgSpeedMps,
                        maxSpeedMps = it.maxSpeedMps,
                        elevationGainMeters = it.elevationGainMeters,
                        elevationLossMeters = it.elevationLossMeters,
                    )
                }
            }

        override fun getRidesWithTracksFlow(): Flow<List<RecordedRide>> = rides

        override suspend fun getRide(id: String): RecordedRide? =
            rides.value.firstOrNull { it.id == id }

        override suspend fun getRides(ids: List<String>): List<RecordedRide> =
            ids.mapNotNull { id -> rides.value.firstOrNull { it.id == id } }

        override suspend fun saveRide(ride: RecordedRide) {
            rides.value = rides.value.filterNot { it.id == ride.id } + ride
        }

        override suspend fun removeRide(id: String) {
            rides.value = rides.value.filterNot { it.id == id }
        }

        override suspend fun updateRideName(id: String, name: String?) {
            rides.value = rides.value.map { if (it.id == id) it.copy(name = name) else it }
        }

        override suspend fun setRideArchived(id: String, archived: Boolean) {
            rides.value = rides.value.map {
                if (it.id == id) it.copy(archivedAt = if (archived) 1L else null) else it
            }
        }

        override suspend fun mergeRides(
            ids: List<String>,
            newId: String,
            name: String?
        ): RecordedRide? {
            val sources = getRides(ids)
            if (!de.velospot.core.analysis.RideMerger.canMerge(sources)) return null
            val merged = de.velospot.core.analysis.RideMerger.merge(sources, newId, name)
            saveRide(merged)
            sources.forEach { setRideArchived(it.id, true) }
            return merged
        }

        override suspend fun clearAll() { rides.value = emptyList() }
    }
}



