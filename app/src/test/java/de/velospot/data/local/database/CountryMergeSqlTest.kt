package de.velospot.data.local.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [CountryMergeSql] — the pure SQL/flag builders used by the
 * first-launch country merge. These verify the exact statements and the
 * exactly-once flag key without needing Room or a device.
 */
class CountryMergeSqlTest {

    @Test
    fun spaceColumns_matchEntityOrder() {
        // The column list must line up 1:1 with the INSERT … SELECT projection.
        assertEquals(
            "id,name,latitude,longitude,address,capacity,isCovered," +
                "imageUrl,operator,type,sourceLayer,lastUpdated",
            CountryMergeSql.SPACE_COLUMNS
        )
    }

    @Test
    fun insertSelect_usesInsertOrIgnore_andSameColumnsOnBothSides() {
        val sql = CountryMergeSql.insertSelectSql("country_src")

        assertTrue("must de-duplicate on primary key", sql.startsWith("INSERT OR IGNORE INTO bike_parking_spaces"))
        assertTrue("must read from the attached alias", sql.contains("FROM country_src.bike_parking_spaces"))
        // Identical column lists on both sides guarantees column alignment.
        val cols = CountryMergeSql.SPACE_COLUMNS
        assertTrue(sql.contains("($cols)"))
        assertTrue(sql.contains("SELECT $cols FROM"))
    }

    @Test
    fun attachAndDetach_referenceTheSameAlias() {
        assertEquals("ATTACH DATABASE ? AS country_src", CountryMergeSql.attachSql("country_src"))
        assertEquals("DETACH DATABASE country_src", CountryMergeSql.detachSql("country_src"))
    }

    @Test
    fun ensureIndex_isIdempotent() {
        assertTrue(CountryMergeSql.ENSURE_INDEX_SQL.contains("CREATE INDEX IF NOT EXISTS"))
        assertTrue(CountryMergeSql.ENSURE_INDEX_SQL.contains("idx_parking_lat_lon"))
        assertTrue(CountryMergeSql.ENSURE_INDEX_SQL.contains("(`latitude`, `longitude`)"))
    }

    @Test
    fun applicationId_sentinelDiffersFromBaseline() {
        // The DB-resident "fully merged" marker must never collide with the
        // baseline value carried by the freshly copied asset, otherwise a fresh
        // (or destructively rebuilt) database would look "already merged".
        assertEquals(0, CountryMergeSql.APPLICATION_ID_BASELINE)
        assertEquals(0x56454C4F, CountryMergeSql.APPLICATION_ID_SENTINEL)
        assertTrue(
            "sentinel must differ from baseline so the guard clears on rebuild",
            CountryMergeSql.APPLICATION_ID_SENTINEL != CountryMergeSql.APPLICATION_ID_BASELINE
        )
    }

    @Test
    fun readApplicationId_isThePlainPragma() {
        assertEquals("PRAGMA application_id", CountryMergeSql.READ_APPLICATION_ID_SQL)
    }

    @Test
    fun setApplicationId_writesTheSentinelByDefault() {
        assertEquals(
            "PRAGMA application_id = ${CountryMergeSql.APPLICATION_ID_SENTINEL}",
            CountryMergeSql.setApplicationIdSql()
        )
        // An explicit value (e.g. resetting to baseline in a test) is honoured too.
        assertEquals("PRAGMA application_id = 0", CountryMergeSql.setApplicationIdSql(0))
    }
}

