package de.velospot.feature.bikeprofiles.presentation

import de.velospot.domain.model.BikeProfile
import de.velospot.domain.model.BikeType
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.domain.repository.BikeProfilesRepository
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BikeProfilesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class FakeBikeProfilesRepository(
        bikes: List<BikeProfile> = emptyList(),
        activeId: String? = null,
    ) : BikeProfilesRepository {
        val bikes = MutableStateFlow(bikes)
        val active = MutableStateFlow(activeId)
        override fun bikeProfilesFlow(): Flow<List<BikeProfile>> = bikes
        override val activeBikeProfileId: Flow<String?> = active
        override suspend fun upsert(profile: BikeProfile) {
            val base = if (profile.isDefault) this.bikes.value.map { it.copy(isDefault = false) } else this.bikes.value
            this.bikes.value = base.filterNot { it.id == profile.id } + profile
        }
        override suspend fun delete(id: String) {
            bikes.value = bikes.value.filterNot { it.id == id }
            if (active.value == id) active.value = null
        }
        override suspend fun setDefault(id: String) {
            bikes.value = bikes.value.map { it.copy(isDefault = it.id == id) }
        }
        override suspend fun setActive(id: String?) { active.value = id }
        override suspend fun resolveActiveProfileId(): String? =
            active.value ?: bikes.value.firstOrNull { it.isDefault }?.id
        override suspend fun evaluateServiceDue(bikeId: String) = null
    }

    private class FakeRidesRepository(
        summaries: List<RecordedRideSummary> = emptyList(),
    ) : RecordedRidesRepository {
        val flow = MutableStateFlow(summaries)
        override fun getRideSummariesFlow(): Flow<List<RecordedRideSummary>> = flow
        override fun getRidesWithTracksFlow(): Flow<List<RecordedRide>> = MutableStateFlow(emptyList())
        override suspend fun getRide(id: String): RecordedRide? = null
        override suspend fun getRides(ids: List<String>): List<RecordedRide> = emptyList()
        override suspend fun saveRide(ride: RecordedRide) = Unit
        override suspend fun updateRideName(id: String, name: String?) = Unit
        override suspend fun setRideArchived(id: String, archived: Boolean) = Unit
        override suspend fun removeRide(id: String) = Unit
        override suspend fun clearAll() = Unit
    }

    private fun bike(id: String, isDefault: Boolean = false, interval: Int? = null) = BikeProfile(
        id = id, name = "Bike $id", type = BikeType.ROAD, isDefault = isDefault,
        createdAt = 0L, serviceIntervalKm = interval,
    )

    private fun summary(
        id: String,
        bikeId: String?,
        distance: Double = 0.0,
        moving: Long = 0L,
        gain: Double = 0.0,
        started: Long = 0L,
        mock: Boolean = false,
    ) = RecordedRideSummary(
        id = id, startedAt = started, endedAt = started + 1, distanceMeters = distance,
        elapsedSeconds = moving, movingSeconds = moving, avgSpeedMps = 0.0, maxSpeedMps = 0.0,
        elevationGainMeters = gain, elevationLossMeters = 0.0, isMock = mock, bikeProfileId = bikeId,
    )

    /** Collects [vm] uiState in the background so the WhileSubscribed flow starts. */
    private fun TestScope.startVm(vm: BikeProfilesViewModel) {
        backgroundScope.launch { vm.uiState.collect {} }
        dispatcher.scheduler.advanceUntilIdle()
    }

    // ── uiState ──────────────────────────────────────────────────────────────

    @Test
    fun `uiState groups ride stats per bike, excludes mock rides and counts unassigned`() = runTest {
        val bikesRepo = FakeBikeProfilesRepository(
            bikes = listOf(bike("b1", isDefault = true), bike("b2")),
            activeId = "b1",
        )
        val ridesRepo = FakeRidesRepository(
            listOf(
                summary("s1", "b1", distance = 1_000.0, moving = 300, gain = 10.0, started = 100),
                summary("s2", "b1", distance = 2_000.0, moving = 400, gain = 20.0, started = 200),
                summary("s3", null, distance = 500.0),
                summary("s4", "b1", mock = true), // excluded
            )
        )
        val vm = BikeProfilesViewModel(bikesRepo, ridesRepo)
        startVm(vm)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.unassignedRideCount)

        val b1 = state.bikes.first { it.profile.id == "b1" }
        assertTrue(b1.isActive)
        assertEquals(2, b1.stats.rideCount)
        assertEquals(3_000.0, b1.stats.totalDistanceMeters, 0.0)
        assertEquals(700L, b1.stats.totalMovingSeconds)
        assertEquals(30.0, b1.stats.totalElevationGainMeters, 0.0)
        assertEquals(200L, b1.stats.lastRideAt)

        val b2 = state.bikes.first { it.profile.id == "b2" }
        assertFalse(b2.isActive)
        assertEquals(0, b2.stats.rideCount)
    }

    @Test
    fun `uiState marks the default bike active when there is no explicit selection`() = runTest {
        val bikesRepo = FakeBikeProfilesRepository(
            bikes = listOf(bike("b1"), bike("b2", isDefault = true)),
            activeId = null,
        )
        val vm = BikeProfilesViewModel(bikesRepo, FakeRidesRepository())
        startVm(vm)

        assertTrue(vm.uiState.value.bikes.first { it.profile.id == "b2" }.isActive)
        assertFalse(vm.uiState.value.bikes.first { it.profile.id == "b1" }.isActive)
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    @Test
    fun `addBike makes the very first bike the default automatically`() = runTest {
        val repo = FakeBikeProfilesRepository()
        val vm = BikeProfilesViewModel(repo, FakeRidesRepository())

        vm.addBike(BikeDraft(name = "First", isDefault = false))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repo.bikes.value.size)
        assertTrue(repo.bikes.value.single().isDefault)
    }

    @Test
    fun `addBike does not force default when a bike already exists`() = runTest {
        val repo = FakeBikeProfilesRepository(bikes = listOf(bike("b1", isDefault = true)))
        val vm = BikeProfilesViewModel(repo, FakeRidesRepository())
        startVm(vm) // uiState must reflect the existing bike (isEmpty == false)

        vm.addBike(BikeDraft(name = "Second", isDefault = false))
        dispatcher.scheduler.advanceUntilIdle()

        val second = repo.bikes.value.first { it.name == "Second" }
        assertFalse(second.isDefault)
    }

    @Test
    fun `updateBike keeps service progress when the interval is unchanged`() = runTest {
        val existing = bike("b1", interval = 500).copy(lastServiceNotifiedKm = 1_000)
        val repo = FakeBikeProfilesRepository(bikes = listOf(existing))
        val vm = BikeProfilesViewModel(repo, FakeRidesRepository())

        vm.updateBike("b1", createdAt = 0L, draft = BikeDraft(name = "b1", serviceIntervalKm = "500"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1_000, repo.bikes.value.single().lastServiceNotifiedKm)
    }

    @Test
    fun `updateBike resets service progress when the interval changes`() = runTest {
        val existing = bike("b1", interval = 500).copy(lastServiceNotifiedKm = 1_000)
        val repo = FakeBikeProfilesRepository(bikes = listOf(existing))
        val vm = BikeProfilesViewModel(repo, FakeRidesRepository())

        vm.updateBike("b1", createdAt = 0L, draft = BikeDraft(name = "b1", serviceIntervalKm = "800"))
        dispatcher.scheduler.advanceUntilIdle()

        val updated = repo.bikes.value.single()
        assertEquals(800, updated.serviceIntervalKm)
        assertEquals(0, updated.lastServiceNotifiedKm)
    }

    @Test
    fun `deleteBike removes the bike`() = runTest {
        val repo = FakeBikeProfilesRepository(bikes = listOf(bike("b1"), bike("b2")))
        val vm = BikeProfilesViewModel(repo, FakeRidesRepository())

        vm.deleteBike("b1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("b2"), repo.bikes.value.map { it.id })
    }

    @Test
    fun `setDefault flips the default flag to the chosen bike`() = runTest {
        val repo = FakeBikeProfilesRepository(bikes = listOf(bike("b1", isDefault = true), bike("b2")))
        val vm = BikeProfilesViewModel(repo, FakeRidesRepository())

        vm.setDefault("b2")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(repo.bikes.value.first { it.id == "b1" }.isDefault)
        assertTrue(repo.bikes.value.first { it.id == "b2" }.isDefault)
    }

    @Test
    fun `setActive persists the chosen active bike`() = runTest {
        val repo = FakeBikeProfilesRepository(bikes = listOf(bike("b1"), bike("b2")))
        val vm = BikeProfilesViewModel(repo, FakeRidesRepository())

        vm.setActive("b2")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("b2", repo.active.value)
    }

    // ── Pure logic: BikeProfileRow ─────────────────────────────────────────────

    @Test
    fun `BikeProfileRow computes the next service milestone and remaining km`() {
        val row = BikeProfileRow(
            profile = bike("b1", interval = 500),
            stats = BikeProfileStats(totalDistanceMeters = 1_200_000.0), // 1200 km
            isActive = false,
        )
        assertEquals(1_500, row.nextServiceAtKm)
        assertEquals(300, row.kmUntilService)
    }

    @Test
    fun `BikeProfileRow has no service milestone when reminders are off`() {
        val off = BikeProfileRow(bike("b1", interval = null), BikeProfileStats(), isActive = false)
        assertNull(off.nextServiceAtKm)
        assertNull(off.kmUntilService)

        val zero = BikeProfileRow(bike("b1", interval = 0), BikeProfileStats(), isActive = false)
        assertNull(zero.nextServiceAtKm)
    }

    // ── Pure logic: BikeDraft ──────────────────────────────────────────────────

    @Test
    fun `BikeDraft isValid requires a non-blank name`() {
        assertFalse(BikeDraft(name = "  ").isValid)
        assertTrue(BikeDraft(name = "Racer").isValid)
    }

    @Test
    fun `BikeDraft toProfile trims, parses and nulls blanks`() {
        val profile = BikeDraft(
            name = "  Racer  ",
            brand = "",
            model = "Ultimate",
            weightKg = "12,5",
            modelYear = "abc2020",
            notes = "",
            serviceIntervalKm = "0",
        ).toProfile(id = "b1", createdAt = 42L)

        assertEquals("Racer", profile.name)
        assertNull(profile.brand)
        assertEquals("Ultimate", profile.model)
        assertEquals(12.5, profile.weightKg!!, 0.0)
        assertEquals(2020, profile.modelYear)
        assertNull(profile.notes)
        assertNull(profile.serviceIntervalKm) // 0 disables reminders
        assertEquals(42L, profile.createdAt)
    }

    @Test
    fun `BikeDraft from formats a whole-number weight without decimals`() {
        val whole = BikeDraft.from(bike("b1").copy(weightKg = 12.0, serviceIntervalKm = 500))
        assertEquals("12", whole.weightKg)
        assertEquals("500", whole.serviceIntervalKm)

        val fractional = BikeDraft.from(bike("b1").copy(weightKg = 12.5))
        assertEquals("12.5", fractional.weightKg)
        assertEquals("", fractional.serviceIntervalKm)
    }
}

