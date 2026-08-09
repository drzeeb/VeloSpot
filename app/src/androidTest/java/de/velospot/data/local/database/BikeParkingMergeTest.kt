package de.velospot.data.local.database

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.velospot.data.local.dao.BikeParkingSpaceDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for the first-launch country merge of [BikeParkingDatabase].
 *
 * These run against the real bundled assets (`bike_parking_germany.db`,
 * `bike_parking_france.db`, `bike_parking_luxembourg.db`) and the production Room
 * configuration (via [BikeParkingDatabase.buildDatabase]). They prove that:
 *  - a fresh install ends with the **union** of all three countries,
 *  - re-opening the (already merged) database does **not** duplicate rows,
 *  - a merge whose DB-resident marker was lost (simulating a crash between the
 *    `INSERT OR IGNORE … SELECT` commit and the `application_id` stamp) re-runs
 *    safely and still produces no duplicates,
 *  - after a simulated destructive rebuild (the DB file replaced by the fresh
 *    Germany-only v3 asset, `application_id` back to baseline) the merge **re-runs**
 *    and restores the country union — i.e. the guard is not permanently stuck.
 *
 * The exactly-once guard is now DB-resident (`PRAGMA application_id`), so there is
 * no SharedPreferences flag to clear between runs; deleting the database file is
 * sufficient to reset the marker.
 *
 * NOTE: requires a connected device/emulator — it exercises real SQLite `ATTACH`
 * against the packaged assets, so it cannot run in the headless JVM suite.
 */
@RunWith(AndroidJUnit4::class)
class BikeParkingMergeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun reset() = clearState()

    @After
    fun tearDown() = clearState()

    /** Deletes the test database files. The merge marker lives inside the DB, so
     * removing the file naturally resets it — no external flags to clear. */
    private fun clearState() {
        listOf("", "-wal", "-shm").forEach { suffix ->
            context.getDatabasePath("$TEST_DB$suffix").delete()
        }
    }

    private fun openDb() = BikeParkingDatabase.buildDatabase(context, TEST_DB)

    private fun BikeParkingSpaceDao.countBlocking(): Int = runBlocking { getSpaceCount() }

    @Test
    fun freshInstall_mergesUnionOfAllCountries() {
        val db = openDb()
        try {
            val dao = db.bikeParkingSpaceDao()
            // A representative viewport in each country must return parking spots.
            runBlocking {
                assertTrue("Germany (Berlin) must be seeded", dao.hasSpotsNear(52.52, 13.40))
                assertTrue("France (Paris) must be merged", dao.hasSpotsNear(48.85, 2.35))
                assertTrue("Luxembourg City must be merged", dao.hasSpotsNear(49.61, 6.13))
            }
            assertTrue("merged database must hold many rows", dao.countBlocking() > 0)
        } finally {
            db.close()
        }
    }

    @Test
    fun reopen_afterMerge_doesNotDuplicate() {
        val first = openDb()
        val countAfterFirst = try {
            first.bikeParkingSpaceDao().countBlocking()
        } finally {
            first.close()
        }

        // Second open: application_id is the sentinel, so onOpen skips the merge entirely.
        val second = openDb()
        val countAfterSecond = try {
            second.bikeParkingSpaceDao().countBlocking()
        } finally {
            second.close()
        }

        assertEquals("re-opening must not add rows", countAfterFirst, countAfterSecond)
    }

    @Test
    fun interruptedMerge_rerunsWithoutDuplicates() {
        val first = openDb()
        val countAfterFirst = try {
            first.bikeParkingSpaceDao().countBlocking()
        } finally {
            first.close()
        }

        // Simulate a crash between the atomic INSERT-commit and the application_id
        // stamp: the rows are already present but the marker is back at baseline,
        // so the next open re-runs the INSERT OR IGNORE … SELECT.
        resetApplicationIdToBaseline()

        val second = openDb()
        val countAfterRerun = try {
            second.bikeParkingSpaceDao().countBlocking()
        } finally {
            second.close()
        }

        assertEquals(
            "re-running the merge must be idempotent (INSERT OR IGNORE)",
            countAfterFirst,
            countAfterRerun
        )
    }

    @Test
    fun destructiveRebuild_reMergesCountryUnion() {
        // 1. Fresh install → full union of all countries.
        val first = openDb()
        val countAfterFirst = try {
            val dao = first.bikeParkingSpaceDao()
            runBlocking {
                assertTrue("France must be present before rebuild", dao.hasSpotsNear(48.85, 2.35))
                assertTrue("Luxembourg must be present before rebuild", dao.hasSpotsNear(49.61, 6.13))
            }
            dao.countBlocking()
        } finally {
            first.close()
        }

        // 2. Simulate Room's destructive fallback: the DB file is deleted and
        //    re-copied from the Germany-only v3 asset (application_id at baseline,
        //    no France/Luxembourg rows). Overwriting the file with the asset bytes
        //    reproduces exactly what createFromAsset does on a destructive rebuild.
        overwriteWithGermanyAsset()

        // 3. Re-open: because the marker is DB-resident, the fresh copy is
        //    un-sentineled, so the merge must re-run and restore the union — the
        //    guard is NOT stuck at "already merged".
        val second = openDb()
        try {
            val dao = second.bikeParkingSpaceDao()
            runBlocking {
                assertTrue("Germany must still be present", dao.hasSpotsNear(52.52, 13.40))
                assertTrue("France must be RE-merged after rebuild", dao.hasSpotsNear(48.85, 2.35))
                assertTrue("Luxembourg must be RE-merged after rebuild", dao.hasSpotsNear(49.61, 6.13))
            }
            assertEquals(
                "the re-merged union must match the original full row set",
                countAfterFirst,
                dao.countBlocking()
            )
        } finally {
            second.close()
        }
    }

    /** Resets the DB-resident merge marker while keeping the already-merged rows,
     * simulating a crash between the INSERT commit and the application_id stamp. */
    private fun resetApplicationIdToBaseline() {
        val path = context.getDatabasePath(TEST_DB).path
        android.database.sqlite.SQLiteDatabase
            .openDatabase(path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            .use { raw ->
                raw.execSQL(CountryMergeSql.setApplicationIdSql(CountryMergeSql.APPLICATION_ID_BASELINE))
            }
    }

    /** Replaces the on-device DB file with a fresh copy of the Germany-only asset,
     * reproducing what createFromAsset does on a destructive rebuild. */
    private fun overwriteWithGermanyAsset() {
        clearState()
        val target = context.getDatabasePath(TEST_DB)
        target.parentFile?.mkdirs()
        context.assets.open(GERMANY).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** Convenience: does a tiny bounding box around a point contain any parking spot? */
    private suspend fun BikeParkingSpaceDao.hasSpotsNear(lat: Double, lon: Double): Boolean {
        val d = 0.15 // ~15 km half-window, generous enough for a city
        return getSpacesInBoundingBox(lat - d, lat + d, lon - d, lon + d).isNotEmpty()
    }

    companion object {
        private const val TEST_DB = "bike_parking_merge_test.db"
        private const val GERMANY = "bike_parking_germany.db"
    }
}

