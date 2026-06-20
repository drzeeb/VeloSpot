package de.velospot.feature.map.presentation.ride

import androidx.annotation.StringRes
import de.velospot.R

/**
 * A hand-picked colour theme for the shareable "VeloSpot Wrapped" ride card.
 *
 * All colours are plain ARGB [Int]s so they can be used both by the
 * [android.graphics.Canvas] renderer and (wrapped in
 * [androidx.compose.ui.graphics.Color]) by the Compose colour-picker UI.
 *
 * @property id stable identifier used as the selection key.
 * @property nameRes localised display name of the theme.
 * @property gradient the three background gradient stops (top → middle → bottom).
 * @property accent the mint/brand accent (brand dot + headline label).
 * @property routeColor the bright core colour of the route polyline.
 * @property startDot start marker fill colour.
 * @property endDot end marker fill colour.
 */
internal data class RideShareTheme(
    val id: String,
    @param:StringRes val nameRes: Int,
    val gradient: IntArray,
    val accent: Int,
    val routeColor: Int,
    val startDot: Int,
    val endDot: Int
) {
    val gradientTop: Int get() = gradient[0]
    val gradientMid: Int get() = gradient[1]
    val gradientBottom: Int get() = gradient[2]

    // Data classes with an array property need explicit equals/hashCode so the
    // theme can be used as a stable key for state / recomposition.
    override fun equals(other: Any?): Boolean = this === other || (other is RideShareTheme && other.id == id)
    override fun hashCode(): Int = id.hashCode()
}

/** The catalogue of selectable card themes. */
internal object RideShareThemes {

    val Aurora = RideShareTheme(
        id = "aurora",
        nameRes = R.string.ride_share_theme_aurora,
        gradient = intArrayOf(0xFF6D28D9.toInt(), 0xFF2563EB.toInt(), 0xFF06B6D4.toInt()),
        accent = 0xFF7DF9C4.toInt(),
        routeColor = 0xFFFFFFFF.toInt(),
        startDot = 0xFF22E06B.toInt(),
        endDot = 0xFFFF5A5F.toInt()
    )

    val Sunset = RideShareTheme(
        id = "sunset",
        nameRes = R.string.ride_share_theme_sunset,
        gradient = intArrayOf(0xFFF9357A.toInt(), 0xFFFF6A3D.toInt(), 0xFFFFB347.toInt()),
        accent = 0xFFFFE9A8.toInt(),
        routeColor = 0xFFFFFFFF.toInt(),
        startDot = 0xFF4ADE80.toInt(),
        endDot = 0xFF7C3AED.toInt()
    )

    val Forest = RideShareTheme(
        id = "forest",
        nameRes = R.string.ride_share_theme_forest,
        gradient = intArrayOf(0xFF0BA360.toInt(), 0xFF11998E.toInt(), 0xFF2BC0A8.toInt()),
        accent = 0xFFE3FCEC.toInt(),
        routeColor = 0xFFFFFFFF.toInt(),
        startDot = 0xFFFACC15.toInt(),
        endDot = 0xFFFF5A5F.toInt()
    )

    val Ocean = RideShareTheme(
        id = "ocean",
        nameRes = R.string.ride_share_theme_ocean,
        gradient = intArrayOf(0xFF0F2027.toInt(), 0xFF203A6B.toInt(), 0xFF2C8C9C.toInt()),
        accent = 0xFF5EEAD4.toInt(),
        routeColor = 0xFF8DF7FF.toInt(),
        startDot = 0xFF34D399.toInt(),
        endDot = 0xFFFF7E6B.toInt()
    )

    val Berry = RideShareTheme(
        id = "berry",
        nameRes = R.string.ride_share_theme_berry,
        gradient = intArrayOf(0xFF8E2DE2.toInt(), 0xFFB5179E.toInt(), 0xFFE0218A.toInt()),
        accent = 0xFFFFD6F5.toInt(),
        routeColor = 0xFFFFFFFF.toInt(),
        startDot = 0xFF7DF9C4.toInt(),
        endDot = 0xFFFFE066.toInt()
    )

    val Midnight = RideShareTheme(
        id = "midnight",
        nameRes = R.string.ride_share_theme_midnight,
        gradient = intArrayOf(0xFF141E30.toInt(), 0xFF243B55.toInt(), 0xFF3A6073.toInt()),
        accent = 0xFF7DF9C4.toInt(),
        routeColor = 0xFFFFD166.toInt(),
        startDot = 0xFF22E06B.toInt(),
        endDot = 0xFFFF5A5F.toInt()
    )

    /** All themes, in display order. */
    val all: List<RideShareTheme> = listOf(Aurora, Sunset, Forest, Ocean, Berry, Midnight)

    val default: RideShareTheme = Aurora
}

