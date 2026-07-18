package de.velospot.domain.repository

import de.velospot.core.map.LayerVisibility
import de.velospot.core.map.MapLayerCategory
import de.velospot.core.map.RideViewOptions
import kotlinx.coroutines.flow.Flow

/**
 * Reactive store for the map screen's user **settings** (UI toggles), backed by
 * Jetpack DataStore instead of blocking `SharedPreferences` reads.
 *
 * Every value is exposed as a [Flow] so the UI updates automatically and no read
 * ever touches disk on the main thread; writes are `suspend` and transactional.
 * Injecting this (rather than reading `SharedPreferences` statics with a
 * `Context`) also keeps the `MapViewModel` free of Android storage details and
 * makes it trivially testable with an in-memory fake.
 */
interface MapSettingsRepository {

    /** Which map pin categories / overlays are currently shown. */
    val layerVisibility: Flow<LayerVisibility>

    /** Whether navigation uses the tilted 3D camera (`true`) or the flat 2D view. */
    val is3DNavigation: Flow<Boolean>

    /** Whether spoken turn-by-turn voice guidance (TTS) is enabled. */
    val voiceGuidanceEnabled: Flow<Boolean>

    /** Whether the display is kept awake during a follow session. */
    val keepScreenOnEnabled: Flow<Boolean>

    /**
     * Whether the screen orientation is locked to portrait. Defaults to `false`
     * (the app follows the device's auto-rotate). When enabled the map screen
     * stays in portrait so the display does not rotate while cycling.
     */
    val portraitLockEnabled: Flow<Boolean>

    /** The rider's persisted "inspect a past ride" overlay choices. */
    val rideViewOptions: Flow<RideViewOptions>

    suspend fun setLayerVisible(category: MapLayerCategory, visible: Boolean)
    suspend fun set3DNavigation(enabled: Boolean)
    suspend fun setVoiceGuidance(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setPortraitLock(enabled: Boolean)
    suspend fun setShowMaxSpeedBubble(enabled: Boolean)
    suspend fun setColorTrackBySpeed(enabled: Boolean)
}

