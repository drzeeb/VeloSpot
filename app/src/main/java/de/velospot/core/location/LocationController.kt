package de.velospot.core.location

import de.velospot.domain.repository.LocationPowerProfile
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
 *  - [setRecordingStationary] — while recording, whether the rider has been standing
 *    still long enough to drop the GNSS engine to a power-saving cadence (battery
 *    win for traffic lights / café / ferry-train legs / paused recordings).
 *
 * Derived strategy:
 *  - **run** updates while the map is visible **or** a recording is active;
 *  - request [LocationPowerProfile.NAVIGATION_OR_MOVING] while navigating **or**
 *    recording-and-moving; [LocationPowerProfile.IDLE_RECORDING] while
 *    recording-but-standing-still; otherwise the battery-friendly
 *    [LocationPowerProfile.BROWSE] map mode.
 */
@Singleton
class LocationController @Inject constructor(
    private val repository: LocationRepository
) {
    private var mapVisible = false
    private var navigating = false
    private var recording = false
    /** While recording, `true` once the rider has stood still long enough to idle GPS. */
    private var recordingStationary = false

    private var appliedRun: Boolean? = null
    private var appliedProfile: LocationPowerProfile? = null

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
        // Every recording starts out moving; clear any leftover standstill so a new
        // ride opens at full accuracy and stopping releases the idle claim cleanly.
        if (!active) recordingStationary = false
        apply()
    }

    /**
     * While recording, declares whether the rider is currently **standing still**
     * long enough to idle the GPS ([stationary] = `true`) or is **moving** and needs
     * full-fidelity fixes ([stationary] = `false`). Ignored when not recording, and
     * always overridden by [setNavigating] (navigation forces high accuracy). Fed by
     * the debounced [de.velospot.core.tracking.StandstillDetector] from the recorder.
     */
    @Synchronized
    fun setRecordingStationary(stationary: Boolean) {
        recordingStationary = stationary
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
        // Navigation always forces high accuracy. A recording is high-accuracy while
        // moving and drops to the idle power-saving profile only once it has been
        // flagged stationary. Plain map browsing keeps today's balanced-power mode.
        val profile = when {
            navigating -> LocationPowerProfile.NAVIGATION_OR_MOVING
            recording && recordingStationary -> LocationPowerProfile.IDLE_RECORDING
            recording -> LocationPowerProfile.NAVIGATION_OR_MOVING
            else -> LocationPowerProfile.BROWSE
        }
        if (!force && run == appliedRun && profile == appliedProfile) return
        if (run) {
            repository.startLocationUpdates(profile)
        } else {
            repository.stopLocationUpdates()
        }
        appliedRun = run
        appliedProfile = profile
    }
}

