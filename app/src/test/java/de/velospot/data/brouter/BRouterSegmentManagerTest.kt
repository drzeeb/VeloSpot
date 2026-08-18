package de.velospot.data.brouter

import android.content.Context
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [BRouterSegmentManager]'s pure tile-naming and on-disk
 * bookkeeping logic. The segments directory is a real temp folder (resolved from a
 * mocked [Context]); no network is touched — the download plumbing is out of scope.
 */
class BRouterSegmentManagerTest {

    private val segmentsRoot = Files.createTempDirectory("brouter-seg-root").toFile()
    private val context: Context = mock {
        whenever(it.getExternalFilesDir(null)).thenReturn(segmentsRoot)
    }
    private val okHttp = OkHttpClient()
    private val manager = BRouterSegmentManager(context, okHttp)

    private fun touch(name: String): File =
        File(manager.segmentsDir, name).apply { writeText("x") }

    @Test
    fun `segment tile name encodes the south-west corner in 5 degree steps`() {
        // Trier (~49.75N, 6.64E) → floor to E5_N45.
        assertEquals("E5_N45.rd5", manager.segmentTileNameForLocation(49.75, 6.64))
        // Western / southern hemisphere prefixes.
        assertEquals("W5_N45.rd5", manager.segmentTileNameForLocation(47.0, -1.0))
    }

    @Test
    fun `requiredSegmentNames covers every 5 degree tile in the bounding box`() {
        val names = manager.requiredSegmentNames(
            fromLat = 49.0, fromLon = 6.0,
            toLat = 52.0, toLon = 11.0
        )
        // Longitude tiles E5, E10 × latitude tiles N45, N50.
        assertTrue(names.contains("E5_N45.rd5"))
        assertTrue(names.contains("E5_N50.rd5"))
        assertTrue(names.contains("E10_N45.rd5"))
        assertTrue(names.contains("E10_N50.rd5"))
    }

    @Test
    fun `requiredSegmentNamesForPoints de-duplicates tiles along a route`() {
        val tiles = manager.requiredSegmentNamesForPoints(
            listOf(49.75 to 6.64, 49.76 to 6.65, 50.10 to 8.60)
        )
        assertEquals(listOf("E5_N45.rd5", "E5_N50.rd5"), tiles)
    }

    @Test
    fun `hasAllSegments reflects presence on disk`() {
        assertFalse(manager.hasAllSegments(49.75, 6.64, 49.76, 6.65))
        touch("E5_N45.rd5")
        assertTrue(manager.hasAllSegments(49.75, 6.64, 49.76, 6.65))
    }

    @Test
    fun `presence, size and deletion of segment tiles`() {
        assertFalse(manager.hasAnySegments())
        assertEquals(0L, manager.totalSegmentsSizeBytes())

        val tile = touch("E5_N50.rd5")
        assertTrue(manager.hasAnySegments())
        assertTrue(manager.hasSegmentTile("E5_N50.rd5"))
        assertEquals(tile.length(), manager.totalSegmentsSizeBytes())

        manager.deleteSegmentTile("E5_N50.rd5")
        assertFalse(manager.hasSegmentTile("E5_N50.rd5"))

        touch("E0_N45.rd5")
        touch("E0_N50.rd5")
        manager.deleteAllSegments()
        assertFalse(manager.hasAnySegments())
    }

    @Test
    fun `hasAllCountrySegments is true only once every country tile is present`() {
        assertFalse(manager.hasAllCountrySegments())
        BRouterSegmentManager.COUNTRY_SEGMENTS.forEach { touch(it) }
        assertTrue(manager.hasAllCountrySegments())
    }
}

