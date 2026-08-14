package de.velospot.core.parking

import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Android-free [ParkingScorer] ranking calculator.
 *
 * All scores are asserted against the deterministic point model documented in
 * [ParkingScorer]; the freshness reference year is injected so the DATA_FRESH
 * boundary is stable regardless of the wall clock.
 */
class ParkingScorerTest {

    /** Reference year injected into every freshness-sensitive assertion. */
    private val year = 2025

    /**
     * Builds a [BikeParkingSpace] with only the enrichment fields under test set;
     * everything else stays `null`/unknown so tests isolate one behaviour at a time.
     */
    private fun space(
        type: BikeParkingType = BikeParkingType.UNKNOWN,
        parkingSubtype: String? = null,
        isCovered: Boolean? = null,
        indoor: Boolean? = null,
        surveillance: Boolean? = null,
        lit: Boolean? = null,
        supervised: Boolean? = null,
        access: String? = null,
        fee: Boolean? = null,
        openingHours: String? = null,
        capacity: Int? = null,
        chargingCapacity: Int? = null,
        cargoBike: Boolean? = null,
        checkDate: String? = null,
    ): BikeParkingSpace = BikeParkingSpace(
        id = "test",
        latitude = 0.0,
        longitude = 0.0,
        type = type,
        capacity = capacity,
        name = null,
        address = null,
        isCovered = isCovered,
        imageUrl = null,
        operator = null,
        sourceLayer = "bike_parking",
        access = access,
        fee = fee,
        lit = lit,
        surveillance = surveillance,
        supervised = supervised,
        cargoBike = cargoBike,
        chargingCapacity = chargingCapacity,
        indoor = indoor,
        openingHours = openingHours,
        checkDate = checkDate,
        parkingSubtype = parkingSubtype,
    )

    // ── Sparse / unknown ────────────────────────────────────────────────────────

    @Test
    fun sparseUnknownSpot_scoresLowButNonNegative_tierBasic_andNoFactorSubtracts() {
        // A totally untagged node: the UNKNOWN theft baseline (12) plus the
        // "access defaults to public" credit (8) fire, nothing subtracts.
        val result = ParkingScorer.compute(space(), year)

        // Low but never negative, and never punished for missing data.
        assertTrue("score must be >= 0", result.value >= 0)
        assertEquals("UNKNOWN theft baseline (12) + default public-access credit (8)", 20, result.value)
        assertEquals(ParkingTier.BASIC, result.tier)
        // A null attribute must never produce a subtracting factor.
        assertTrue(
            "no factor may subtract for an all-null spot",
            result.factors.none { it.points < 0 || !it.positive }
        )
    }

    // ── Theft fallback to BikeParkingType (null/unknown subtype) ─────────────────

    @Test
    fun theftFallback_bikeRackType_nullSubtype_grants24_tierDecent() {
        // A bare lockable stand: BIKE_RACK + null subtype → theft baseline 24.
        // Total = theft 24 + default public access 8 = 32, tier DECENT.
        val result = ParkingScorer.compute(space(type = BikeParkingType.BIKE_RACK), year)
        assertEquals(32, result.value)
        assertEquals(ParkingTier.DECENT, result.tier)
        assertEquals(24, categoryPoints(result, ScoreFactorKey.THEFT_PROTECTION))
        assertTrue(
            "the fallback baseline still emits a positive THEFT factor",
            result.factors.any { it.key == ScoreFactorKey.THEFT_PROTECTION && it.points == 24 && it.positive }
        )
    }

    @Test
    fun theftFallback_unknownType_nullSubtype_grants12() {
        assertEquals(12, theftFactor(space(type = BikeParkingType.UNKNOWN)))
    }

    @Test
    fun theftFallback_garageType_nullSubtype_grants40() {
        assertEquals(40, theftFactor(space(type = BikeParkingType.GARAGE)))
    }

    @Test
    fun theftFallback_bikeRackType_withStandsSubtype_stillGrants24() {
        // Known subtype path is unchanged even when a fallback type is present.
        assertEquals(24, theftFactor(space(type = BikeParkingType.BIKE_RACK, parkingSubtype = "stands")))
    }


    // ── Maximal / clamped ───────────────────────────────────────────────────────

    @Test
    fun maximallySecureSpot_clampsTo100_andTierPremium() {
        val result = ParkingScorer.compute(
            space(
                type = BikeParkingType.GARAGE,
                parkingSubtype = "lockers",
                isCovered = true,
                indoor = true,
                surveillance = true,
                lit = true,
                supervised = true,
                access = "yes",
                fee = false,
                openingHours = "24/7",
                capacity = 200,
                chargingCapacity = 4,
                checkDate = "2024-05-01",
            ),
            year,
        )

        assertEquals(100, result.value)
        assertEquals(ParkingTier.PREMIUM, result.tier)
    }

    // ── Theft-protection ladder ─────────────────────────────────────────────────

    @Test
    fun theftLadder_lockersShedBuildingGarageSubtype_grants40() {
        for (subtype in listOf("lockers", "shed", "building", "garage")) {
            val theft = theftFactor(space(parkingSubtype = subtype))
            assertEquals("subtype '$subtype' → 40", 40, theft)
        }
    }

    @Test
    fun theftLadder_garageType_grants40() {
        assertEquals(40, theftFactor(space(type = BikeParkingType.GARAGE)))
    }

    @Test
    fun theftLadder_twoTier_grants28() {
        assertEquals(28, theftFactor(space(parkingSubtype = "two-tier")))
    }

    @Test
    fun theftLadder_frameLockableSubtypes_grant24() {
        for (subtype in listOf("stands", "anchors", "wall_loops", "bollard", "ground_slots")) {
            assertEquals("subtype '$subtype' → 24", 24, theftFactor(space(parkingSubtype = subtype)))
        }
    }

    @Test
    fun theftLadder_leanToAndInformal_grant8() {
        assertEquals(8, theftFactor(space(parkingSubtype = "lean_to")))
        assertEquals(8, theftFactor(space(parkingSubtype = "informal")))
    }

    @Test
    fun theftLadder_supervisedAdds12_butCategoryNeverExceedsMaxTheft() {
        // stands(24) + supervised(12) = 36 (under the 40 cap): both factors present.
        val moderate = ParkingScorer.compute(space(parkingSubtype = "stands", supervised = true), year)
        assertEquals(
            36,
            categoryPoints(moderate, ScoreFactorKey.THEFT_PROTECTION) +
                categoryPoints(moderate, ScoreFactorKey.SUPERVISED),
        )

        // garage(40) + supervised(12) = 52 → theft category clamps at MAX_THEFT (40).
        // Isolate the theft contribution by removing the default public-access credit.
        val capped = ParkingScorer.compute(
            space(type = BikeParkingType.GARAGE, supervised = true, access = "private"),
            year,
        )
        assertEquals("theft category must clamp at MAX_THEFT", ParkingScorer.MAX_THEFT, capped.value)
    }

    // ── Category caps & floor ───────────────────────────────────────────────────

    @Test
    fun accessNo_floorsAccessCategoryAtZero_scoreNeverNegative() {
        val result = ParkingScorer.compute(space(access = "no"), year)
        // The 'no' penalty floors the *access* category at 0; the overall score is
        // still the UNKNOWN theft baseline (12), never negative.
        assertEquals("the 'no' penalty floors the access category, not the overall score", 12, result.value)
        assertTrue(result.value >= 0)
        assertEquals(ParkingTier.BASIC, result.tier)
    }

    // ── Data-freshness boundary ─────────────────────────────────────────────────

    @Test
    fun checkDate_withinTwoYears_grantsDataFresh() {
        for (fresh in listOf("2025", "2024-06-01", "2023-01-01")) {
            val result = ParkingScorer.compute(space(access = "private", checkDate = fresh), year)
            // UNKNOWN theft baseline (12) + DATA_FRESH (2).
            assertEquals("checkDate '$fresh' is fresh → 2 extra points", 14, result.value)
            assertTrue(result.factors.any { it.key == ScoreFactorKey.DATA_FRESH })
        }
    }

    @Test
    fun checkDate_olderThanTwoYearsOrUnparseableOrFuture_grantsNothing() {
        for (stale in listOf("2022-12-31", "2010", "not-a-date", "2030")) {
            val result = ParkingScorer.compute(space(access = "private", checkDate = stale), year)
            // Only the UNKNOWN theft baseline (12) remains; no DATA_FRESH credit.
            assertEquals("checkDate '$stale' is not fresh → 0 points", 12, result.value)
            assertFalse(result.factors.any { it.key == ScoreFactorKey.DATA_FRESH })
        }
    }

    // ── Tier thresholds ─────────────────────────────────────────────────────────

    @Test
    fun tierThresholds_boundaries() {
        // 24 → BASIC (just under 25); 28 → DECENT (just over 25).
        assertScore(24, ParkingTier.BASIC, space(parkingSubtype = "stands", access = "private"))
        assertScore(28, ParkingTier.DECENT, space(parkingSubtype = "two-tier", access = "private"))

        // 44 → DECENT (just under 45); 46 → GOOD (just over 45).
        assertScore(
            44, ParkingTier.DECENT,
            space(type = BikeParkingType.GARAGE, access = "private", capacity = 1, chargingCapacity = 3),
        )
        assertScore(
            46, ParkingTier.GOOD,
            space(
                type = BikeParkingType.GARAGE, access = "private", capacity = 1,
                chargingCapacity = 3, checkDate = "2024",
            ),
        )

        // 64 → GOOD (just under 65); 66 → SECURE (just over 65).
        assertScore(
            64, ParkingTier.GOOD,
            space(type = BikeParkingType.GARAGE, access = "private", isCovered = true, capacity = 20),
        )
        assertScore(
            66, ParkingTier.SECURE,
            space(type = BikeParkingType.GARAGE, access = "private", isCovered = true, capacity = 50),
        )

        // 84 → SECURE (just under 85); 88 → PREMIUM (just over 85).
        assertScore(
            84, ParkingTier.SECURE,
            space(
                type = BikeParkingType.GARAGE, access = "private", isCovered = true,
                surveillance = true, lit = true, capacity = 50, chargingCapacity = 4, checkDate = "2024",
            ),
        )
        assertScore(
            88, ParkingTier.PREMIUM,
            space(
                type = BikeParkingType.GARAGE, access = "customers", isCovered = true,
                surveillance = true, lit = true, capacity = 50, chargingCapacity = 4, checkDate = "2024",
            ),
        )
    }

    // ── topPositiveFactors ──────────────────────────────────────────────────────

    @Test
    fun topPositiveFactors_returnsOnlyPositive_orderedByPointsDesc_respectingLimit() {
        val score = ParkingScorer.compute(
            space(
                type = BikeParkingType.GARAGE,
                parkingSubtype = "lockers",
                isCovered = true,
                surveillance = true,
                lit = true,
                supervised = true,
                access = "yes",
                fee = false,
                openingHours = "24/7",
                capacity = 200,
                chargingCapacity = 4,
                checkDate = "2024-05-01",
            ),
            year,
        )

        // Highest three positive contributions: THEFT(40) > WEATHER(18) > SUPERVISED(12).
        assertEquals(
            listOf(
                ScoreFactorKey.THEFT_PROTECTION,
                ScoreFactorKey.WEATHER_PROTECTION,
                ScoreFactorKey.SUPERVISED,
            ),
            ParkingScorer.topPositiveFactors(score, limit = 3),
        )

        // limit is honoured.
        assertEquals(1, ParkingScorer.topPositiveFactors(score, limit = 1).size)
    }

    @Test
    fun topPositiveFactors_excludesNegativeAndNonPositiveFactors() {
        // access "no" (-6) and fee true (+2 but positive=false) must be excluded.
        val score = ParkingScorer.compute(
            space(parkingSubtype = "stands", access = "no", fee = true),
            year,
        )
        val keys = ParkingScorer.topPositiveFactors(score, limit = 10)
        assertFalse("negative access factor is excluded", keys.contains(ScoreFactorKey.PUBLIC_ACCESS))
        assertFalse("non-positive fee factor is excluded", keys.contains(ScoreFactorKey.FREE))
        assertTrue("the positive theft factor survives", keys.contains(ScoreFactorKey.THEFT_PROTECTION))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun assertScore(expected: Int, tier: ParkingTier, space: BikeParkingSpace) {
        val result = ParkingScorer.compute(space, year)
        assertEquals("value for $space", expected, result.value)
        assertEquals("tier for $space", tier, result.tier)
    }

    /** Sum of the (single) THEFT_PROTECTION factor's points, or 0 if absent. */
    private fun theftFactor(space: BikeParkingSpace): Int =
        categoryPoints(ParkingScorer.compute(space, year), ScoreFactorKey.THEFT_PROTECTION)

    private fun categoryPoints(score: ParkingScore, key: ScoreFactorKey): Int =
        score.factors.filter { it.key == key }.sumOf { it.points }
}

