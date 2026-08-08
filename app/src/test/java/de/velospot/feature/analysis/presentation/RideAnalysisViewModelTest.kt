package de.velospot.feature.analysis.presentation

import androidx.lifecycle.SavedStateHandle
import de.velospot.core.map.LayerVisibility
import de.velospot.core.map.MapLayerCategory
import de.velospot.core.map.RideViewOptions
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.model.TrackPoint
import de.velospot.domain.model.WeatherSnapshot
import de.velospot.domain.repository.MapSettingsRepository
import de.velospot.domain.repository.RecordedRidesRepository
import de.velospot.testsupport.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideAnalysisViewModelTest {

    /**
     * Installs a controlled test dispatcher as `Dispatchers.Main` and resets it only
     * *after* [tearDown]. That ordering lets [tearDown] cancel every view-model's
     * `viewModelScope` (which cancels its `stateIn` collector and the `flowOn`
     * upstream) and drain the scheduler while the test dispatcher is still Main — so
     * no leaked coroutine can dispatch onto a reset (missing on the JVM) Main and
     * surface as a flaky `UncaughtExceptionsBeforeTest` against a later test.
     */
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dispatcher get() = mainDispatcherRule.dispatcher

    /** View-models built by [viewModel]; their scopes are cancelled in [tearDown]. */
    private val createdViewModels = mutableListOf<RideAnalysisViewModel>()

    @After
    fun tearDown() {
        // Cancel each view-model's viewModelScope (reflectively — clear() is not
        // public) so its collectors are torn down while Main is still the test
        // dispatcher, then drain the scheduler before the rule resets Main.
        createdViewModels.forEach { vm ->
            runCatching {
                androidx.lifecycle.ViewModel::class.java
                    .getDeclaredMethod("clear")
                    .apply { isAccessible = true }
                    .invoke(vm)
            }
        }
        createdViewModels.clear()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private class FakeRidesRepository(rides: List<RecordedRide>) : RecordedRidesRepository {
        val flow = MutableStateFlow(rides)

        /** Every ride id passed to [getRide], to prove only the target track is loaded. */
        val loadedIds = mutableListOf<String>()

        override fun getRidesWithTracksFlow(): Flow<List<RecordedRide>> = flow
        override fun getRideSummariesFlow(): Flow<List<RecordedRideSummary>> =
            flow.map { list -> list.map { it.toSummary() } }
        override suspend fun getRide(id: String): RecordedRide? {
            loadedIds += id
            return flow.value.firstOrNull { it.id == id }
        }
        override suspend fun getRides(ids: List<String>): List<RecordedRide> = emptyList()
        override suspend fun saveRide(ride: RecordedRide) = Unit
        override suspend fun updateRideName(id: String, name: String?) = Unit
        override suspend fun setRideArchived(id: String, archived: Boolean) = Unit
        override suspend fun removeRide(id: String) = Unit
        override suspend fun clearAll() = Unit
    }


    /** Minimal [MapSettingsRepository] fake — only [weatherEnabled] matters here. */
    private class FakeMapSettings(weatherEnabled: Boolean) : MapSettingsRepository {
        override val weatherEnabled: Flow<Boolean> = MutableStateFlow(weatherEnabled)
        override val layerVisibility: Flow<LayerVisibility> = MutableStateFlow(LayerVisibility())
        override val is3DNavigation: Flow<Boolean> = MutableStateFlow(true)
        override val voiceGuidanceEnabled: Flow<Boolean> = MutableStateFlow(false)
        override val keepScreenOnEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val hudEnabled: Flow<Boolean> = MutableStateFlow(false)
        override val hudExpanded: Flow<Boolean> = MutableStateFlow(false)
        override val portraitLockEnabled: Flow<Boolean> = MutableStateFlow(false)
        override val roundedBuildingsEnabled: Flow<Boolean> = MutableStateFlow(false)
        override val amoledEnabled: Flow<Boolean> = MutableStateFlow(false)
        override val sunAlertEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val rideViewOptions: Flow<RideViewOptions> = MutableStateFlow(RideViewOptions())
        override val onboardingCompleted: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setLayerVisible(category: MapLayerCategory, visible: Boolean) = Unit
        override suspend fun set3DNavigation(enabled: Boolean) = Unit
        override suspend fun setVoiceGuidance(enabled: Boolean) = Unit
        override suspend fun setKeepScreenOn(enabled: Boolean) = Unit
        override suspend fun setHudEnabled(enabled: Boolean) = Unit
        override suspend fun setHudExpanded(expanded: Boolean) = Unit
        override suspend fun setPortraitLock(enabled: Boolean) = Unit
        override suspend fun setRoundedBuildings(enabled: Boolean) = Unit
        override suspend fun setAmoled(enabled: Boolean) = Unit
        override suspend fun setSunAlertEnabled(enabled: Boolean) = Unit
        override suspend fun setWeatherEnabled(enabled: Boolean) = Unit
        override suspend fun setShowMaxSpeedBubble(enabled: Boolean) = Unit
        override suspend fun setColorTrackBySpeed(enabled: Boolean) = Unit
        override suspend fun setOnboardingCompleted(completed: Boolean) = Unit
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

    private fun viewModel(
        repo: RecordedRidesRepository,
        rideId: String,
        weatherEnabled: Boolean = true,
    ) = RideAnalysisViewModel(
        repository = repo,
        mapSettings = FakeMapSettings(weatherEnabled),
        savedStateHandle = SavedStateHandle(mapOf(RideAnalysisViewModel.ARG_RIDE_ID to rideId)),
    ).also { createdViewModels.add(it) }

    /**
     * Awaits the first settled (non-[RideAnalysisUiState.Loading]) state. The analysis
     * runs on a real `Dispatchers.Default` via `flowOn`, so we can't just advance the
     * virtual scheduler — subscribing and awaiting resumes once Default has emitted.
     */
    private suspend fun RideAnalysisViewModel.awaitSettled(): RideAnalysisUiState =
        uiState.first { it !is RideAnalysisUiState.Loading }

    @Test
    fun `missing rideId argument fails fast`() {
        val ex = runCatching {
            RideAnalysisViewModel(
                FakeRidesRepository(emptyList()),
                FakeMapSettings(weatherEnabled = false),
                SavedStateHandle(),
            )
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

    @Test
    fun `weather is exposed when the feature is enabled`() = runTest {
        val target = ride("r").copy(weather = sampleWeather())
        val vm = viewModel(FakeRidesRepository(listOf(target)), rideId = "r", weatherEnabled = true)

        val state = vm.awaitSettled() as RideAnalysisUiState.Ready
        assertNotNull(state.ride.weather)
    }

    @Test
    fun `weather is hidden when the feature is disabled`() = runTest {
        val target = ride("r").copy(weather = sampleWeather())
        val vm = viewModel(FakeRidesRepository(listOf(target)), rideId = "r", weatherEnabled = false)

        val state = vm.awaitSettled() as RideAnalysisUiState.Ready
        assertNull(state.ride.weather)
    }

    @Test
    fun `analysis loads only the target ride's track, never other rides`() = runTest {
        // Three rides in the history; the screen must analyse just "r" and never
        // deserialise the other two rides' tracks (a full getRide on them).
        val repo = FakeRidesRepository(
            listOf(ride("other-a"), ride("r", count = 300), ride("other-b")),
        )
        val vm = viewModel(repo, rideId = "r")

        val state = vm.awaitSettled()
        assertTrue(state is RideAnalysisUiState.Ready)
        assertEquals("r", (state as RideAnalysisUiState.Ready).ride.id)
        // Only the target's full track was ever loaded.
        assertEquals(listOf("r"), repo.loadedIds.distinct())
    }

    @Test
    fun `personal record uses the track-free summaries of other rides`() = runTest {
        // The target is the longest ride; the two others exist only via their
        // summaries (their tracks are never loaded) yet still gate the PR.
        val target = ride("r", count = 400) // ~2.2 km
        val repo = FakeRidesRepository(listOf(target, ride("a", count = 50), ride("b", count = 80)))
        val vm = viewModel(repo, rideId = "r")

        val state = vm.awaitSettled() as RideAnalysisUiState.Ready
        assertEquals(listOf("r"), repo.loadedIds.distinct())
        assertTrue(
            "target is the longest → distance PR",
            state.achievements.any {
                it.id == de.velospot.core.analysis.AchievementId.PR_DISTANCE && it.isPersonalRecord
            },
        )
    }

    private fun sampleWeather() = WeatherSnapshot(
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
}

/** The track-free timeline view derived from a full ride, for the summaries flow. */
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
    name = name,
    isMock = isMock,
    archivedAt = archivedAt,
    bikeProfileId = bikeProfileId,
)




