package de.velospot.core.analysis

import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideElevation
import de.velospot.core.format.formatRideSpeed
import de.velospot.domain.model.RecordedRide
import java.util.Calendar

/**
 * The catalogue of badges a ride can earn. Each id is mapped to an icon, a
 * localised title and a colour in the presentation layer; the engine here stays
 * pure (no Android, no resources) so it is fully JVM-unit-testable.
 */
enum class AchievementId {
    // Per-ride milestones (tiered ones award only the highest reached).
    HALF_CENTURY,       // ≥ 50 km
    CENTURY,            // ≥ 100 km
    HILL_CLIMBER,       // ≥ 500 m ascent
    SUMMIT_SEEKER,      // ≥ 1000 m ascent
    KING_OF_THE_MOUNTAIN, // conquered a categorised climb
    SPEED_DEMON,        // hit ≥ 40 km/h
    ENDURANCE,          // ≥ 2 h moving
    CALORIE_CRUSHER,    // ≥ 1000 kcal
    EARLY_BIRD,         // started before 06:00
    NIGHT_OWL,          // started at/after 21:00

    // Personal records (this ride beats every other recorded ride).
    PR_DISTANCE,
    PR_CLIMBING,
    PR_PACE,
    PR_TOP_SPEED
}

/**
 * One earned badge: its [id], a short human value (e.g. `"112.30 km"`, `"Cat 2"`)
 * and whether it is a **personal record** (rendered with extra flair).
 */
data class Achievement(
    val id: AchievementId,
    val value: String?,
    val isPersonalRecord: Boolean = false
)

private const val HALF_CENTURY_METERS = 50_000.0
private const val CENTURY_METERS = 100_000.0
private const val HILL_CLIMBER_METERS = 500.0
private const val SUMMIT_SEEKER_METERS = 1_000.0
private const val SPEED_DEMON_MPS = 40.0 / 3.6
private const val ENDURANCE_SECONDS = 2 * 3_600L
private const val CALORIE_CRUSHER_KCAL = 1_000
private const val EARLY_BIRD_BEFORE_HOUR = 6
private const val NIGHT_OWL_FROM_HOUR = 21

/**
 * Evaluates every badge [ride] earns, given its [analysis] and the full ride list
 * [allRides] (used for the personal-record comparisons). Mock rides earn no badges
 * and are excluded from record comparisons. Returns the earned badges in display
 * order (milestones first, then personal records).
 */
fun evaluateAchievements(
    ride: RecordedRide,
    analysis: RideAnalysis,
    allRides: List<RecordedRide>
): List<Achievement> {
    if (ride.isMock) return emptyList()
    val out = ArrayList<Achievement>()

    // ── Distance milestone (highest reached) ─────────────────────────────────
    when {
        analysis.distanceMeters >= CENTURY_METERS ->
            out += Achievement(AchievementId.CENTURY, formatRideDistance(analysis.distanceMeters))
        analysis.distanceMeters >= HALF_CENTURY_METERS ->
            out += Achievement(AchievementId.HALF_CENTURY, formatRideDistance(analysis.distanceMeters))
    }

    // ── Climbing milestone (highest reached) ─────────────────────────────────
    when {
        analysis.elevationGainMeters >= SUMMIT_SEEKER_METERS ->
            out += Achievement(AchievementId.SUMMIT_SEEKER, formatRideElevation(analysis.elevationGainMeters))
        analysis.elevationGainMeters >= HILL_CLIMBER_METERS ->
            out += Achievement(AchievementId.HILL_CLIMBER, formatRideElevation(analysis.elevationGainMeters))
    }

    // ── King of the Mountain (hardest categorised climb) ─────────────────────
    val hardestClimb = analysis.climbs
        .filter { it.category != ClimbCategory.UNCATEGORIZED }
        .maxByOrNull { it.category.score }
    if (hardestClimb != null) {
        out += Achievement(AchievementId.KING_OF_THE_MOUNTAIN, categoryLabel(hardestClimb.category))
    }

    // ── Single-figure milestones ─────────────────────────────────────────────
    if (analysis.maxSpeedMps >= SPEED_DEMON_MPS) {
        out += Achievement(AchievementId.SPEED_DEMON, formatRideSpeed(analysis.maxSpeedMps))
    }
    if (analysis.movingSeconds >= ENDURANCE_SECONDS) {
        out += Achievement(AchievementId.ENDURANCE, formatRideDuration(analysis.movingSeconds))
    }
    if (analysis.caloriesKcal >= CALORIE_CRUSHER_KCAL) {
        out += Achievement(AchievementId.CALORIE_CRUSHER, "${analysis.caloriesKcal} kcal")
    }

    // ── Time of day ──────────────────────────────────────────────────────────
    val startHour = hourOfDay(ride.startedAt)
    when {
        startHour < EARLY_BIRD_BEFORE_HOUR -> out += Achievement(AchievementId.EARLY_BIRD, clockLabel(ride.startedAt))
        startHour >= NIGHT_OWL_FROM_HOUR -> out += Achievement(AchievementId.NIGHT_OWL, clockLabel(ride.startedAt))
    }

    // ── Personal records (vs. every other real ride) ─────────────────────────
    val others = allRides.filter { it.id != ride.id && !it.isMock }
    if (others.isNotEmpty()) {
        if (analysis.distanceMeters > others.maxOf { it.distanceMeters }) {
            out += Achievement(AchievementId.PR_DISTANCE, formatRideDistance(analysis.distanceMeters), isPersonalRecord = true)
        }
        if (analysis.elevationGainMeters > others.maxOf { it.elevationGainMeters }) {
            out += Achievement(AchievementId.PR_CLIMBING, formatRideElevation(analysis.elevationGainMeters), isPersonalRecord = true)
        }
        if (analysis.avgMovingSpeedMps > others.maxOf { it.avgSpeedMps }) {
            out += Achievement(AchievementId.PR_PACE, formatRideSpeed(analysis.avgMovingSpeedMps), isPersonalRecord = true)
        }
        if (analysis.maxSpeedMps > others.maxOf { it.maxSpeedMps }) {
            out += Achievement(AchievementId.PR_TOP_SPEED, formatRideSpeed(analysis.maxSpeedMps), isPersonalRecord = true)
        }
    }

    return out
}

private fun categoryLabel(category: ClimbCategory): String = when (category) {
    ClimbCategory.HC -> "HC"
    ClimbCategory.CAT1 -> "Cat 1"
    ClimbCategory.CAT2 -> "Cat 2"
    ClimbCategory.CAT3 -> "Cat 3"
    ClimbCategory.CAT4 -> "Cat 4"
    ClimbCategory.UNCATEGORIZED -> ""
}

private fun hourOfDay(epochMillis: Long): Int =
    Calendar.getInstance().apply { timeInMillis = epochMillis }.get(Calendar.HOUR_OF_DAY)

private fun clockLabel(epochMillis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

