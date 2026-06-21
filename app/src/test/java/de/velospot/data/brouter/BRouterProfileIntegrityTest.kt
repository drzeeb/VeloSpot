package de.velospot.data.brouter

import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the bundled BRouter `.brf` profiles with the real BRouter expression
 * engine so any syntax error introduced by the "route hilliness" `uphill_extra`
 * edits is caught at build time (profile errors otherwise only surface at runtime
 * when a route is calculated).
 *
 * Also verifies the new `uphill_extra` parameter override is accepted by every
 * profile, mirroring how [BRouterEngine] passes it via `RoutingContext.keyValues`.
 */
class BRouterProfileIntegrityTest {

    private fun profilesDir(): File {
        // Unit tests run with the module (`app`) dir as the working directory.
        val dir = File("src/main/assets/brouter/profiles")
        assertTrue("profiles dir not found at ${dir.absolutePath}", dir.isDirectory)
        return dir
    }

    private fun parseWay(
        profile: File,
        lookup: File,
        keyValues: Map<String, String>?
    ): BExpressionContextWay {
        val meta = BExpressionMetaData()
        val way = BExpressionContextWay(meta)
        val node = BExpressionContextNode(meta)
        meta.readMetaData(lookup)
        node.setForeignContext(way)
        if (keyValues != null) {
            way.parseFile(profile, "global", HashMap(keyValues))
            node.parseFile(profile, "global", HashMap(keyValues))
        } else {
            way.parseFile(profile, "global")
            node.parseFile(profile, "global")
        }
        return way
    }

    @Test
    fun `all bundled profiles parse cleanly`() {
        val dir = profilesDir()
        val lookup = File(dir, "lookups.dat")
        assertTrue("lookups.dat missing", lookup.exists())
        val profiles = dir.listFiles { f -> f.name.endsWith(".brf") }.orEmpty()
        assertTrue("no .brf profiles found", profiles.isNotEmpty())
        profiles.forEach { parseWay(it, lookup, keyValues = null) }
    }

    @Test
    fun `every profile accepts and applies the uphill_extra parameter`() {
        val dir = profilesDir()
        val lookup = File(dir, "lookups.dat")
        val profiles = dir.listFiles { f -> f.name.endsWith(".brf") }.orEmpty()
        val extra = ElevationPreference.FLATTEST.uphillExtraCost
        profiles.forEach { profile ->
            val way = parseWay(profile, lookup, mapOf("uphill_extra" to extra.toString()))
            // The override must have replaced the profile's default uphill_extra (0).
            assertEquals(
                "uphill_extra not overridden in ${profile.name}",
                extra.toFloat(),
                way.getVariableValue("uphill_extra", -1f),
                0.001f
            )
        }
    }

    /**
     * The "Any" elevation level now passes `uphill_extra = 0` as a key-value map (rather
     * than `null`), so every level — Any included — uses the same BRouter code path.
     * This guards that every bundled profile still parses with that zero-penalty map and
     * resolves `uphill_extra` to 0, the behaviour the gravel-on-Any fix relies on.
     */
    @Test
    fun `every profile accepts the Any-level zero uphill_extra map`() {
        val dir = profilesDir()
        val lookup = File(dir, "lookups.dat")
        val profiles = dir.listFiles { f -> f.name.endsWith(".brf") }.orEmpty()
        val extra = ElevationPreference.ANY.uphillExtraCost
        assertEquals("ANY should carry a zero uphill penalty", 0, extra)
        profiles.forEach { profile ->
            val way = parseWay(profile, lookup, mapOf("uphill_extra" to extra.toString()))
            assertEquals(
                "uphill_extra not zero in ${profile.name}",
                0f,
                way.getVariableValue("uphill_extra", -1f),
                0.001f
            )
        }
    }
}

