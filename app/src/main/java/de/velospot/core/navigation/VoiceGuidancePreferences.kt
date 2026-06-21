package de.velospot.core.navigation

import android.content.Context
import androidx.core.content.edit

/**
 * Persists whether spoken turn-by-turn voice guidance (Text-to-Speech) is enabled
 * during navigation.
 *
 * Defaults to **disabled** — voice guidance is opt-in, so a fresh install stays
 * silent until the rider turns it on in the settings sheet.
 */
object VoiceGuidancePreferences {

    private const val PREFS_NAME = "velospot_navigation"
    private const val KEY_TTS_ENABLED = "navigation_tts_enabled"

    fun isVoiceGuidanceEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TTS_ENABLED, false)

    fun setVoiceGuidanceEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_TTS_ENABLED, enabled) }
    }
}

