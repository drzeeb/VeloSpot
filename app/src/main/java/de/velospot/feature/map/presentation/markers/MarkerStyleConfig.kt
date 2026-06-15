package de.velospot.feature.map.presentation.markers

import androidx.core.graphics.toColorInt

data class MarkerStyleConfig(
    val normalPinColor: Int,
    val favoritePinColor: Int,
    val selectedPinColor: Int,
    val routeColor: Int,
    val mutedScale: Float,
    val mutedAlpha: Int,
    val mutedBrightenOffset: Float
)

internal fun defaultMarkerStyleConfig(isDarkTheme: Boolean = false): MarkerStyleConfig =
    if (isDarkTheme) darkMarkerStyleConfig() else lightMarkerStyleConfig()

private fun lightMarkerStyleConfig(): MarkerStyleConfig = MarkerStyleConfig(
    normalPinColor = "#0A2A66".toColorInt(),
    favoritePinColor = "#D32F2F".toColorInt(),
    selectedPinColor = "#FF8F00".toColorInt(),
    routeColor = "#1976D2".toColorInt(),
    mutedScale = 0.84f,
    mutedAlpha = 130,
    mutedBrightenOffset = 34f
)

// Brighter, higher-contrast variant for the dark map style. The normal pin in
// particular is lifted well clear of the dark water colour (#0a2233) it would
// otherwise blend into; favourite/selected/route stay in the same hue family
// but a touch lighter for legibility on the near-black background.
private fun darkMarkerStyleConfig(): MarkerStyleConfig = MarkerStyleConfig(
    normalPinColor = "#3B82F6".toColorInt(),
    favoritePinColor = "#F44336".toColorInt(),
    selectedPinColor = "#FFB300".toColorInt(),
    routeColor = "#42A5F5".toColorInt(),
    mutedScale = 0.84f,
    mutedAlpha = 130,
    mutedBrightenOffset = 34f
)

