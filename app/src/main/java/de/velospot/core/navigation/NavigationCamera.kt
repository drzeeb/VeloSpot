package de.velospot.core.navigation

/**
 * Tunables and pure helpers for the 3D navigation camera. Kept separate from the
 * MapLibre-bound [de.velospot.feature.map.presentation.NavigationManager] so the
 * camera behaviour can be unit tested and tweaked in one place.
 */
internal object NavigationCamera {

    /** Fixed downward tilt that gives the Google-Maps-style 3D perspective. */
    const val PITCH_DEGREES = 60.0

    /** Closest zoom — standing still or while a turn is imminent. */
    const val ZOOM_SLOW = 18.5

    /** Farthest zoom — cruising at speed so more of the road ahead is visible. */
    const val ZOOM_FAST = 17.0

    /** Speed (m/s) at or below which we use [ZOOM_SLOW] (~3.6 km/h, walking pace). */
    private const val SPEED_SLOW_MPS = 1.0

    /** Speed (m/s) at or above which we use [ZOOM_FAST] (~40 km/h). */
    private const val SPEED_FAST_MPS = 11.0

    /**
     * A turn at least this sharp (degrees) forces [ZOOM_SLOW] regardless of speed
     * so the rider can clearly see the manoeuvre.
     */
    private const val TURN_FORCE_SLOW_DEGREES = 45.0

    /** Exponential-smoothing time constants (seconds): bigger = smoother/slower. */
    const val TAU_POSITION_S = 0.22
    const val TAU_BEARING_S = 0.28
    const val TAU_ZOOM_S = 0.55
    const val TAU_PITCH_S = 0.65

    /**
     * Maps the current ground [speedMps] and the [turnSharpnessDegrees] of the
     * upcoming turn to a target zoom level. Linear ramp between
     * [SPEED_SLOW_MPS]/[ZOOM_SLOW] and [SPEED_FAST_MPS]/[ZOOM_FAST]; an imminent
     * sharp turn overrides to [ZOOM_SLOW].
     */
    fun targetZoom(speedMps: Float?, turnSharpnessDegrees: Double): Double {
        if (turnSharpnessDegrees >= TURN_FORCE_SLOW_DEGREES) return ZOOM_SLOW
        val speed = (speedMps ?: 0f).toDouble().coerceAtLeast(0.0)
        val f = ((speed - SPEED_SLOW_MPS) / (SPEED_FAST_MPS - SPEED_SLOW_MPS)).coerceIn(0.0, 1.0)
        return GeoMath.lerp(ZOOM_SLOW, ZOOM_FAST, f)
    }

    /**
     * Smoothing factor for a frame of duration [dtSeconds] given a time constant
     * [tauSeconds]: `1 - e^(-dt/tau)`. Frame-rate independent so the animation
     * looks identical at 60/90/120 Hz.
     */
    fun smoothingAlpha(dtSeconds: Double, tauSeconds: Double): Double {
        if (tauSeconds <= 0.0) return 1.0
        return (1.0 - Math.exp(-dtSeconds / tauSeconds)).coerceIn(0.0, 1.0)
    }
}

