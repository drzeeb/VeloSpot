package de.velospot.core.location

import de.velospot.domain.repository.LocationRepository
import kotlinx.coroutines.flow.Flow
import de.velospot.domain.model.GeoCoordinate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The **single owner** of the GPS radio's power state.
 *
 * Previously the location power mode was steered from two places — the map
 * `ViewModel` (foreground / accuracy) and the `RideRecordingManager` (background
 * recording) — which had to coordinate through a callback. This controller
 * replaces that with one source of truth: each feature declares its *need* for
 * location, and the controller derives whether the radio should run and at what
 * accuracy.
 *
 * Needs (any can be toggled independently, from any thread):
 *  - [setMapVisible] — the map screen is in the foreground (wants position updates).
 *  - [setNavigating] — turn-by-turn navigation is active (wants frequent fixes).
 *  - [setRecording]  — a ride is being recorded (wants frequent fixes; keeps the
 *    radio alive even with the map backgrounded, via the foreground service).
 *
 * Derived strategy:
 *  - **run** updates while the map is visible **or** a recording is active;
 *  - use **high accuracy** while navigating **or** recording, otherwise the
 *    battery-friendly balanced-power mode.
 */
@Singleton
class LocationController @Inject constructor(
    private val repository: LocationRepository
) {
    private var mapVisible = false
    private var navigating = false
    private var recording = false

    private var appliedRun: Boolean? = null
    private var appliedHigh: Boolean? = null

    /** The shared live-location flow (pass-through to the underlying repository). */
    fun locationFlow(): Flow<GeoCoordinate?> = repository.getCurrentLocationFlow()

    /** The map screen entered (`true`) or left (`false`) the foreground. */
    @Synchronized
    fun setMapVisible(visible: Boolean) {
        mapVisible = visible
        apply()
    }

    /** Turn-by-turn navigation started (`true`) or ended (`false`). */
    @Synchronized
    fun setNavigating(active: Boolean) {
        navigating = active
        apply()
    }

    /** A ride recording started (`true`) or ended (`false`). */
    @Synchronized
    fun setRecording(active: Boolean) {
        recording = active
        apply()
    }

    /**
     * Forces the strategy to be re-applied even if no need changed — used after the
     * location permission is granted, where the earlier `start` was a no-op.
     */
    @Synchronized
    fun refresh() = apply(force = true)

    private fun apply(force: Boolean = false) {
        val run = mapVisible || recording
        val high = navigating || recording
        if (!force && run == appliedRun && high == appliedHigh) return
        if (run) {
            repository.startLocationUpdates(highAccuracy = high)
        } else {
            repository.stopLocationUpdates()
        }
        appliedRun = run
        appliedHigh = high
    }
}

