package de.velospot.data.local.database

/**
 * Pure (Android-free) SQL/string builders for the first-launch country merge.
 *
 * Extracted so the exact statements and the one-time "already merged?" flag key
 * can be covered by fast JVM unit tests, independent of Room/SQLite. The merge
 * itself (see [BikeParkingDatabase]) copies each bundled country asset into a
 * temp file, `ATTACH`es it and bulk-inserts its rows into the Germany-seeded
 * `bike_parking_spaces` table with a single `INSERT OR IGNORE … SELECT` — orders
 * of magnitude fewer round-trips than the previous row-by-row loop.
 */
internal object CountryMergeSql {

    /**
     * Column list shared by every bundled country dataset (identical schema, all
     * produced by `scripts/extract_osm_parking.py`). Order is fixed so the
     * `INSERT (…) SELECT …` lines up column-for-column.
     */
    const val SPACE_COLUMNS: String =
        "id,name,latitude,longitude,address,capacity,isCovered,imageUrl,operator,type,sourceLayer,lastUpdated," +
            "access,fee,lit,surveillance,supervised,cargoBike,cargoBikeCapacity,disabledCapacity," +
            "chargingCapacity,indoor,maxstay,openingHours,website,network,brand,ref,checkDate,parkingSubtype"

    /**
     * `ALTER TABLE … ADD COLUMN` statements adding the enriched OSM attribute
     * columns for the v4 → v5 migration. Every column is nullable with no default,
     * so existing rows keep all their data and simply gain NULL ("unknown") values.
     * The column order matches [de.velospot.data.local.entity.BikeParkingSpaceEntity]
     * and the tail of [SPACE_COLUMNS].
     */
    val ADD_ENRICHMENT_COLUMNS_SQL: List<String> = listOf(
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `access` TEXT",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `fee` INTEGER",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `lit` INTEGER",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `surveillance` INTEGER",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `supervised` INTEGER",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `cargoBike` INTEGER",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `cargoBikeCapacity` INTEGER",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `disabledCapacity` INTEGER",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `chargingCapacity` INTEGER",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `indoor` INTEGER",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `maxstay` TEXT",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `openingHours` TEXT",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `website` TEXT",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `network` TEXT",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `brand` TEXT",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `ref` TEXT",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `checkDate` TEXT",
        "ALTER TABLE `bike_parking_spaces` ADD COLUMN `parkingSubtype` TEXT"
    )

    /** Idempotent recreation of the viewport-query index (also present in the assets). */
    const val ENSURE_INDEX_SQL: String =
        "CREATE INDEX IF NOT EXISTS `idx_parking_lat_lon` " +
            "ON `bike_parking_spaces` (`latitude`, `longitude`)"

    /** `ATTACH` statement; the file path is passed as a bound argument by the caller. */
    fun attachSql(alias: String): String = "ATTACH DATABASE ? AS $alias"

    /** `DETACH` statement releasing the attached source database. */
    fun detachSql(alias: String): String = "DETACH DATABASE $alias"

    /**
     * Single bulk import of every row from the attached source's
     * `bike_parking_spaces` into the main table. `INSERT OR IGNORE` de-duplicates
     * on the primary key (globally-unique OSM element IDs), which is also what
     * makes an interrupted-then-rerun merge safe (already-present rows are skipped,
     * missing ones are added — the final row set is identical either way).
     */
    fun insertSelectSql(alias: String, columns: String = SPACE_COLUMNS): String =
        "INSERT OR IGNORE INTO bike_parking_spaces ($columns) " +
            "SELECT $columns FROM $alias.bike_parking_spaces"

    /**
     * DB-resident "fully merged" sentinel written into SQLite's `PRAGMA
     * application_id` once **all** country assets have been merged.
     *
     * `application_id` is chosen because Room does not use it (Room tracks its
     * schema version via `PRAGMA user_version`), so it is free for app use and
     * never trips Room's schema/identity-hash validation. The bundled asset ships
     * with the baseline value [APPLICATION_ID_BASELINE] (0), so a freshly
     * `createFromAsset`-copied database — including one re-copied after a
     * destructive fallback — always starts un-sentineled, which makes the merge
     * correctly re-run after a rebuild.
     *
     * The value spells "VELO" (0x56454C4F) and comfortably fits a signed 32-bit
     * integer, matching SQLite's `application_id` storage.
     */
    const val APPLICATION_ID_SENTINEL: Int = 0x56454C4F

    /** Baseline `application_id` carried by the bundled assets (unset = 0). */
    const val APPLICATION_ID_BASELINE: Int = 0

    /** Reads the DB-resident merge marker (`PRAGMA application_id`). */
    const val READ_APPLICATION_ID_SQL: String = "PRAGMA application_id"

    /** Writes the DB-resident merge marker. Called only after a full merge. */
    fun setApplicationIdSql(value: Int = APPLICATION_ID_SENTINEL): String =
        "PRAGMA application_id = $value"
}

