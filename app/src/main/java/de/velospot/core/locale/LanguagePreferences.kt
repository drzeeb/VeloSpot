package de.velospot.core.locale

import android.content.Context
import androidx.core.content.edit
import java.util.Locale

private const val PREFS_NAME = "velospot_language"
private const val KEY_LANGUAGE_CODE = "language_code"

object LanguagePreferences {

    /** Returns the user-selected language code, or null if none was saved yet. */
    fun getSavedLanguageCode(context: Context): String? {
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_CODE, null)
    }

    /** Persists the user's language choice. */
    fun saveLanguageCode(context: Context, languageCode: String) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_LANGUAGE_CODE, languageCode) }
    }

    /** Wraps [base] with the user-saved locale (if any). Falls back to the base context unchanged. */
    fun wrap(base: Context): Context {
        val savedCode = getSavedLanguageCode(base) ?: return base
        val locale = Locale.forLanguageTag(savedCode)
        Locale.setDefault(locale)
        val config = base.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        return base.createConfigurationContext(config)
    }
}

