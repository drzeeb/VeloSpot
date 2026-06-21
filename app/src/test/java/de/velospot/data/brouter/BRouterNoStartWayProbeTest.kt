package de.velospot.data.brouter

import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the "don't start on the sidewalk" behaviour.
 *
 * BRouter only avoids snapping the route start/end onto a `footway`/`sidewalk` when
 * the profile declares `check_start_way` with a `noStartWay=footway,sidewalk`
 * annotation, which it parses into `BExpressionContextWay.noStartWays`. If a profile
 * omits it, navigation opens on the pavement with a crossing detour. **Every**
 * bundled profile must therefore populate `noStartWays`.
 */
class BRouterNoStartWayProbeTest {

    private fun noStartWayCount(profile: File, lookup: File, keyValues: Map<String, String>?): Int {
        val meta = BExpressionMetaData()
        val way = BExpressionContextWay(meta)
        val node = BExpressionContextNode(meta)
        meta.readMetaData(lookup)
        node.setForeignContext(way)
        if (keyValues != null) {
            way.parseFile(profile, "global", HashMap(keyValues))
        } else {
            way.parseFile(profile, "global")
        }
        return way.noStartWays.size
    }

    @Test
    fun `every bundled profile avoids starting on the sidewalk`() {
        val dir = File("src/main/assets/brouter/profiles")
        val lookup = File(dir, "lookups.dat")
        val profiles = dir.listFiles { f -> f.name.endsWith(".brf") }.orEmpty()
        assertTrue("no .brf profiles found", profiles.isNotEmpty())
        profiles.forEach { p ->
            // Probe both routing paths: the elevation slider always injects
            // `uphill_extra`, while a bare parse (no key-values) must work too.
            assertTrue(
                "${p.name} has no noStartWay guard (no key-values)",
                noStartWayCount(p, lookup, null) > 0
            )
            assertTrue(
                "${p.name} has no noStartWay guard (with uphill_extra)",
                noStartWayCount(p, lookup, mapOf("uphill_extra" to "0")) > 0
            )
        }
    }
}

