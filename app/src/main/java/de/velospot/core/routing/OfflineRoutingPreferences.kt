package de.velospot.core.routing

import android.content.Context
import androidx.core.content.edit
import de.velospot.data.brouter.BRouterProfile
import de.velospot.data.brouter.ElevationPreference

private const val PREFS_NAME = "velospot_offline_routing"
private const val KEY_ENABLED = "offline_routing_enabled"
private const val KEY_PROFILE = "routing_profile"
private const val KEY_ON_DEMAND = "offline_routing_on_demand"
private const val KEY_ELEVATION = "routing_elevation_level"

object OfflineRoutingPreferences {

    fun isOfflineRoutingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setOfflineRoutingEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }

    /**
     * When `true` (default), the router downloads the BRouter tiles missing for a
     * particular route on demand instead of silently falling back to OSRM online.
     */
    fun isOnDemandDownloadEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ON_DEMAND, true)

    fun setOnDemandDownloadEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_ON_DEMAND, enabled) }

    fun getSelectedProfile(context: Context): BRouterProfile {
        val name = prefs(context).getString(KEY_PROFILE, BRouterProfile.TREKKING.fileName)
        return BRouterProfile.entries.firstOrNull { it.fileName == name } ?: BRouterProfile.TREKKING
    }

    fun setSelectedProfile(context: Context, profile: BRouterProfile) =
        prefs(context).edit { putString(KEY_PROFILE, profile.fileName) }

    /**
     * How strongly offline routes should avoid climbing (the "route hilliness"
     * slider). Defaults to [ElevationPreference.DEFAULT], which preserves the
     * previous routing behaviour.
     */
    fun getElevationPreference(context: Context): ElevationPreference {
        val ordinal = prefs(context).getInt(KEY_ELEVATION, ElevationPreference.DEFAULT.ordinal)
        return ElevationPreference.fromOrdinal(ordinal)
    }

    fun setElevationPreference(context: Context, preference: ElevationPreference) =
        prefs(context).edit { putInt(KEY_ELEVATION, preference.ordinal) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
