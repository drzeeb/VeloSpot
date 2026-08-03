package de.velospot.data.brouter

import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies with the real BRouter expression engine that the bike profiles
 * de-prefer gravel / loose unpaved tracks ("Schotterwege") in favour of a
 * dedicated cycleway (Fahrradweg) or a paved road.
 *
 * A user reported the *trekking* profile — the default one for ordinary riders —
 * picking a gravel track even though a cycleway ran only a few metres away. The
 * *shortest* profile did not consider the surface at all, so it happily crossed
 * gravel whenever it was marginally shorter. The *fastbike* profile already
 * strongly avoided unpaved tracks; this test guards that it stays that way.
 *
 * The cost of each way type is evaluated exactly like the map-creator does:
 * build a lookup-data array from OSM tags, evaluate the profile and read the
 * resulting `costfactor`.
 */
class BRouterGravelAvoidanceTest {

    private fun profilesDir(): File {
        val dir = File("src/main/assets/brouter/profiles")
        assertTrue("profiles dir not found at ${dir.absolutePath}", dir.isDirectory)
        return dir
    }

    private fun wayContext(profileName: String): BExpressionContextWay {
        val dir = profilesDir()
        val meta = BExpressionMetaData()
        val way = BExpressionContextWay(meta)
        val node = BExpressionContextNode(meta)
        meta.readMetaData(File(dir, "lookups.dat"))
        node.setForeignContext(way)
        val profile = File(dir, profileName)
        way.parseFile(profile, "global")
        node.parseFile(profile, "global")
        return way
    }

    /** Costfactor for a way described by the given OSM tags. */
    private fun BExpressionContextWay.costOf(vararg tags: Pair<String, String>): Float {
        val lookupData = createNewLookupData()
        tags.forEach { (key, value) ->
            addLookupValue(key, value.replace(' ', '_'), lookupData)
        }
        val description = encode(lookupData)
        evaluate(false, description)
        return costfactor
    }

    private fun cyclewayCost(way: BExpressionContextWay) =
        way.costOf("highway" to "cycleway")

    private fun pavedRoadCost(way: BExpressionContextWay) =
        way.costOf("highway" to "residential", "surface" to "asphalt")

    private fun gravelTrackCost(way: BExpressionContextWay) =
        way.costOf("highway" to "track", "tracktype" to "grade1", "surface" to "gravel")

    @Test
    fun `trekking prefers a cycleway over a gravel track`() {
        val way = wayContext("trekking.brf")
        val cycleway = cyclewayCost(way)
        val gravel = gravelTrackCost(way)
        assertTrue(
            "trekking: cycleway ($cycleway) should be clearly cheaper than gravel ($gravel)",
            gravel > cycleway * 1.5f
        )
    }

    @Test
    fun `shortest avoids gravel in favour of paved ways and cycleways`() {
        val way = wayContext("shortest.brf")
        val cycleway = cyclewayCost(way)
        val paved = pavedRoadCost(way)
        val gravel = gravelTrackCost(way)
        assertTrue("shortest: paved ($paved) should beat gravel ($gravel)", gravel > paved * 1.5f)
        assertTrue("shortest: cycleway ($cycleway) should beat gravel ($gravel)", gravel > cycleway * 1.5f)
    }

    @Test
    fun `fastbike keeps strongly avoiding gravel tracks`() {
        val way = wayContext("fastbike.brf")
        val cycleway = cyclewayCost(way)
        val gravel = gravelTrackCost(way)
        assertTrue(
            "fastbike: gravel ($gravel) should stay well above a cycleway ($cycleway)",
            gravel > cycleway * 1.5f
        )
    }
}


