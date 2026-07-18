package de.velospot.data.repository

import de.velospot.data.local.dao.SavedPlaceDao
import de.velospot.data.local.entity.SavedPlaceEntity
import de.velospot.domain.model.SavedPlace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SavedPlacesRepositoryImpl] using an in-memory fake DAO.
 * Covers the entity ⇄ domain mapping and the DAO delegation.
 */
class SavedPlacesRepositoryImplTest {

    /** Minimal in-memory [SavedPlaceDao] fake backed by a reactive flow. */
    private class FakeSavedPlaceDao : SavedPlaceDao {
        private val store = MutableStateFlow<List<SavedPlaceEntity>>(emptyList())
        var deletedIds = mutableListOf<String>()

        override fun getAllFlow(): Flow<List<SavedPlaceEntity>> = store

        override suspend fun upsert(place: SavedPlaceEntity) {
            store.value = store.value.filterNot { it.id == place.id } + place
        }

        override suspend fun delete(id: String) {
            deletedIds += id
            store.value = store.value.filterNot { it.id == id }
        }
    }

    private fun place(id: String = "p1") = SavedPlace(
        id = id,
        name = "Home",
        latitude = 49.75,
        longitude = 6.64,
        address = "Hauptstr. 1",
        addedAt = 1_000L,
    )

    @Test
    fun `savePlace persists and the flow maps entities back to domain`() = runTest {
        val dao = FakeSavedPlaceDao()
        val repo = SavedPlacesRepositoryImpl(dao)

        repo.savePlace(place())

        val result = repo.getSavedPlacesFlow().first()
        assertEquals(1, result.size)
        val saved = result.first()
        assertEquals("p1", saved.id)
        assertEquals("Home", saved.name)
        assertEquals(49.75, saved.latitude, 0.0)
        assertEquals(6.64, saved.longitude, 0.0)
        assertEquals("Hauptstr. 1", saved.address)
        assertEquals(1_000L, saved.addedAt)
    }

    @Test
    fun `removePlace delegates the id to the dao`() = runTest {
        val dao = FakeSavedPlaceDao()
        val repo = SavedPlacesRepositoryImpl(dao)
        repo.savePlace(place("p1"))
        repo.savePlace(place("p2"))

        repo.removePlace("p1")

        assertTrue(dao.deletedIds.contains("p1"))
        val remaining = repo.getSavedPlacesFlow().first()
        assertEquals(listOf("p2"), remaining.map { it.id })
    }
}

