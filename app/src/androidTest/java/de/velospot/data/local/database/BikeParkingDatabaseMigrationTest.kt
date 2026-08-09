package de.velospot.data.local.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration/DB test for [BikeParkingDatabase], focused on
 * performance finding #7 (the viewport bounding-box query and its spatial index)
 * and the first-launch country merge.
 *
 * Under the current design the two concerns are handled on two different paths:
 *  - The v3 → v4 Room migration is a *data-only* bump that ONLY ensures the
 *    composite viewport index `idx_parking_lat_lon` (self-healing
 *    `CREATE INDEX IF NOT EXISTS`); it does NOT merge any country data.
 *  - The bundled France/Luxembourg datasets are merged once on the
 *    non-transactional `onOpen` path via
 *    [BikeParkingDatabase.mergeExtraCountriesIfNeeded] (a single fast
 *    `ATTACH … INSERT OR IGNORE … SELECT` per country), which is where the index
 *    self-heal also runs so the freshly-inserted rows are covered.
 *
 * So the first test exercises the migration (index + schema), and the second
 * exercises the real merge path directly.
 *
 * NOTE: This is an *instrumented* test and needs a connected device/emulator
 * (it reads the bundled `assets/bike_parking_*.db` files through the app context
 * and drives a real SQLite database). It will not run in a headless JVM.
 */
@RunWith(AndroidJUnit4::class)
class BikeParkingDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BikeParkingDatabase::class.java
    )

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migrate3To4_createsViewportIndex_andValidatesSchema() {
        // Create the v3 database from the checked-in 3.json baseline (no index).
        helper.createDatabase(TEST_DB, 3).use { db ->
            // Seed a couple of Germany rows so the bbox query has data to filter.
            db.execSQL(insertSpace("de-1", 49.75, 6.64)) // Trier, inside the test box
            db.execSQL(insertSpace("de-2", 52.52, 13.40)) // Berlin, outside the test box
        }

        // Run the real production v3 → v4 migration (data-only: it only ensures the
        // self-healing viewport index) and validate against the exported 4.json.
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            BikeParkingDatabase.migration3To4ForTest()
        )

        // 1) The composite viewport index must exist after the migration.
        assertTrue(
            "viewport index $INDEX_NAME must exist after v3→v4 migration",
            indexExists(db)
        )

        // 2) The bounding-box query over the two SEEDED rows returns only the ones
        //    inside the box (no reliance on merged France/Luxembourg rows here).
        val ids = mutableListOf<String>()
        db.query(
            "SELECT id FROM bike_parking_spaces " +
                "WHERE latitude BETWEEN 49.70 AND 49.80 " +
                "AND longitude BETWEEN 6.60 AND 6.70"
        ).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        assertTrue("Trier seed must be inside the box", ids.contains("de-1"))
        assertFalse("Berlin seed must be outside the box", ids.contains("de-2"))

        // 3) The planner actually uses the index for the bbox query.
        var usesIndex = false
        db.query(
            "EXPLAIN QUERY PLAN SELECT * FROM bike_parking_spaces " +
                "WHERE latitude BETWEEN 49.70 AND 49.80 " +
                "AND longitude BETWEEN 6.60 AND 6.70"
        ).use { c ->
            val detailCol = c.getColumnIndex("detail").let { if (it >= 0) it else c.columnCount - 1 }
            while (c.moveToNext()) {
                if (c.getString(detailCol).contains(INDEX_NAME)) usesIndex = true
            }
        }
        assertTrue("bbox query must use $INDEX_NAME", usesIndex)

        db.close()
    }

    @Test
    fun mergeExtraCountriesIfNeeded_mergesAssets_isIdempotent_andSelfHealsIndex() {
        // A v3 database (no index) with a single seeded Germany row, exercising the
        // real onOpen merge path directly (independent of any migration).
        val db = helper.createDatabase(TEST_DB, 3)
        db.execSQL(insertSpace("de-1", 49.75, 6.64))

        val seededCount = countRows(db)
        assertEquals("exactly one seeded Germany row to start", 1, seededCount)

        // First merge: imports the bundled France/Luxembourg assets.
        BikeParkingDatabase.mergeExtraCountriesIfNeeded(targetContext, db)

        // (a) The merged table must hold far more than the single Germany seed.
        val afterMergeCount = countRows(db)
        assertTrue(
            "country merge must have imported the extra country assets",
            afterMergeCount > seededCount
        )

        // (b) The seeded row must survive the merge unchanged.
        db.query("SELECT latitude FROM bike_parking_spaces WHERE id = 'de-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(49.75, c.getDouble(0), 0.0001)
        }

        // (c) The DB-resident "fully merged" sentinel must be set afterwards.
        assertEquals(
            "application_id sentinel must be stamped once all countries merged",
            CountryMergeSql.APPLICATION_ID_SENTINEL,
            readApplicationId(db)
        )

        // (d) The viewport index self-heal must have created the index on the merge
        //     path even though the db started from the index-less v3 baseline.
        assertTrue(
            "viewport index $INDEX_NAME must exist after the merge (self-heal)",
            indexExists(db)
        )

        // A second merge is a no-op (exactly-once): the sentinel short-circuits it,
        // so the row count is unchanged.
        BikeParkingDatabase.mergeExtraCountriesIfNeeded(targetContext, db)
        assertEquals(
            "second merge must be idempotent (exactly-once)",
            afterMergeCount,
            countRows(db)
        )

        db.close()
    }

    private fun countRows(db: SupportSQLiteDatabase): Int =
        db.query("SELECT COUNT(*) FROM bike_parking_spaces").use { c ->
            assertTrue(c.moveToFirst())
            c.getInt(0)
        }

    private fun readApplicationId(db: SupportSQLiteDatabase): Int =
        db.query(CountryMergeSql.READ_APPLICATION_ID_SQL).use { c ->
            assertTrue(c.moveToFirst())
            c.getInt(0)
        }

    private fun indexExists(db: SupportSQLiteDatabase): Boolean {
        db.query("PRAGMA index_list(`bike_parking_spaces`)").use { c ->
            val nameCol = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                if (c.getString(nameCol) == INDEX_NAME) return true
            }
        }
        return false
    }

    private fun insertSpace(id: String, lat: Double, lon: Double): String =
        "INSERT INTO bike_parking_spaces " +
            "(id, name, latitude, longitude, address, capacity, isCovered, imageUrl, " +
            "operator, type, sourceLayer, lastUpdated) VALUES (" +
            "'$id', NULL, $lat, $lon, NULL, NULL, NULL, NULL, NULL, " +
            "'STANDS', 'bike_parking', 0)"

    companion object {
        private const val TEST_DB = "migration-test-bike-parking.db"
        private const val INDEX_NAME = "idx_parking_lat_lon"
    }
}

