package de.velospot.core.navigation

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the user's preferred navigation camera perspective: the tilted 3D
 * view (60° pitch + extruded buildings) or the flat 2D heading-up view.
 *
 * Defaults to 3D — the richer, Google-Maps-style experience.
 */
object NavigationModePreferences {

    private const val PREFS_NAME = "velospot_navigation"
    private const val KEY_3D_ENABLED = "navigation_3d_enabled"

    fun is3DEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_3D_ENABLED, true)

    fun set3DEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_3D_ENABLED, enabled) }
    }
}

