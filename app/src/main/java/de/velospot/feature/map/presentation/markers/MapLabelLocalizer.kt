package de.velospot.feature.map.presentation.markers

import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * The eight app languages that ship with VeloSpot. The base map (OpenMapTiles
 * schema) carries per-language `name:<lang>` fields for these; any other /
 * unsupported language falls back to `name:latin` → `name` via the coalesce below.
 * `lb` (Luxembourgish) usually has no `name:lb` in the tiles — that is fine, the
 * coalesce degrades gracefully to `name:latin` / `name`.
 */
private val SUPPORTED_MAP_LANGUAGES = setOf("de", "es", "fr", "it", "lb", "nl", "pt", "en")

/**
 * Resolves the two-letter language code the base-map labels should follow. Prefers
 * the AppCompat per-app locale (set via [AppCompatDelegate.setApplicationLocales]
 * from the in-app language picker) and falls back to the system default. Any
 * language VeloSpot does not ship is mapped to `en` so the labels stay legible.
 */
internal fun currentMapLanguage(): String {
    val appLocale = AppCompatDelegate.getApplicationLocales()[0]
    val lang = (appLocale?.language ?: Locale.getDefault().language).lowercase(Locale.ROOT)
    return if (lang in SUPPORTED_MAP_LANGUAGES) lang else "en"
}

/**
 * Rewrites the base style's place / road / POI / water label `text-field`s so they
 * follow the app language: `coalesce(get("name:<lang>"), get("name:latin"), get("name"))`.
 * Without this the OpenMapTiles style shows endonyms (e.g. "Cologne" instead of
 * "Köln") because its symbol layers use `{name:latin}` / `{name}`.
 *
 * Pure MapLibre glue — call it from every `setStyle { … }` completion (idle, dark /
 * AMOLED reload, and any navigation style reload) since re-loading a style discards
 * these overrides. Best-effort: our own `velospot-` overlay layers and icon-only
 * layers are skipped, and each layer update is guarded so one odd layer can't abort
 * the whole pass.
 *
 * @param languageTag two-letter language code (see [currentMapLanguage]).
 */
internal fun localizeMapLabels(style: Style, languageTag: String) {
    val localized = Expression.coalesce(
        Expression.get("name:$languageTag"),
        Expression.get("name:latin"),
        Expression.get("name")
    )
    for (layer in style.layers) {
        // Only touch base-map symbol layers that actually render text; never our
        // own overlay symbol layers (parking, route, pins, clusters, …).
        if (layer !is SymbolLayer) continue
        if (layer.id.startsWith("velospot-")) continue
        // Skip icon-only layers (no text-field expression / literal set).
        if (layer.textField.isNull) continue
        runCatching {
            layer.setProperties(PropertyFactory.textField(localized))
        }
    }
}


