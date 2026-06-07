package de.velospot.core.theme

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "velospot_theme"
private const val KEY_DARK_MODE_ENABLED = "dark_mode_enabled"

object DarkModePreferences {

    fun isDarkModeEnabled(context: Context): Boolean {
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE_ENABLED, false)
    }

    fun setDarkModeEnabled(context: Context, isEnabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DARK_MODE_ENABLED, isEnabled) }
    }
}

