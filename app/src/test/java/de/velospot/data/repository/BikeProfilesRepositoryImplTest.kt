package de.velospot.data.repository

import android.content.Context
import de.velospot.data.local.dao.BikeProfileDao
import de.velospot.data.local.entity.BikeProfileEntity
import de.velospot.domain.model.BikeProfile
import de.velospot.domain.model.BikeType
import de.velospot.domain.repository.RecordedRidesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * Unit tests for [BikeProfilesRepositoryImpl] with an in-memory fake [BikeProfileDao],
 * a mocked [RecordedRidesRepository] and a **real** Jetpack DataStore backed by a
 * temporary directory.
 *
 * The `by preferencesDataStore(...)` delegate is a process-wide singleton keyed to the
 * top-level property, so a single class-scoped [TemporaryFolder] backs it for the whole
 * class; each test resets the tiny "active bike" selection in [resetActive].
 */
class BikeProfilesRepositoryImplTest {

    companion object {
        @get:ClassRule
        @JvmStatic
        val tempDir = TemporaryFolder()
    }

    /** In-memory [BikeProfileDao]; the default `setDefault` transaction is inherited. */
    private class FakeBikeProfileDao : BikeProfileDao {
        val store = MutableStateFlow<List<BikeProfileEntity>>(emptyList())
        override fun getAllFlow(): Flow<List<BikeProfileEntity>> =
            store.map { it.sortedBy { e -> e.createdAt } }
        override suspend fun getById(id: String): BikeProfileEntity? = store.value.firstOrNull { it.id == id }
        override suspend fun getDefaultId(): String? = store.value.firstOrNull { it.isDefault }?.id
        override suspend fun upsert(profile: BikeProfileEntity) {
            store.value = store.value.filterNot { it.id == profile.id } + profile
        }
        override suspend fun delete(id: String) { store.value = store.value.filterNot { it.id == id } }
        override suspend fun clearDefaultFlags() {
            store.value = store.value.map { it.copy(isDefault = false) }
        }
        override suspend fun markDefault(id: String) {
            store.value = store.value.map { if (it.id == id) it.copy(isDefault = true) else it }
        }
        override suspend fun updateServiceNotified(id: String, milestoneKm: Int) {
            store.value = store.value.map { if (it.id == id) it.copy(lastServiceNotifiedKm = milestoneKm) else it }
        }
        override suspend fun getAll(): List<BikeProfileEntity> = store.value
        override suspend fun deleteAll() { store.value = emptyList() }
    }

    private fun newContext(): Context = mock<Context>().also {
        whenever(it.applicationContext).thenReturn(it)
        whenever(it.filesDir).thenReturn(tempDir.root)
    }

    private fun profile(
        id: String,
        name: String = "Bike $id",
        isDefault: Boolean = false,
        createdAt: Long = 1_000L,
        serviceIntervalKm: Int? = null,
        lastServiceNotifiedKm: Int = 0,
    ) = BikeProfile(
        id = id,
        name = name,
        type = BikeType.ROAD,
        isDefault = isDefault,
        createdAt = createdAt,
        serviceIntervalKm = serviceIntervalKm,
        lastServiceNotifiedKm = lastServiceNotifiedKm,
    )

    private fun repo(
        dao: BikeProfileDao = FakeBikeProfileDao(),
        rides: RecordedRidesRepository = mock(),
        context: Context = newContext(),
        photos: de.velospot.data.photo.BikePhotoStore = NoopBikePhotoStore(),
    ) = BikeProfilesRepositoryImpl(context, dao, rides, photos)

    /** No-op photo store: these repository tests don't exercise photo storage. */
    private class NoopBikePhotoStore : de.velospot.data.photo.BikePhotoStore {
        override suspend fun savePhoto(bikeId: String, sourceUri: android.net.Uri): String? = null
        override suspend fun deletePhoto(bikeId: String) = Unit
    }

    @Before
    fun resetActive() = runBlocking { repo().setActive(null) }

    // ── Room-backed CRUD & mapping ─────────────────────────────────────────────

    @Test
    fun `upsert trims fields and drops blanks and zero interval`() = runTest {
        val dao = FakeBikeProfileDao()
        val sut = repo(dao = dao)
        sut.upsert(
            profile("b1").copy(
                name = "  Racer  ",
                brand = "   ",
                model = "Ultimate",
                serviceIntervalKm = 0,
            )
        )
        val loaded = sut.bikeProfilesFlow().first().single()
        assertEquals("Racer", loaded.name)
        assertNull(loaded.brand)
        assertEquals("Ultimate", loaded.model)
        assertNull(loaded.serviceIntervalKm)
        assertEquals(BikeType.ROAD, loaded.type)
    }

    @Test
    fun `upsert with isDefault enforces a single default`() = runTest {
        val dao = FakeBikeProfileDao()
        val sut = repo(dao = dao)
        sut.upsert(profile("a", isDefault = true, createdAt = 1L))
        sut.upsert(profile("b", isDefault = true, createdAt = 2L))

        val defaults = sut.bikeProfilesFlow().first().filter { it.isDefault }.map { it.id }
        assertEquals(listOf("b"), defaults)
    }

    @Test
    fun `setDefault marks exactly one bike default`() = runTest {
        val dao = FakeBikeProfileDao()
        val sut = repo(dao = dao)
        sut.upsert(profile("a", createdAt = 1L))
        sut.upsert(profile("b", createdAt = 2L))

        sut.setDefault("b")

        assertEquals("b", dao.getDefaultId())
    }

    // ── Active selection (DataStore) ────────────────────────────────────────────

    @Test
    fun `setActive is reflected by activeBikeProfileId and can be cleared`() = runTest {
        val sut = repo()
        sut.setActive("b1")
        assertEquals("b1", sut.activeBikeProfileId.first())
        sut.setActive(null)
        assertNull(sut.activeBikeProfileId.first())
    }

    @Test
    fun `resolveActiveProfileId returns active then falls back to the default`() = runTest {
        val dao = FakeBikeProfileDao()
        val sut = repo(dao = dao)
        dao.upsert(BikeProfileEntity(id = "d1", name = "Default", type = "ROAD", isDefault = true, createdAt = 1L))

        assertEquals("d1", sut.resolveActiveProfileId())

        sut.setActive("a1")
        assertEquals("a1", sut.resolveActiveProfileId())
    }

    // ── Deletion ────────────────────────────────────────────────────────────────

    @Test
    fun `delete detaches rides, removes the bike and clears a matching active`() = runTest {
        val dao = FakeBikeProfileDao()
        val rides = mock<RecordedRidesRepository>()
        val sut = repo(dao = dao, rides = rides)
        sut.upsert(profile("b1"))
        sut.setActive("b1")

        sut.delete("b1")

        verifyBlocking(rides) { clearBikeProfileFromRides("b1") }
        assertTrue(sut.bikeProfilesFlow().first().isEmpty())
        assertNull(sut.activeBikeProfileId.first())
    }

    @Test
    fun `delete keeps a non-matching active selection`() = runTest {
        val dao = FakeBikeProfileDao()
        val sut = repo(dao = dao)
        sut.upsert(profile("b1"))
        sut.setActive("keep")

        sut.delete("b1")

        assertEquals("keep", sut.activeBikeProfileId.first())
    }

    // ── Service reminders ─────────────────────────────────────────────────────

    @Test
    fun `evaluateServiceDue returns the crossed milestone and records it`() = runTest {
        val dao = FakeBikeProfileDao()
        val rides = mock<RecordedRidesRepository> {
            on { totalDistanceForBike("b1") } doReturn 1_200_000.0 // 1200 km
        }
        val sut = repo(dao = dao, rides = rides)
        sut.upsert(profile("b1", name = "Tourer", serviceIntervalKm = 500))

        val reminder = sut.evaluateServiceDue("b1")
        assertEquals(1000, reminder?.milestoneKm)
        assertEquals(1200, reminder?.totalDistanceKm)
        assertEquals("Tourer", reminder?.bikeName)
        assertEquals(1000, dao.getById("b1")?.lastServiceNotifiedKm)
    }

    @Test
    fun `evaluateServiceDue is null when reminders are off`() = runTest {
        val dao = FakeBikeProfileDao()
        val sut = repo(dao = dao)
        sut.upsert(profile("b1", serviceIntervalKm = null))
        assertNull(sut.evaluateServiceDue("b1"))
    }

    @Test
    fun `evaluateServiceDue is null when the milestone was already notified`() = runTest {
        val dao = FakeBikeProfileDao()
        val rides = mock<RecordedRidesRepository> {
            on { totalDistanceForBike("b1") } doReturn 1_200_000.0
        }
        val sut = repo(dao = dao, rides = rides)
        sut.upsert(profile("b1", serviceIntervalKm = 500, lastServiceNotifiedKm = 1000))
        assertNull(sut.evaluateServiceDue("b1"))
    }

    @Test
    fun `evaluateServiceDue is null for an unknown bike`() = runTest {
        assertNull(repo().evaluateServiceDue("missing"))
    }
}


