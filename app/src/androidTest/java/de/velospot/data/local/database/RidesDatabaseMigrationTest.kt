package de.velospot.data.local.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration safety net for [RidesDatabase] — the highest-risk user store, which
 * holds the irreplaceable recorded-ride history and has evolved through eight
 * hand-written `ALTER TABLE` migrations (v1 → v9).
 *
 * Room only ever exports the schema JSON for the *current* version, and the
 * database ran with `exportSchema = false` for most of its history, so there are
 * no historical (`1.json` … `8.json`) baselines for [MigrationTestHelper] to seed
 * a v1 database from. Instead the original v1 schema is recreated here by hand
 * (the original `recorded_rides` table, before any `ALTER TABLE`), a row is
 * inserted, and then the real production migrations are run all the way to v9 and
 * **validated against the checked-in `9.json` baseline**. If any `MIGRATION_x_y`
 * produces a schema that does not exactly match the current entities, Room's
 * `runMigrationsAndValidate` throws — catching a typo before it can corrupt real
 * ride history.
 *
 * The data-carry assertions additionally prove that a ride recorded on the very
 * first app version survives every migration with the newly-added columns
 * defaulted correctly.
 */
@RunWith(AndroidJUnit4::class)
class RidesDatabaseMigrationTest {

    private val allMigrations = RidesDatabase.ALL_MIGRATIONS

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RidesDatabase::class.java
    )

    /**
     * Original v1 `recorded_rides` table — the schema before the `name`,
     * `isMock`, `archivedAt`, `bikeProfileId`, `sourceRouteId` and `weatherJson`
     * columns (and the timeline indices) were added by later migrations.
     */
    private val createRecordedRidesV1 =
        "CREATE TABLE IF NOT EXISTS `recorded_rides` (" +
            "`id` TEXT NOT NULL, " +
            "`startedAt` INTEGER NOT NULL, " +
            "`endedAt` INTEGER NOT NULL, " +
            "`distanceMeters` REAL NOT NULL, " +
            "`elapsedSeconds` INTEGER NOT NULL, " +
            "`movingSeconds` INTEGER NOT NULL, " +
            "`avgSpeedMps` REAL NOT NULL, " +
            "`maxSpeedMps` REAL NOT NULL, " +
            "`elevationGainMeters` REAL NOT NULL, " +
            "`elevationLossMeters` REAL NOT NULL, " +
            "`pointsJson` TEXT NOT NULL, " +
            "PRIMARY KEY(`id`))"

    /**
     * Creates the database file at version 1 with the original schema (bypassing
     * [MigrationTestHelper.createDatabase], which would require a `1.json` baseline
     * that never existed), seeds one ride, and returns after closing the handle.
     */
    private fun createV1Database(seedRow: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(createRecordedRidesV1)
                if (seedRow) {
                    db.execSQL(
                        "INSERT INTO recorded_rides (" +
                            "id, startedAt, endedAt, distanceMeters, elapsedSeconds, " +
                            "movingSeconds, avgSpeedMps, maxSpeedMps, elevationGainMeters, " +
                            "elevationLossMeters, pointsJson) VALUES (" +
                            "'ride-1', 1000, 2000, 1234.5, 1000, 900, 5.0, 9.0, 42.0, 7.0, '[]')"
                    )
                }
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // No-op: the file is only ever opened at v1 here.
            }
        }
        val configuration = SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(TEST_DB)
            .callback(callback)
            .build()
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        // Touching the writable database forces onCreate to run.
        openHelper.writableDatabase
        openHelper.close()
    }

    @Test
    fun migrateAll_1_to_9_validatesAgainstExportedSchema() {
        createV1Database(seedRow = false)

        // Runs MIGRATION_1_2 … MIGRATION_8_9 and validates the resulting schema
        // (columns, indices, foreign keys) against the exported 9.json baseline.
        helper.runMigrationsAndValidate(TEST_DB, 9, true, *allMigrations).close()
    }

    @Test
    fun migrateAll_1_to_9_preservesRideAndDefaultsNewColumns() {
        createV1Database(seedRow = true)

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, *allMigrations)

        db.query("SELECT * FROM recorded_rides WHERE id = 'ride-1'").use { c ->
            assertTrue("seeded v1 ride must survive the full migration chain", c.moveToFirst())

            // Original v1 payload is intact.
            assertEquals(1234.5, c.getDouble(c.getColumnIndexOrThrow("distanceMeters")), 0.0001)
            assertEquals(1000L, c.getLong(c.getColumnIndexOrThrow("startedAt")))

            // Columns added by later migrations exist and carry their defaults.
            assertNull(c.getString(c.getColumnIndexOrThrow("name")))            // v2
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("isMock")))         // v3, DEFAULT 0
            assertTrue(c.isNull(c.getColumnIndexOrThrow("archivedAt")))          // v3
            assertNull(c.getString(c.getColumnIndexOrThrow("bikeProfileId")))    // v5
            assertNull(c.getString(c.getColumnIndexOrThrow("sourceRouteId")))    // v7
            assertNull(c.getString(c.getColumnIndexOrThrow("weatherJson")))      // v8
        }

        // The bike garage table introduced in v5 must exist and be empty, and the
        // v9 `photoPath` column must be present (and default NULL for new bikes).
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'bike_profiles'"
        ).use { c ->
            assertTrue("bike_profiles table must be created by MIGRATION_4_5", c.moveToFirst())
        }
        db.query("SELECT COUNT(*) FROM bike_profiles").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.execSQL(
            "INSERT INTO bike_profiles (id, name, type, isDefault, createdAt, lastServiceNotifiedKm) " +
                "VALUES ('bike-1', 'Racer', 'ROAD', 0, 1000, 0)"
        )
        db.query("SELECT photoPath FROM bike_profiles WHERE id = 'bike-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertNull("photoPath must default to NULL after MIGRATION_8_9", c.getString(0)) // v9
        }

        db.close()
    }

    @Test
    fun openMigratedDatabaseWithRoom_succeeds() {
        // Belt-and-braces: after migrating, opening through the real Room builder
        // (which re-validates the identity hash) must not trigger a destructive
        // rebuild or an integrity error.
        createV1Database(seedRow = false)
        helper.runMigrationsAndValidate(TEST_DB, 9, true, *allMigrations).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, RidesDatabase::class.java, TEST_DB)
            .addMigrations(*allMigrations)
            .build()
        // Force the DB open through Room's normal path, which re-validates the
        // identity hash; a mismatch would throw here instead of returning.
        db.recordedRideDao()
        db.openHelper.writableDatabase
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-test-rides.db"
    }
}

