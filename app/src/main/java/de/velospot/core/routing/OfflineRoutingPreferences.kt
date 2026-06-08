package de.velospot.core.routing

import android.content.Context
import androidx.core.content.edit
import de.velospot.data.brouter.BRouterProfile

private const val PREFS_NAME = "velospot_offline_routing"
private const val KEY_ENABLED = "offline_routing_enabled"
private const val KEY_PROFILE = "routing_profile"

object OfflineRoutingPreferences {

    fun isOfflineRoutingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setOfflineRoutingEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }

    fun getSelectedProfile(context: Context): BRouterProfile {
        val name = prefs(context).getString(KEY_PROFILE, BRouterProfile.TREKKING.fileName)
        return BRouterProfile.entries.firstOrNull { it.fileName == name } ?: BRouterProfile.TREKKING
    }

    fun setSelectedProfile(context: Context, profile: BRouterProfile) =
        prefs(context).edit { putString(KEY_PROFILE, profile.fileName) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

