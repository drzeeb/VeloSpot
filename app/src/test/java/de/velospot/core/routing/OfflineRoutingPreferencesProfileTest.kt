package de.velospot.core.routing

import de.velospot.data.brouter.BRouterProfile
import de.velospot.testsupport.fakeContextWithPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that [OfflineRoutingPreferences.getSelectedProfile] gracefully falls
 * back to the default bike profile when the persisted value is missing, unknown,
 * or points at a profile that is no longer user-selectable (e.g. a legacy
 * "shortest" persisted before it was hidden by product decision #23).
 */
class OfflineRoutingPreferencesProfileTest {

    @Test
    fun `default when nothing is persisted is the default bike profile`() {
        val ctx = fakeContextWithPrefs()
        assertEquals(BRouterProfile.DEFAULT, OfflineRoutingPreferences.getSelectedProfile(ctx))
    }

    @Test
    fun `a selectable persisted profile is returned unchanged`() {
        val ctx = fakeContextWithPrefs()
        OfflineRoutingPreferences.setSelectedProfile(ctx, BRouterProfile.GRAVEL)
        assertEquals(BRouterProfile.GRAVEL, OfflineRoutingPreferences.getSelectedProfile(ctx))
    }

    @Test
    fun `legacy persisted SHORTEST falls back to the default bike profile`() {
        val ctx = fakeContextWithPrefs()
        // Simulate a user who had SHORTEST persisted before it was hidden.
        ctx.getSharedPreferences("velospot_offline_routing", 0)
            .edit()
            .putString("routing_profile", BRouterProfile.SHORTEST.fileName)
            .commit()

        val loaded = OfflineRoutingPreferences.getSelectedProfile(ctx)
        assertEquals(BRouterProfile.DEFAULT, loaded)
    }

    @Test
    fun `an unknown persisted profile name falls back to the default`() {
        val ctx = fakeContextWithPrefs()
        ctx.getSharedPreferences("velospot_offline_routing", 0)
            .edit()
            .putString("routing_profile", "does_not_exist")
            .commit()

        assertEquals(BRouterProfile.DEFAULT, OfflineRoutingPreferences.getSelectedProfile(ctx))
    }
}

