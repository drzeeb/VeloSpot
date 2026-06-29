package de.velospot.core.tracking

import de.velospot.domain.model.RecordedRide
import kotlin.math.roundToInt

/**
 * Estimates the calories a rider burned on a recorded ride from its physical work.
 *
 * This mirrors the model behind the **planned-route** calorie figure (which reads
 * BRouter's own computed energy): a cyclist's metabolic burn in kcal is
 * numerically ≈ the **mechanical work** done expressed in kJ. That holds because a
 * rider's efficiency is ~24 % and 1 kcal = 4.184 kJ, so
 * `metabolic_kJ = mechanical_kJ / 0.24 ≈ mechanical_kJ × 4.18` and
 * `metabolic_kcal = metabolic_kJ / 4.184 ≈ mechanical_kJ` — the well-known
 * "≈ 1 kJ of work ≈ 1 kcal burned" rule of thumb.
 *
 * The mechanical work is the sum of rolling resistance, aerodynamic drag and the
 * climb (potential energy):
 *
 * ```
 * W = C_rr·m·g·d  +  ½·ρ·CdA·v²·d  +  m·g·Δh⁺
 * ```
 *
 * The constants match the bundled BRouter **trekking** profile (90 kg rider + bike,
 * `C_r` 0.01, `S_C_x`/CdA 0.225) so the recorded-ride and planned-route figures
 * stay consistent. Speed uses the ride's **average moving speed**; only net ascent
 * (`Δh⁺`) costs energy — coasting downhill is treated as free, as for a casual
 * rider. Returns whole kcal (rounded), and `0` for an empty/zero-distance ride.
 */
fun estimateRideCalories(ride: RecordedRide): Int = estimateRideCalories(
    distanceMeters = ride.distanceMeters,
    movingSeconds = ride.movingSeconds,
    elevationGainMeters = ride.elevationGainMeters
)

/**
 * Physics-based calorie estimate from the raw figures. See [estimateRideCalories]
 * for the model. [totalMassKg] is the combined rider + bike mass.
 */
fun estimateRideCalories(
    distanceMeters: Double,
    movingSeconds: Long,
    elevationGainMeters: Double,
    totalMassKg: Double = DEFAULT_TOTAL_MASS_KG
): Int {
    if (distanceMeters <= 0.0) return 0
    val avgSpeedMps = if (movingSeconds > 0) distanceMeters / movingSeconds else 0.0
    val rolling  = ROLLING_RESISTANCE * totalMassKg * GRAVITY * distanceMeters
    val drag     = 0.5 * AIR_DENSITY * DRAG_AREA * avgSpeedMps * avgSpeedMps * distanceMeters
    val climbing = totalMassKg * GRAVITY * elevationGainMeters.coerceAtLeast(0.0)
    val workJoules = rolling + drag + climbing
    // kJ of mechanical work ≈ kcal of metabolic energy burned (see KDoc).
    return (workJoules / 1_000.0).roundToInt()
}

/** Rider + bike mass (kg). Matches the BRouter trekking profile's `totalMass`. */
private const val DEFAULT_TOTAL_MASS_KG = 90.0
/** Standard gravity (m/s²). */
private const val GRAVITY = 9.81
/** Rolling-resistance coefficient (BRouter `C_r`). */
private const val ROLLING_RESISTANCE = 0.01
/** Effective drag area CdA in m² (BRouter `S_C_x`). */
private const val DRAG_AREA = 0.225
/** Air density at ~15 °C, sea level (kg/m³). */
private const val AIR_DENSITY = 1.225

