package de.velospot.feature.map.presentation

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

internal fun defaultMarkerStyleConfig(): MarkerStyleConfig = MarkerStyleConfig(
    normalPinColor = "#0A2A66".toColorInt(),
    favoritePinColor = "#D32F2F".toColorInt(),
    selectedPinColor = "#FF8F00".toColorInt(),
    routeColor = "#1976D2".toColorInt(),
    mutedScale = 0.84f,
    mutedAlpha = 130,
    mutedBrightenOffset = 34f
)

