package de.velospot.core.parking

import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType

/**
 * Pure, Android-free "VeloScore" ranking calculator for a [BikeParkingSpace].
 *
 * The model is **additive with a floor of 0**: every category contributes a
 * non-negative number of points (a single "no access" penalty may pull the
 * accessibility category down, but the category itself is floored at 0), the
 * per-category totals are capped at their maximum, and the grand total is
 * clamped into `0..100`.
 *
 * Crucially, an *unknown* (`null`) attribute NEVER reduces the score — it simply
 * contributes 0 points. That means a sparsely tagged spot scores low but is not
 * punished, while a richly tagged, secure spot scores high.
 *
 * The result is display-agnostic: [ScoreFactor]s reference a stable
 * [ScoreFactorKey] that the UI layer maps to a localised string, keeping this
 * object free of Android string resources.
 */
object ParkingScorer {

    /** Maximum points each category can contribute. Sum == 100. */
    const val MAX_THEFT = 40
    const val MAX_WEATHER = 18
    const val MAX_WATCH = 14
    const val MAX_ACCESS = 16
    const val MAX_CAPACITY = 8
    const val MAX_EXTRAS = 4

    /** Tier thresholds (upper-exclusive except PREMIUM). */
    private const val TIER_DECENT_MIN = 25
    private const val TIER_GOOD_MIN = 45
    private const val TIER_SECURE_MIN = 65
    private const val TIER_PREMIUM_MIN = 85

    /** A [checkDate] is considered "fresh" when within this many years of `now`. */
    private const val FRESH_WITHIN_YEARS = 2

    /** Default reference year used when the caller does not supply one. */
    const val DEFAULT_CURRENT_YEAR = 2025

    /**
     * Computes the [ParkingScore] for [space].
     *
     * @param currentYear reference year for the data-freshness check; injectable
     *   so unit tests are deterministic.
     */
    fun compute(space: BikeParkingSpace, currentYear: Int = DEFAULT_CURRENT_YEAR): ParkingScore {
        val factors = mutableListOf<ScoreFactor>()

        val theft = theftPoints(space, factors)
        val weather = weatherPoints(space, factors)
        val watch = watchPoints(space, factors)
        val access = accessPoints(space, factors)
        val capacity = capacityPoints(space, factors)
        val extras = extrasPoints(space, currentYear, factors)

        val total = (theft + weather + watch + access + capacity + extras).coerceIn(0, 100)
        return ParkingScore(
            value = total,
            tier = tierFor(total),
            factors = factors.toList()
        )
    }

    /**
     * The top positive [ScoreFactorKey]s (highest point contribution first), used
     * by the UI to render a short "why is this secure" summary such as
     * "covered · surveilled · lockable".
     */
    fun topPositiveFactors(score: ParkingScore, limit: Int = 3): List<ScoreFactorKey> =
        score.factors
            .filter { it.positive && it.points > 0 }
            .sortedByDescending { it.points }
            .take(limit)
            .map { it.key }

    // ── Categories ─────────────────────────────────────────────────────────────

    private fun theftPoints(space: BikeParkingSpace, factors: MutableList<ScoreFactor>): Int {
        val subtype = space.parkingSubtype?.trim()?.lowercase()
        var points = when {
            space.type == BikeParkingType.GARAGE ||
                subtype in setOf("lockers", "shed", "building", "garage") -> 40
            subtype == "two-tier" -> 28
            subtype in setOf("stands", "anchors", "wall_loops", "bollard", "ground_slots") -> 24
            subtype in setOf("lean_to", "informal") -> 8
            else -> 0
        }
        if (points > 0) {
            factors += ScoreFactor(ScoreFactorKey.THEFT_PROTECTION, points, positive = true)
        }
        if (space.supervised == true) {
            points += 12
            factors += ScoreFactor(ScoreFactorKey.SUPERVISED, 12, positive = true)
        }
        return points.coerceIn(0, MAX_THEFT)
    }

    private fun weatherPoints(space: BikeParkingSpace, factors: MutableList<ScoreFactor>): Int {
        return if (space.isCovered == true || space.indoor == true) {
            factors += ScoreFactor(ScoreFactorKey.WEATHER_PROTECTION, MAX_WEATHER, positive = true)
            MAX_WEATHER
        } else 0
    }

    private fun watchPoints(space: BikeParkingSpace, factors: MutableList<ScoreFactor>): Int {
        var points = 0
        if (space.surveillance == true) {
            points += 9
            factors += ScoreFactor(ScoreFactorKey.SURVEILLANCE, 9, positive = true)
        }
        if (space.lit == true) {
            points += 5
            factors += ScoreFactor(ScoreFactorKey.LIGHTING, 5, positive = true)
        }
        return points.coerceIn(0, MAX_WATCH)
    }

    private fun accessPoints(space: BikeParkingSpace, factors: MutableList<ScoreFactor>): Int {
        var points = 0
        when (space.access?.trim()?.lowercase()) {
            null, "yes", "permissive", "designated" -> {
                points += 8
                factors += ScoreFactor(ScoreFactorKey.PUBLIC_ACCESS, 8, positive = true)
            }
            "customers" -> {
                points += 4
                factors += ScoreFactor(ScoreFactorKey.PUBLIC_ACCESS, 4, positive = true)
            }
            "private" -> Unit // 0
            "no" -> {
                points -= 6
                factors += ScoreFactor(ScoreFactorKey.PUBLIC_ACCESS, -6, positive = false)
            }
            else -> Unit
        }
        when (space.fee) {
            false -> {
                points += 5
                factors += ScoreFactor(ScoreFactorKey.FREE, 5, positive = true)
            }
            true -> {
                points += 2
                factors += ScoreFactor(ScoreFactorKey.FREE, 2, positive = false)
            }
            null -> Unit
        }
        if (space.openingHours != null) {
            points += 3
            factors += ScoreFactor(ScoreFactorKey.OPENING_HOURS, 3, positive = true)
        }
        return points.coerceIn(0, MAX_ACCESS)
    }

    private fun capacityPoints(space: BikeParkingSpace, factors: MutableList<ScoreFactor>): Int {
        val capacity = space.capacity ?: return 0
        val points = when {
            capacity >= 50 -> 8
            capacity >= 20 -> 6
            capacity >= 8 -> 4
            capacity >= 1 -> 2
            else -> 0
        }
        if (points > 0) {
            factors += ScoreFactor(ScoreFactorKey.CAPACITY, points, positive = true)
        }
        return points.coerceIn(0, MAX_CAPACITY)
    }

    private fun extrasPoints(
        space: BikeParkingSpace,
        currentYear: Int,
        factors: MutableList<ScoreFactor>
    ): Int {
        var points = 0
        if (space.chargingCapacity != null || space.cargoBike == true) {
            points += 2
            val key = if (space.chargingCapacity != null) ScoreFactorKey.CHARGING else ScoreFactorKey.CARGO_BIKE
            factors += ScoreFactor(key, 2, positive = true)
        }
        if (isFresh(space.checkDate, currentYear)) {
            points += 2
            factors += ScoreFactor(ScoreFactorKey.DATA_FRESH, 2, positive = true)
        }
        return points.coerceIn(0, MAX_EXTRAS)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun tierFor(value: Int): ParkingTier = when {
        value < TIER_DECENT_MIN -> ParkingTier.BASIC
        value < TIER_GOOD_MIN -> ParkingTier.DECENT
        value < TIER_SECURE_MIN -> ParkingTier.GOOD
        value < TIER_PREMIUM_MIN -> ParkingTier.SECURE
        else -> ParkingTier.PREMIUM
    }

    /**
     * True when [checkDate] carries a parseable year within [FRESH_WITHIN_YEARS]
     * of [currentYear]. Accepts ISO-ish values whose first 4 chars are a year
     * (e.g. "2024-05-01", "2023"). A future or unparseable value is not "fresh".
     */
    private fun isFresh(checkDate: String?, currentYear: Int): Boolean {
        val year = checkDate?.trim()?.take(4)?.toIntOrNull() ?: return false
        if (year > currentYear) return false
        return currentYear - year <= FRESH_WITHIN_YEARS
    }
}

/** Result of a [ParkingScorer.compute] run. */
data class ParkingScore(
    /** Overall quality/security score in `0..100`. */
    val value: Int,
    val tier: ParkingTier,
    val factors: List<ScoreFactor>
)

/** Coarse quality tier derived from the numeric [ParkingScore.value]. */
enum class ParkingTier { BASIC, DECENT, GOOD, SECURE, PREMIUM }

/**
 * A single, display-agnostic contribution to the score. [key] is a stable
 * identifier the UI maps to a localised label; [points] is the signed amount it
 * added (or subtracted); [positive] marks whether it improved the score.
 */
data class ScoreFactor(
    val key: ScoreFactorKey,
    val points: Int,
    val positive: Boolean
)

/** Stable keys the UI maps to localised strings — no Android resources here. */
enum class ScoreFactorKey {
    THEFT_PROTECTION,
    WEATHER_PROTECTION,
    SURVEILLANCE,
    LIGHTING,
    PUBLIC_ACCESS,
    FREE,
    OPENING_HOURS,
    CAPACITY,
    CARGO_BIKE,
    CHARGING,
    SUPERVISED,
    DATA_FRESH
}



