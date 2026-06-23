package de.velospot.core.display

import android.content.Context
import androidx.core.content.edit

/**
 * Persists whether the device display is kept awake during a follow session —
 * active navigation **or** a live ride recording — so the screen doesn't dim or
 * lock mid-ride.
 *
 * Defaults to **enabled**: keeping the screen on while navigating/recording is
 * the expected behaviour, but the rider can turn it off in the settings sheet
 * (e.g. to save battery and rely on voice guidance instead).
 */
object KeepScreenOnPreferences {

    private const val PREFS_NAME = "velospot_display"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_enabled"

    fun isKeepScreenOnEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_SCREEN_ON, true)

    fun setKeepScreenOnEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_KEEP_SCREEN_ON, enabled) }
    }
}

