package de.velospot.data.repository

import de.velospot.data.local.dao.FavoriteSpaceDao
import de.velospot.data.local.entity.FavoriteSpaceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FavoritesRepositoryImpl] with an in-memory fake [FavoriteSpaceDao].
 * Covers the reactive id mapping (newest first) and the add/remove/toggle behaviour,
 * including the atomic `toggleFavorite` transaction inherited from the DAO.
 */
class FavoritesRepositoryImplTest {

    private class FakeFavoriteSpaceDao : FavoriteSpaceDao {
        val store = MutableStateFlow<List<FavoriteSpaceEntity>>(emptyList())
        override fun getFavoritesFlow(): Flow<List<FavoriteSpaceEntity>> =
            store.map { list -> list.sortedByDescending { it.addedAt } }
        override suspend fun isFavorite(parkingSpaceId: String): Boolean =
            store.value.any { it.parkingSpaceId == parkingSpaceId }
        override suspend fun addFavorite(favorite: FavoriteSpaceEntity) {
            store.value = store.value.filterNot { it.parkingSpaceId == favorite.parkingSpaceId } + favorite
        }
        override suspend fun removeFavorite(parkingSpaceId: String) {
            store.value = store.value.filterNot { it.parkingSpaceId == parkingSpaceId }
        }
        override suspend fun getAll(): List<FavoriteSpaceEntity> = store.value
        override suspend fun deleteAll() { store.value = emptyList() }
    }

    private fun repo(dao: FavoriteSpaceDao = FakeFavoriteSpaceDao()) = FavoritesRepositoryImpl(dao)

    @Test
    fun `addFavorite is reflected by isFavorite and the flow`() = runTest {
        val repo = repo()
        repo.addFavorite("space-1")

        assertTrue(repo.isFavorite("space-1"))
        assertEquals(listOf("space-1"), repo.getFavoritesFlow().first())
    }

    @Test
    fun `favorites flow exposes ids newest first`() = runTest {
        val dao = FakeFavoriteSpaceDao()
        dao.store.value = listOf(
            FavoriteSpaceEntity(parkingSpaceId = "old", addedAt = 1_000L),
            FavoriteSpaceEntity(parkingSpaceId = "new", addedAt = 5_000L),
        )
        assertEquals(listOf("new", "old"), repo(dao).getFavoritesFlow().first())
    }

    @Test
    fun `isFavorite is false for an unknown id`() = runTest {
        assertFalse(repo().isFavorite("nope"))
    }

    @Test
    fun `removeFavorite deletes the entry`() = runTest {
        val repo = repo()
        repo.addFavorite("space-1")
        repo.removeFavorite("space-1")

        assertFalse(repo.isFavorite("space-1"))
        assertTrue(repo.getFavoritesFlow().first().isEmpty())
    }

    @Test
    fun `toggleFavorite adds when absent and removes when present`() = runTest {
        val repo = repo()

        repo.toggleFavorite("space-1")
        assertTrue(repo.isFavorite("space-1"))

        repo.toggleFavorite("space-1")
        assertFalse(repo.isFavorite("space-1"))
    }
}

