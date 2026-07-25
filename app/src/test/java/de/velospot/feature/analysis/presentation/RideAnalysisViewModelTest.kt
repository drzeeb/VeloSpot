package de.velospot.feature.analysis.presentation

import androidx.lifecycle.SavedStateHandle
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.model.TrackPoint
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideAnalysisViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeRidesRepository(rides: List<RecordedRide>) : RecordedRidesRepository {
        val flow = MutableStateFlow(rides)
        override fun getRidesWithTracksFlow(): Flow<List<RecordedRide>> = flow
        override fun getRideSummariesFlow(): Flow<List<RecordedRideSummary>> = MutableStateFlow(emptyList())
        override suspend fun getRide(id: String): RecordedRide? = flow.value.firstOrNull { it.id == id }
        override suspend fun getRides(ids: List<String>): List<RecordedRide> = emptyList()
        override suspend fun saveRide(ride: RecordedRide) = Unit
        override suspend fun updateRideName(id: String, name: String?) = Unit
        override suspend fun setRideArchived(id: String, archived: Boolean) = Unit
        override suspend fun removeRide(id: String) = Unit
        override suspend fun clearAll() = Unit
    }

    /** A straight, gently-climbing south-to-north ride of [count] fixes, 1 s apart. */
    private fun ride(id: String, count: Int = 200): RecordedRide {
        val stepLat = 0.00005
        val points = (0 until count).map { i ->
            TrackPoint(
                latitude = i * stepLat,
                longitude = 8.0,
                timestamp = i * 1_000L,
                speedMps = 5.56f,
                altitudeMeters = 100.0 + i,
                accuracyMeters = 5f,
            )
        }
        val elapsed = (count - 1).toLong()
        return RecordedRide(
            id = id,
            startedAt = 0,
            endedAt = elapsed * 1_000,
            distanceMeters = 5.566 * (count - 1),
            elapsedSeconds = elapsed,
            movingSeconds = elapsed,
            avgSpeedMps = 5.56,
            maxSpeedMps = 8.0,
            elevationGainMeters = 40.0,
            elevationLossMeters = 5.0,
            points = points,
            name = "Test ride",
        )
    }

    private fun viewModel(repo: RecordedRidesRepository, rideId: String) = RideAnalysisViewModel(
        repository = repo,
        savedStateHandle = SavedStateHandle(mapOf(RideAnalysisViewModel.ARG_RIDE_ID to rideId)),
    )

    /**
     * Awaits the first settled (non-[RideAnalysisUiState.Loading]) state. The analysis
     * runs on a real [Dispatchers.Default] via `flowOn`, so we can't just advance the
     * virtual scheduler — subscribing and awaiting resumes once Default has emitted.
     */
    private suspend fun RideAnalysisViewModel.awaitSettled(): RideAnalysisUiState =
        uiState.first { it !is RideAnalysisUiState.Loading }

    @Test
    fun `missing rideId argument fails fast`() {
        val ex = runCatching {
            RideAnalysisViewModel(FakeRidesRepository(emptyList()), SavedStateHandle())
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
    }

    @Test
    fun `uiState is NotFound when the ride is absent`() = runTest {
        val vm = viewModel(FakeRidesRepository(listOf(ride("other"))), rideId = "missing")
        assertEquals(RideAnalysisUiState.NotFound, vm.awaitSettled())
    }

    @Test
    fun `uiState is Ready with the analysed ride when present`() = runTest {
        val target = ride("r", count = 400)
        val vm = viewModel(FakeRidesRepository(listOf(target)), rideId = "r")

        val state = vm.awaitSettled()
        assertTrue(state is RideAnalysisUiState.Ready)
        state as RideAnalysisUiState.Ready
        assertEquals("r", state.ride.id)
        assertEquals(target.distanceMeters, state.analysis.distanceMeters, 0.0)
        // ~2.2 km ride → at least two full-km splits were computed off the track.
        assertTrue(state.analysis.splits.size >= 2)
    }

    @Test
    fun `uiState reacts to the ride being deleted`() = runTest {
        val repo = FakeRidesRepository(listOf(ride("r")))
        val vm = viewModel(repo, rideId = "r")
        assertTrue(vm.awaitSettled() is RideAnalysisUiState.Ready)

        repo.flow.value = emptyList()

        // Await the specific NotFound state (the cached Ready lingers until the
        // Default-dispatched re-analysis of the now-empty list completes).
        assertEquals(
            RideAnalysisUiState.NotFound,
            vm.uiState.first { it is RideAnalysisUiState.NotFound },
        )
    }
}




