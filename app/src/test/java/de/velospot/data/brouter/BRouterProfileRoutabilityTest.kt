package de.velospot.data.brouter

import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Routability guard for the bundled BRouter `.brf` profiles, run against the real
 * BRouter expression engine (the `:brouter` module compiled from the pinned
 * `brouter-upstream` submodule).
 *
 * [BRouterProfileIntegrityTest] already proves every profile *parses*; this test
 * goes one step further and proves every profile still *routes ordinary roads*.
 * It catches the class of regression where a profile edit accidentally prices a
 * normal cyclable highway at BRouter's "blocked" cost ([BLOCKED_COST]). When that
 * happens the router can no longer connect the start/end to the network and
 * returns an empty track — surfacing in the app as **"route data incomplete"** for
 * *every* route on the affected profile, which is exactly what slipped through when
 * the trunk/motorway hard-blocks were added.
 *
 * The check is intentionally generous (it only asserts the cost is *routable*, not
 * any particular value) so legitimately expensive-but-passable profiles — e.g. the
 * MTB profile, which heavily penalises smooth paved roads — still pass.
 */
class BRouterProfileRoutabilityTest {

    private fun newWayContext(profile: File, lookup: File): BExpressionContextWay {
        val meta = BExpressionMetaData()
        val way = BExpressionContextWay(meta)
        val node = BExpressionContextNode(meta)
        meta.readMetaData(lookup)
        node.setForeignContext(way)
        // Mirror how BRouterEngine injects the uphill_extra parameter at runtime.
        way.parseFile(profile, "global", HashMap(mapOf("uphill_extra" to "0")))
        node.parseFile(profile, "global", HashMap(mapOf("uphill_extra" to "0")))
        return way
    }

    private fun costfactor(way: BExpressionContextWay, tags: List<String>): Float {
        val lookupData = way.createNewLookupData()
        tags.forEach { t ->
            val i = t.indexOf('=')
            way.addLookupValue(t.substring(0, i), t.substring(i + 1), lookupData)
        }
        way.evaluate(false, way.encode(lookupData))
        return way.costfactor
    }

    private fun profiles(): Pair<List<File>, File> {
        // Unit tests run with the module (`app`) dir as the working directory.
        val dir = File("src/main/assets/brouter/profiles")
        val lookup = File(dir, "lookups.dat")
        val list = dir.listFiles { f -> f.name.endsWith(".brf") }.orEmpty().sortedBy { it.name }
        assertTrue("no .brf profiles found", list.isNotEmpty())
        assertTrue("lookups.dat missing", lookup.exists())
        return list to lookup
    }

    @Test
    fun `every profile keeps ordinary cyclable roads routable`() {
        val (list, lookup) = profiles()
        list.forEach { p ->
            val way = newWayContext(p, lookup)
            ORDINARY_WAYS.forEach { tags ->
                val cost = costfactor(way, tags)
                assertTrue(
                    "${p.name}: $tags must stay routable but costs $cost " +
                        "(>= $BLOCKED_COST is treated as blocked by BRouter)",
                    cost.isFinite() && cost > 0f && cost < BLOCKED_COST
                )
            }
        }
    }


    companion object {
        /**
         * BRouter treats a cost factor at/above this as effectively impassable (the
         * profiles use 10000 as the "forbidden" sentinel). A way priced here can't be
         * snapped to / routed through, so the start or end fails to match.
         */
        private const val BLOCKED_COST = 10_000f

        /**
         * A representative spread of everyday cyclable highways. None of these may ever
         * be blocked by a bundled profile, otherwise routing breaks wholesale.
         */
        private val ORDINARY_WAYS = listOf(
            listOf("highway=residential"),
            listOf("highway=living_street"),
            listOf("highway=unclassified"),
            listOf("highway=service"),
            listOf("highway=tertiary", "surface=asphalt"),
            listOf("highway=secondary"),
            listOf("highway=primary"),
            listOf("highway=cycleway"),
            listOf("highway=path", "bicycle=yes"),
            listOf("highway=track"),
        )
    }
}

