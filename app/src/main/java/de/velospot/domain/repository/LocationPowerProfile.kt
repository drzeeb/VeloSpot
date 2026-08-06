package de.velospot.domain.repository

/**
 * The GPS-radio power profile requested from the [LocationRepository].
 *
 * This replaces the earlier single `highAccuracy: Boolean` so **three** distinct
 * states are representable — the middle one is the battery win for long tours:
 *
 *  - [NAVIGATION_OR_MOVING] — precise, frequent GPS fixes. Used during active
 *    turn-by-turn navigation **and** while a ride is being recorded and the rider
 *    is actually moving. This is the old `highAccuracy = true` request and its
 *    track fidelity must never be reduced while moving.
 *  - [IDLE_RECORDING] — the rider is recording but has been standing still for a
 *    sustained period (traffic light, café stop, ferry/train leg, or the ride is
 *    paused). The GNSS engine is dropped to a balanced-power request with a longer
 *    interval and a small min-displacement so the chip can idle and the battery
 *    lasts the whole tour. Restored to [NAVIGATION_OR_MOVING] on the first moving
 *    fix. The min-displacement filter (and the paused tracker) suppress any
 *    phantom distance.
 *  - [BROWSE] — the map is in the foreground but nothing is recording/navigating.
 *    This is the old `highAccuracy = false` request, kept **identical** to today.
 */
enum class LocationPowerProfile {
    NAVIGATION_OR_MOVING,
    IDLE_RECORDING,
    BROWSE,
}

