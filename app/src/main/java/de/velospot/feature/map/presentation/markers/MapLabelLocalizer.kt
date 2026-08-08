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
 * these overrides. Best-effort: our own `velospot-` overlay layers, icon-only
 * layers and road route-number **shields** are skipped (see [shouldLocalizeLabelLayer]),
 * and each layer update is guarded so one odd layer can't abort the whole pass.
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
        if (layer !is SymbolLayer) continue
        if (!shouldLocalizeLabelLayer(
                layerId = layer.id,
                hasTextField = !layer.textField.isNull,
                hasIconImage = !layer.iconImage.isNull,
                sourceLayer = layer.sourceLayer
            )
        ) {
            continue
        }
        runCatching {
            layer.setProperties(PropertyFactory.textField(localized))
        }
    }
}

/**
 * Pure decision: should this base-map symbol layer have its `text-field` rewritten to
 * the localized name? Extracted so the (otherwise MapLibre-native) filtering can be
 * exercised by fast JVM unit tests.
 *
 * Rewritten layers are base-map layers that render a **translatable name**. We
 * deliberately skip:
 *  - our own `velospot-` overlay layers (parking, route, pins, clusters, …),
 *  - icon-only layers with no text at all,
 *  - road route-number **shields** — these carry the road `ref` (e.g. "B51") inside a
 *    shield icon box, *not* a name. Overwriting their `text-field` would render the
 *    full street name inside that little box, producing the "white box behind street
 *    names" artefact. They are detected by a `shield` in the layer id, or by being a
 *    `transportation_name` layer that draws an icon (only shields do so there).
 */
internal fun shouldLocalizeLabelLayer(
    layerId: String,
    hasTextField: Boolean,
    hasIconImage: Boolean,
    sourceLayer: String?
): Boolean {
    if (layerId.startsWith("velospot-")) return false
    if (!hasTextField) return false
    if (layerId.contains("shield", ignoreCase = true)) return false
    if (hasIconImage && sourceLayer == "transportation_name") return false
    return true
}


