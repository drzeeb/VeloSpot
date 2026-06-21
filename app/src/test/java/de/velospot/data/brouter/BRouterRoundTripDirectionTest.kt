package de.velospot.data.brouter

import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the round-trip direction handling.
 *
 * BRouter's [btools.router.RoutingEngine.doRoundTrip] derives a start heading via
 * `getRandomDirectionFromData` **only when no `startDirection` is set**. For profiles
 * whose global config enables `consider_elevation` / `consider_forest` /
 * `consider_river`, that path needs area-info data and a `dummy.brf` we do not bundle,
 * so the loop silently produces no route. [BRouterEngine.calculateRoundTrip] therefore
 * always passes a concrete direction.
 *
 * This test documents which bundled profiles trip that branch, so the root cause is
 * visible and a future profile change can't silently reintroduce the crash unnoticed.
 */
class BRouterRoundTripDirectionTest {

    private fun readGlobalFlag(profile: File, lookup: File, name: String): Boolean {
        val meta = BExpressionMetaData()
        val way = BExpressionContextWay(meta)
        val node = BExpressionContextNode(meta)
        meta.readMetaData(lookup)
        node.setForeignContext(way)
        way.parseFile(profile, "global")
        node.parseFile(profile, "global")
        return way.getVariableValue(name, 0f) == 1f
    }

    @Test
    fun `elevation-aware profiles would need the area-data direction path`() {
        val dir = File("src/main/assets/brouter/profiles")
        val lookup = File(dir, "lookups.dat")
        assertTrue("lookups.dat missing", lookup.exists())

        val elevationAware = dir.listFiles { f -> f.name.endsWith(".brf") }.orEmpty()
            .filter { p ->
                readGlobalFlag(p, lookup, "consider_elevation") ||
                    readGlobalFlag(p, lookup, "consider_forest") ||
                    readGlobalFlag(p, lookup, "consider_river")
            }
            .map { it.name }
            .toSet()

        // These are exactly the profiles that previously failed round-trip generation
        // when no explicit direction was supplied.
        assertTrue(
            "expected trekking/fastbike/mtb to enable an area-aware flag, got $elevationAware",
            elevationAware.containsAll(listOf("trekking.brf", "fastbike.brf", "mtb.brf"))
        )
    }
}

