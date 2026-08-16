package de.velospot.core.analysis

import de.velospot.core.tracking.ElevationAccumulator
import de.velospot.core.tracking.RideTracker
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import de.velospot.domain.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure [RideMerger] (no Android dependency). Cover the merge
 * plan §6: aggregation, chronological chaining, segment breaks, peak/avg/elevation
 * recomputation, bike-profile handling, edge cases and preview/merge agreement.
 */
class RideMergerTest {

    private fun ride(
        id: String,
        startedAt: Long,
        endedAt: Long,
        distanceMeters: Double = 1000.0,
        movingSeconds: Long = 100L,
        elapsedSeconds: Long = 120L,
        maxSpeedMps: Double = 10.0,
        elevationGainMeters: Double = 0.0,
        elevationLossMeters: Double = 0.0,
        points: List<TrackPoint> = emptyList(),
        name: String? = null,
        isMock: Boolean = false,
        bikeProfileId: String? = null,
        weather: WeatherSnapshot? = null
    ) = RecordedRide(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        distanceMeters = distanceMeters,
        elapsedSeconds = elapsedSeconds,
        movingSeconds = movingSeconds,
        avgSpeedMps = if (movingSeconds > 0) distanceMeters / movingSeconds else 0.0,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        points = points,
        name = name,
        isMock = isMock,
        bikeProfileId = bikeProfileId,
        weather = weather
    )

    private fun point(
        lat: Double,
        lon: Double,
        timestamp: Long,
        speedMps: Float? = null,
        altitudeMeters: Double? = null,
        segmentStart: Boolean = false
    ) = TrackPoint(
        latitude = lat,
        longitude = lon,
        timestamp = timestamp,
        speedMps = speedMps,
        altitudeMeters = altitudeMeters,
        segmentStart = segmentStart
    )

    // 1. Two simple rides: distance/moving/elapsed summed, start = min, end = max.
    @Test
    fun `merges two rides by summing aggregates and spanning the time range`() {
        val a = ride("a", startedAt = 100L, endedAt = 200L, distanceMeters = 1500.0, movingSeconds = 60L, elapsedSeconds = 80L)
        val b = ride("b", startedAt = 300L, endedAt = 400L, distanceMeters = 2500.0, movingSeconds = 100L, elapsedSeconds = 130L)

        val merged = RideMerger.merge(listOf(a, b), newId = "m")

        assertEquals("m", merged.id)
        assertEquals(4000.0, merged.distanceMeters, 1e-9)
        assertEquals(160L, merged.movingSeconds)
        assertEquals(210L, merged.elapsedSeconds)
        assertEquals(100L, merged.startedAt)
        assertEquals(400L, merged.endedAt)
        assertNull(merged.sourceRouteId)
        assertNull(merged.archivedAt)
    }

    // 2. Unsorted input is chained chronologically by startedAt.
    @Test
    fun `chains rides chronologically regardless of input order`() {
        val early = ride("early", startedAt = 100L, endedAt = 200L, points = listOf(point(1.0, 1.0, 100L)))
        val mid = ride("mid", startedAt = 300L, endedAt = 400L, points = listOf(point(2.0, 2.0, 300L)))
        val late = ride("late", startedAt = 500L, endedAt = 600L, points = listOf(point(3.0, 3.0, 500L)))

        val merged = RideMerger.merge(listOf(late, early, mid), newId = "m")

        assertEquals(listOf(100L, 300L, 500L), merged.points.map { it.timestamp })
        assertEquals(100L, merged.startedAt)
        assertEquals(600L, merged.endedAt)
    }

    // 3. Segment breaks: first point of each following source flagged; inner flags kept.
    @Test
    fun `marks the first point of each following segment and preserves inner breaks`() {
        val a = ride(
            "a", startedAt = 100L, endedAt = 200L,
            points = listOf(
                point(1.0, 1.0, 100L),
                point(1.1, 1.1, 150L, segmentStart = true) // an inner pause inside ride A
            )
        )
        val b = ride(
            "b", startedAt = 300L, endedAt = 400L,
            points = listOf(
                point(2.0, 2.0, 300L),
                point(2.1, 2.1, 350L)
            )
        )

        val merged = RideMerger.merge(listOf(a, b), newId = "m")

        // Point 0: first of first ride, not a break.
        assertFalse(merged.points[0].segmentStart)
        // Point 1: preserved inner break of ride A.
        assertTrue(merged.points[1].segmentStart)
        // Point 2: first of ride B -> forced break (the inter-ride gap = a pause).
        assertTrue(merged.points[2].segmentStart)
        // Point 3: ordinary point.
        assertFalse(merged.points[3].segmentStart)
    }

    // 4. maxSpeedMps = max over source peaks and plausible point speeds.
    @Test
    fun `takes the max speed over source peaks and plausible point speeds`() {
        val a = ride(
            "a", startedAt = 100L, endedAt = 200L, maxSpeedMps = 12.0,
            points = listOf(point(1.0, 1.0, 100L, speedMps = 15.0f))
        )
        val b = ride(
            "b", startedAt = 300L, endedAt = 400L, maxSpeedMps = 8.0,
            // 1000 m/s is above the plausibility ceiling and must be ignored.
            points = listOf(point(2.0, 2.0, 300L, speedMps = 1000.0f))
        )

        val merged = RideMerger.merge(listOf(a, b), newId = "m")

        assertEquals(15.0, merged.maxSpeedMps, 1e-6)
        assertTrue(merged.maxSpeedMps <= RideTracker.MAX_PLAUSIBLE_SPEED_MPS)
    }

    // 5. Elevation recomputed over the stitched track (gap banks no phantom step).
    @Test
    fun `recomputes elevation over the stitched track with a segment break`() {
        val a = ride(
            "a", startedAt = 100L, endedAt = 200L,
            points = listOf(
                point(1.0, 1.0, 100L, altitudeMeters = 100.0),
                point(1.1, 1.1, 150L, altitudeMeters = 120.0)
            )
        )
        // A large altitude jump across the gap must NOT bank as gain (breakSegment).
        val b = ride(
            "b", startedAt = 300L, endedAt = 400L,
            points = listOf(
                point(2.0, 2.0, 300L, altitudeMeters = 500.0),
                point(2.1, 2.1, 350L, altitudeMeters = 510.0)
            )
        )

        val merged = RideMerger.merge(listOf(a, b), newId = "m")

        // Reference: the exact same integrator, fed with a segment break at B's start.
        val acc = ElevationAccumulator()
        acc.add(100.0); acc.add(120.0)
        acc.breakSegment()
        acc.add(500.0); acc.add(510.0)
        assertEquals(acc.gain, merged.elevationGainMeters, 1e-9)
        assertEquals(acc.loss, merged.elevationLossMeters, 1e-9)
        // The 380 m step across the gap did not become gain.
        assertTrue(merged.elevationGainMeters < 380.0)
    }

    // 5b. No altitudes anywhere -> fall back to summed source values.
    @Test
    fun `falls back to summed elevation when no altitudes are present`() {
        val a = ride("a", startedAt = 100L, endedAt = 200L, elevationGainMeters = 30.0, elevationLossMeters = 10.0)
        val b = ride("b", startedAt = 300L, endedAt = 400L, elevationGainMeters = 20.0, elevationLossMeters = 5.0)

        val merged = RideMerger.merge(listOf(a, b), newId = "m")

        assertEquals(50.0, merged.elevationGainMeters, 1e-9)
        assertEquals(15.0, merged.elevationLossMeters, 1e-9)
    }

    // 6. avgSpeedMps = distance / movingSeconds (and 0 when movingSeconds == 0).
    @Test
    fun `computes average speed from distance over moving seconds`() {
        val a = ride("a", startedAt = 100L, endedAt = 200L, distanceMeters = 1000.0, movingSeconds = 100L)
        val b = ride("b", startedAt = 300L, endedAt = 400L, distanceMeters = 1000.0, movingSeconds = 100L)

        val merged = RideMerger.merge(listOf(a, b), newId = "m")

        assertEquals(2000.0 / 200.0, merged.avgSpeedMps, 1e-9)
    }

    @Test
    fun `average speed is zero when nothing moved`() {
        val a = ride("a", startedAt = 100L, endedAt = 200L, distanceMeters = 0.0, movingSeconds = 0L)
        val b = ride("b", startedAt = 300L, endedAt = 400L, distanceMeters = 0.0, movingSeconds = 0L)

        val merged = RideMerger.merge(listOf(a, b), newId = "m")

        assertEquals(0.0, merged.avgSpeedMps, 0.0)
    }

    // 7. bikeProfileId: identical -> kept; different -> null.
    @Test
    fun `keeps the bike profile when all sources share it`() {
        val a = ride("a", startedAt = 100L, endedAt = 200L, bikeProfileId = "gravel")
        val b = ride("b", startedAt = 300L, endedAt = 400L, bikeProfileId = "gravel")

        val merged = RideMerger.merge(listOf(a, b), newId = "m")

        assertEquals("gravel", merged.bikeProfileId)
    }

    @Test
    fun `clears the bike profile when sources differ`() {
        val a = ride("a", startedAt = 100L, endedAt = 200L, bikeProfileId = "gravel")
        val b = ride("b", startedAt = 300L, endedAt = 400L, bikeProfileId = "road")

        val merged = RideMerger.merge(listOf(a, b), newId = "m")

        assertNull(merged.bikeProfileId)
    }

    @Test
    fun `clears the bike profile when only some sources are tagged`() {
        val a = ride("a", startedAt = 100L, endedAt = 200L, bikeProfileId = "gravel")
        val b = ride("b", startedAt = 300L, endedAt = 400L, bikeProfileId = null)

        val merged = RideMerger.merge(listOf(a, b), newId = "m")

        assertNull(merged.bikeProfileId)
    }

    // 8. Edge cases: <2 rides and mock/real mix are rejected.
    @Test
    fun `rejects a single ride`() {
        val a = ride("a", startedAt = 100L, endedAt = 200L)
        assertThrows(IllegalArgumentException::class.java) {
            RideMerger.merge(listOf(a), newId = "m")
        }
    }

    @Test
    fun `rejects an empty list`() {
        assertThrows(IllegalArgumentException::class.java) {
            RideMerger.merge(emptyList(), newId = "m")
        }
    }

    @Test
    fun `rejects mixing mock and real rides`() {
        val real = ride("a", startedAt = 100L, endedAt = 200L, isMock = false)
        val mock = ride("b", startedAt = 300L, endedAt = 400L, isMock = true)
        assertThrows(IllegalArgumentException::class.java) {
            RideMerger.merge(listOf(real, mock), newId = "m")
        }
        assertFalse(RideMerger.canMerge(listOf(real, mock)))
    }

    @Test
    fun `name defaults to the first ride's name and is overridable`() {
        val a = ride("a", startedAt = 100L, endedAt = 200L, name = "Morning commute")
        val b = ride("b", startedAt = 300L, endedAt = 400L, name = "Evening leg")

        assertEquals("Morning commute", RideMerger.merge(listOf(b, a), newId = "m").name)
        assertEquals("Full ride", RideMerger.merge(listOf(a, b), newId = "m", name = " Full ride ").name)
    }

    @Test
    fun `weather is the first available snapshot in chronological order`() {
        val weather = WeatherSnapshot(
            temperatureC = 12.0,
            apparentTemperatureC = null,
            humidityPct = null,
            precipitationMm = null,
            weatherCode = 1,
            windSpeedMps = 3.0,
            windDirectionDeg = null,
            observedAt = 100L,
            latitude = 1.0,
            longitude = 1.0
        )
        val a = ride("a", startedAt = 100L, endedAt = 200L, weather = null)
        val b = ride("b", startedAt = 300L, endedAt = 400L, weather = weather)

        val merged = RideMerger.merge(listOf(b, a), newId = "m")

        assertEquals(weather, merged.weather)
    }

    // 9. preview(...) yields the same aggregates as merge(...).
    @Test
    fun `preview agrees with merge on every aggregate`() {
        val a = ride(
            "a", startedAt = 100L, endedAt = 200L, distanceMeters = 1200.0,
            movingSeconds = 80L, elapsedSeconds = 100L, maxSpeedMps = 11.0,
            bikeProfileId = "gravel",
            points = listOf(
                point(1.0, 1.0, 100L, speedMps = 9.0f, altitudeMeters = 100.0),
                point(1.1, 1.1, 150L, altitudeMeters = 130.0)
            )
        )
        val b = ride(
            "b", startedAt = 300L, endedAt = 400L, distanceMeters = 800.0,
            movingSeconds = 40L, elapsedSeconds = 60L, maxSpeedMps = 7.0,
            bikeProfileId = "road",
            points = listOf(
                point(2.0, 2.0, 300L, altitudeMeters = 200.0),
                point(2.1, 2.1, 350L, altitudeMeters = 180.0)
            )
        )

        val merged = RideMerger.merge(listOf(a, b), newId = "m")
        val preview = RideMerger.preview(listOf(a, b))

        assertEquals(merged.distanceMeters, preview.distanceMeters, 1e-9)
        assertEquals(merged.movingSeconds, preview.movingSeconds)
        assertEquals(merged.elapsedSeconds, preview.elapsedSeconds)
        assertEquals(merged.elevationGainMeters, preview.elevationGainMeters, 1e-9)
        assertEquals(merged.elevationLossMeters, preview.elevationLossMeters, 1e-9)
        assertEquals(merged.startedAt, preview.startedAt)
        assertEquals(merged.endedAt, preview.endedAt)
        assertEquals(2, preview.rideCount)
        assertEquals(2, preview.segmentCount)
        assertEquals(listOf("gravel", "road"), preview.bikeProfileIds)
        assertFalse(preview.sharesSingleProfile)
    }
}


