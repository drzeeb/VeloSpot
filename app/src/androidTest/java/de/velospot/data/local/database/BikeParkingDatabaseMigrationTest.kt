package de.velospot.data.local.database

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
 * performance finding #7 (the viewport bounding-box query and its spatial index).
 *
 * It proves three things about the real v3 → v4 country-merge migration:
 *  1. The composite viewport index `idx_parking_lat_lon` exists **after** the
 *     migration — even when the starting v3 database (created from the checked-in
 *     `3.json` baseline, which has no index) shipped without it. This guards the
 *     self-healing `CREATE INDEX IF NOT EXISTS` added to the migration and the
 *     fact that the index physically backs the merged rows.
 *  2. The resulting schema validates against the exported `4.json` baseline, so
 *     the index change did not drift the managed Room schema / identity hash.
 *  3. The bounding-box query returns exactly the spots inside the box and the
 *     SQLite query planner uses `idx_parking_lat_lon` for it (`EXPLAIN QUERY
 *     PLAN`), confirming the index is not merely present but actually applied.
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
            // Seed a couple of Germany rows so the merge has pre-existing data.
            db.execSQL(insertSpace("de-1", 49.75, 6.64)) // Trier, inside the test box
            db.execSQL(insertSpace("de-2", 52.52, 13.40)) // Berlin, outside the test box
        }

        // Run the real production migration (imports France/Luxembourg assets and
        // creates the self-healing viewport index) and validate against 4.json.
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            BikeParkingDatabase.migration3To4ForTest(targetContext)
        )

        // 1) The composite viewport index must exist after the migration.
        val indexNames = mutableListOf<String>()
        db.query("PRAGMA index_list(`bike_parking_spaces`)").use { c ->
            val nameCol = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) indexNames.add(c.getString(nameCol))
        }
        assertTrue(
            "viewport index $INDEX_NAME must exist after v3→v4 migration",
            indexNames.contains(INDEX_NAME)
        )

        // 2) The bounding-box query returns only the spots inside the box.
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
    fun migrate3To4_mergesExtraCountryAssets() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(insertSpace("de-1", 49.75, 6.64))
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            BikeParkingDatabase.migration3To4ForTest(targetContext)
        )

        // The France/Luxembourg assets carry many rows, so the merged table must
        // hold far more than the single seeded Germany row.
        db.query("SELECT COUNT(*) FROM bike_parking_spaces").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(
                "country merge must have imported the extra country assets",
                c.getInt(0) > 1
            )
        }

        // The seeded row must survive the merge unchanged.
        db.query("SELECT latitude FROM bike_parking_spaces WHERE id = 'de-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(49.75, c.getDouble(0), 0.0001)
        }

        db.close()
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

