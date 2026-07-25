package de.velospot.core.offline

import de.velospot.testsupport.fakeContextWithPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [OfflineRegionsStore] (JSON-in-SharedPreferences persistence). */
class OfflineRegionsStoreTest {

    private fun store() = OfflineRegionsStore(fakeContextWithPrefs())

    private fun pack(id: String, createdAt: Long = 1_000L) = OfflineRegionPack(
        id = id,
        label = "Region $id",
        latitude = 50.11 + id.hashCode() % 3,
        longitude = 8.68,
        createdAt = createdAt,
    )

    @Test
    fun `empty store lists nothing`() {
        assertEquals(emptyList<OfflineRegionPack>(), store().list())
    }

    @Test
    fun `add persists and round-trips all fields`() {
        val s = store()
        val p = OfflineRegionPack("a", "Frankfurt", 50.11, 8.68, 1_234L)
        s.add(p)

        val loaded = s.list()
        assertEquals(1, loaded.size)
        assertEquals(p, loaded.first())
    }

    @Test
    fun `list is ordered by createdAt ascending`() {
        val s = store()
        s.add(pack("b", createdAt = 3_000L))
        s.add(pack("a", createdAt = 1_000L))
        s.add(pack("c", createdAt = 2_000L))

        assertEquals(listOf("a", "c", "b"), s.list().map { it.id })
    }

    @Test
    fun `add with existing id replaces (idempotent)`() {
        val s = store()
        s.add(pack("a", createdAt = 1_000L))
        s.add(OfflineRegionPack("a", "Renamed", 1.0, 2.0, 1_000L))

        assertEquals(1, s.list().size)
        assertEquals("Renamed", s.list().first().label)
    }

    @Test
    fun `remove deletes only the matching id`() {
        val s = store()
        s.add(pack("a")); s.add(pack("b"))
        s.remove("a")
        assertEquals(listOf("b"), s.list().map { it.id })
    }

    @Test
    fun `clear empties the store`() {
        val s = store()
        s.add(pack("a")); s.add(pack("b"))
        s.clear()
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `a fresh store instance reads what a previous one wrote (persisted)`() {
        val ctx = fakeContextWithPrefs()
        OfflineRegionsStore(ctx).add(pack("a"))
        // A new instance over the same context/prefs still sees it.
        assertEquals(listOf("a"), OfflineRegionsStore(ctx).list().map { it.id })
    }
}

