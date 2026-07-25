package de.velospot.data.repository

import de.velospot.domain.model.ParkedBike
import de.velospot.testsupport.fakeContextWithPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [ParkedBikeRepositoryImpl] over an in-memory SharedPreferences. */
class ParkedBikeRepositoryImplTest {

    private fun bike() = ParkedBike(
        latitude = 49.75,
        longitude = 6.64,
        parkedAt = 1_700_000_000_000L,
        note = "Behind the station",
        address = "Bahnhofstr. 1",
    )

    @Test
    fun `no bike parked initially`() = runTest {
        val repo = ParkedBikeRepositoryImpl(fakeContextWithPrefs())
        assertNull(repo.getParkedBikeFlow().first())
    }

    @Test
    fun `park then flow emits the bike`() = runTest {
        val repo = ParkedBikeRepositoryImpl(fakeContextWithPrefs())
        repo.park(bike())
        assertEquals(bike(), repo.getParkedBikeFlow().first())
    }

    @Test
    fun `parked bike survives across repository instances (persisted)`() = runTest {
        val ctx = fakeContextWithPrefs()
        ParkedBikeRepositoryImpl(ctx).park(bike())
        // A fresh instance reads the persisted record from prefs.
        assertEquals(bike(), ParkedBikeRepositoryImpl(ctx).getParkedBikeFlow().first())
    }

    @Test
    fun `clear removes the parked bike`() = runTest {
        val repo = ParkedBikeRepositoryImpl(fakeContextWithPrefs())
        repo.park(bike())
        repo.clear()
        assertNull(repo.getParkedBikeFlow().first())
    }

    @Test
    fun `null note and address round-trip as null`() = runTest {
        val repo = ParkedBikeRepositoryImpl(fakeContextWithPrefs())
        repo.park(ParkedBike(latitude = 1.0, longitude = 2.0, parkedAt = 5L, note = null, address = null))
        val loaded = repo.getParkedBikeFlow().first()!!
        assertNull(loaded.note)
        assertNull(loaded.address)
        assertEquals(1.0, loaded.latitude, 0.0)
        assertEquals(2.0, loaded.longitude, 0.0)
    }
}

