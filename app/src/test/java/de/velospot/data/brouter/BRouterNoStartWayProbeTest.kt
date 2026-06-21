package de.velospot.data.brouter
import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
/**
 * Guards that no bundled profile opens navigation on / hugs the sidewalk.
 *
 * Two independent mechanisms keep routes off the pavement, and both must hold for
 * every profile (they regressed when only gravel/trekking had them):
 *  1. Start snapping -- BRouter only refuses to snap the route start/end onto a
 *     footway=sidewalk when the profile declares check_start_way +
 *     noStartWay=footway,sidewalk, parsed into BExpressionContextWay.noStartWays.
 *  2. Riding cost -- cycling a footway=sidewalk (even with bicycle=yes) must cost
 *     clearly more than the carriageway, otherwise the route hugs the pavement.
 */
class BRouterNoStartWayProbeTest {
    private fun newWayContext(profile: File, lookup: File): BExpressionContextWay {
        val meta = BExpressionMetaData()
        val way = BExpressionContextWay(meta)
        val node = BExpressionContextNode(meta)
        meta.readMetaData(lookup)
        node.setForeignContext(way)
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
        val dir = File("src/main/assets/brouter/profiles")
        val lookup = File(dir, "lookups.dat")
        val list = dir.listFiles { f -> f.name.endsWith(".brf") }.orEmpty().sortedBy { it.name }
        assertTrue("no .brf profiles found", list.isNotEmpty())
        return list to lookup
    }
    @Test
    fun `every bundled profile avoids snapping the start onto a sidewalk`() {
        val (list, lookup) = profiles()
        list.forEach { p ->
            assertTrue(
                "${p.name} has no noStartWay guard (parse populates noStartWays)",
                newWayContext(p, lookup).noStartWays.isNotEmpty()
            )
        }
    }
    @Test
    fun `every bundled profile penalises cycling a sidewalk`() {
        val (list, lookup) = profiles()
        // Cycling a bicycle-allowed sidewalk must cost clearly more than a quiet road,
        // so the route prefers the carriageway. 3.0 sits well above residential (~1.1).
        val minSidewalkCost = 3.0f
        list.forEach { p ->
            val way = newWayContext(p, lookup)
            val cost = costfactor(way, listOf("highway=footway", "footway=sidewalk", "bicycle=yes"))
            assertTrue(
                "${p.name} rides bicycle=yes sidewalks too cheaply (costfactor=$cost)",
                cost >= minSidewalkCost
            )
        }
    }
}
