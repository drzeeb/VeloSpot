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
     * Whether the **Trip Computer HUD** overlay is shown on the map. Defaults to
     * `false` (hidden). When enabled a live stats overlay (speed, distance, time)
     * is drawn on top of the map while riding.
     */
    val hudEnabled: Flow<Boolean>

    /**
     * Whether the Trip Computer HUD was last shown in its **expanded** state
     * (`true`) or the **compact** state (`false`). Defaults to `false`. Remembers
     * the user's last choice so the HUD reopens in the same layout.
     */
    val hudExpanded: Flow<Boolean>

    /**
     * Whether the screen orientation is locked to portrait. Defaults to `false`
     * (the app follows the device's auto-rotate). When enabled the map screen
     * stays in portrait so the display does not rotate while cycling.
     */
    val portraitLockEnabled: Flow<Boolean>

    /**
     * Whether the 3D buildings are drawn with **rounded corners** (a MapLibre
     * `fill-extrusion` style property). Defaults to `false` (sharp corners). Purely
     * cosmetic — only affects the extruded-building look on the tilted 3D map.
     */
    val roundedBuildingsEnabled: Flow<Boolean>

    /**
     * Whether the **AMOLED** (pure-black) map style is used while dark mode is on.
     * Defaults to `false` (the regular dark style). Has no effect in light mode.
     * A true-black map lets OLED panels switch pixels off, saving battery at night.
     */
    val amoledEnabled: Flow<Boolean>

    /**
     * Whether the sunrise/sunset **"golden hour"** alert FAB may appear on the
     * map. Defaults to `true` (enabled). When disabled the alert FAB is never
     * shown, regardless of the current sun position.
     */
    val sunAlertEnabled: Flow<Boolean>

    /** The rider's persisted "inspect a past ride" overlay choices. */
    val rideViewOptions: Flow<RideViewOptions>

    /**
     * Whether finished rides are **automatically exported to Health Connect** right
     * after they are saved. Opt-in; defaults to `false`. Best-effort — a disabled
     * flag, an unavailable provider or missing permissions simply skip the export.
     */
    val healthConnectAutoExportEnabled: Flow<Boolean>

    /**
     * Whether the first-launch **welcome onboarding** has been completed (seen or
     * dismissed). Defaults to `false` so the 3-card welcome sheet is shown once on
     * the first start; it can be re-armed from the About sheet ("view the tour again").
     */
    val onboardingCompleted: Flow<Boolean>


    suspend fun setLayerVisible(category: MapLayerCategory, visible: Boolean)
    suspend fun set3DNavigation(enabled: Boolean)
    suspend fun setVoiceGuidance(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setHudEnabled(enabled: Boolean)
    suspend fun setHudExpanded(expanded: Boolean)
    suspend fun setPortraitLock(enabled: Boolean)
    suspend fun setRoundedBuildings(enabled: Boolean)
    suspend fun setAmoled(enabled: Boolean)
    suspend fun setSunAlertEnabled(enabled: Boolean)
    suspend fun setShowMaxSpeedBubble(enabled: Boolean)
    suspend fun setColorTrackBySpeed(enabled: Boolean)
    suspend fun setHealthConnectAutoExportEnabled(enabled: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
}

