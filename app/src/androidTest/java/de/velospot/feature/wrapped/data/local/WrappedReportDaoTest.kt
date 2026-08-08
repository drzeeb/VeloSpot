package de.velospot.feature.wrapped.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented CRUD coverage for [WrappedReportDao] against a real in-memory
 * [WrappedDatabase]. There is no Robolectric on the JVM unit-test classpath, so
 * the Room-backed DAO is exercised here (mirroring how the app's other DAOs are
 * tested against a device/emulator).
 */
@RunWith(AndroidJUnit4::class)
class WrappedReportDaoTest {

    private lateinit var db: WrappedDatabase
    private lateinit var dao: WrappedReportDao

    private fun entity(
        id: String,
        type: String = "WEEK",
        periodStart: Long = 0,
        periodEnd: Long = 100,
        generatedAt: Long = 0,
        snapshotJson: String = "{}"
    ) = WrappedReportEntity(id, type, periodStart, periodEnd, generatedAt, snapshotJson)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, WrappedDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.wrappedReportDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertAndGetById() = runBlocking {
        dao.upsert(entity("a", snapshotJson = """{"k":1}"""))
        assertEquals("""{"k":1}""", dao.getById("a")?.snapshotJson)
        assertNull(dao.getById("missing"))
        assertEquals(1, dao.count())
    }

    @Test
    fun upsertReplacesSameId() = runBlocking {
        dao.upsert(entity("a", generatedAt = 1))
        dao.upsert(entity("a", generatedAt = 2))
        assertEquals(1, dao.count())
        assertEquals(2L, dao.getById("a")?.generatedAt)
    }

    @Test
    fun getAllFlowOrdersByGeneratedAtDesc() = runBlocking {
        dao.upsert(entity("old", generatedAt = 10))
        dao.upsert(entity("new", generatedAt = 30))
        dao.upsert(entity("mid", generatedAt = 20))

        val ids = dao.getAllFlow().first().map { it.id }
        assertEquals(listOf("new", "mid", "old"), ids)
    }

    @Test
    fun getByPeriodMatchesExactBucket() = runBlocking {
        dao.upsert(entity("w", type = "WEEK", periodStart = 100, periodEnd = 200))

        assertNotNull(dao.getByPeriod("WEEK", 100, 200))
        assertNull(dao.getByPeriod("WEEK", 100, 999))
        assertNull(dao.getByPeriod("MONTH", 100, 200))
    }

    @Test
    fun deleteAndDeleteAll() = runBlocking {
        dao.upsert(entity("a"))
        dao.upsert(entity("b"))
        dao.delete("a")
        assertEquals(1, dao.count())
        assertNull(dao.getById("a"))

        dao.deleteAll()
        assertEquals(0, dao.count())
    }
}

