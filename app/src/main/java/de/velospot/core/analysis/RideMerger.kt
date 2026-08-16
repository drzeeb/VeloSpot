package de.velospot.core.analysis

import de.velospot.core.tracking.ElevationAccumulator
import de.velospot.core.tracking.RideTracker
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint

/**
 * A non-persisted summary of what merging a set of rides *would* produce, shown in
 * the confirmation dialog before the user commits. Derived from the exact same
 * aggregation as [RideMerger.merge], so the preview never disagrees with the ride
 * that ends up saved.
 *
 * @property bikeProfileIds the distinct, non-null bike-profile ids across the
 *  sources. Size > 1 means the sources were recorded with **different** bikes, so
 *  the merged ride keeps **no** profile (see [sharesSingleProfile]); the UI lists
 *  these to make that clear.
 */
data class MergePreview(
    val rideCount: Int,
    val distanceMeters: Double,
    val movingSeconds: Long,
    val elapsedSeconds: Long,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val startedAt: Long,
    val endedAt: Long,
    val bikeProfileIds: List<String>,
    val isMock: Boolean
) {
    /** Wall-clock span from the first ride's start to the last ride's end. */
    val timeSpanMillis: Long get() = (endedAt - startedAt).coerceAtLeast(0L)

    /** Number of stitched pieces = number of source rides. */
    val segmentCount: Int get() = rideCount

    /** `true` when every source shares one (or no) bike profile → it is kept. */
    val sharesSingleProfile: Boolean get() = bikeProfileIds.size <= 1
}

/**
 * Pure, Android-free, JVM-testable logic that stitches several recorded rides back
 * into one — the recovery path for a recording bug that split a single real ride
 * into separate rides. Modelled on [RideRouteFactory]: a side-effect-free `object`
 * that only reads the given [RecordedRide]s and returns a new one, so persistence,
 * archiving and UI live entirely in the caller.
 *
 * ## How rides are combined (see the merge plan §2)
 *  - Sources are always chained **chronologically by `startedAt`**, independent of
 *    selection order, so the result is deterministic and correct for the bug case.
 *  - Tracks are concatenated; the **first point of every following source** is
 *    flagged [TrackPoint.segmentStart] so the time gap between two rides reads as a
 *    **pause/gap**: it is not drawn, not counted in distance/time, and exported as
 *    its own `<trkseg>`. Existing `segmentStart` flags within a source are kept.
 *  - `distanceMeters`, `movingSeconds`, `elapsedSeconds` are **summed** (the gaps
 *    are excluded, consistent with the pause model).
 *  - `avgSpeedMps` = distance / movingSeconds (0 when not moving).
 *  - `maxSpeedMps` = max over the sources' peaks and every plausible point speed
 *    (`0..`[RideTracker.MAX_PLAUSIBLE_SPEED_MPS]).
 *  - `elevationGain/Loss` are **recomputed** with [ElevationAccumulator] over the
 *    stitched track, breaking continuity at every segment start so a gap banks no
 *    phantom step. Falls back to the summed source values when no altitudes exist.
 *  - `bikeProfileId` is kept only when all sources share it, else `null`.
 *  - `sourceRouteId`/`archivedAt` are cleared; `weather` is the first available
 *    snapshot; a fresh `id` is assigned by the caller.
 */
object RideMerger {

    /** A merge needs at least this many rides to make sense. */
    const val MIN_RIDES = 2

    /**
     * Merges [rides] into a single [RecordedRide] identified by [newId]. When
     * [name] is non-blank it becomes the merged ride's name; otherwise the first
     * (chronologically earliest) source's name is used.
     *
     * @throws IllegalArgumentException when fewer than [MIN_RIDES] are given or the
     *  sources mix mock (simulator) and real rides.
     */
    fun merge(rides: List<RecordedRide>, newId: String, name: String? = null): RecordedRide {
        requireMergeable(rides)
        val sorted = rides.sortedBy { it.startedAt }

        val points = concatTracks(sorted)
        val agg = aggregate(sorted, points)

        val chosenName = name?.trim()?.takeIf { it.isNotBlank() }
            ?: sorted.firstOrNull()?.name?.trim()?.takeIf { it.isNotBlank() }

        val distinctProfiles = sorted.mapNotNull { it.bikeProfileId }.distinct()
        val bikeProfileId = distinctProfiles.singleOrNull()
            ?.takeIf { sorted.all { r -> r.bikeProfileId == it } }

        return RecordedRide(
            id = newId,
            startedAt = agg.startedAt,
            endedAt = agg.endedAt,
            distanceMeters = agg.distanceMeters,
            elapsedSeconds = agg.elapsedSeconds,
            movingSeconds = agg.movingSeconds,
            avgSpeedMps = agg.avgSpeedMps,
            maxSpeedMps = agg.maxSpeedMps,
            elevationGainMeters = agg.elevationGainMeters,
            elevationLossMeters = agg.elevationLossMeters,
            points = points,
            name = chosenName,
            isMock = sorted.first().isMock,
            archivedAt = null,
            // Kept only when every source shares the same profile.
            bikeProfileId = bikeProfileId,
            // The merged ride is not a single planned route.
            sourceRouteId = null,
            // First available weather snapshot in chronological order.
            weather = sorted.firstNotNullOfOrNull { it.weather }
        )
    }

    /**
     * Computes the confirmation-dialog [MergePreview] for [rides] without building
     * a full merged ride's worth of persisted state. Yields exactly the aggregates
     * [merge] would produce.
     *
     * @throws IllegalArgumentException on the same invalid input as [merge].
     */
    fun preview(rides: List<RecordedRide>): MergePreview {
        requireMergeable(rides)
        val sorted = rides.sortedBy { it.startedAt }
        val points = concatTracks(sorted)
        val agg = aggregate(sorted, points)
        return MergePreview(
            rideCount = sorted.size,
            distanceMeters = agg.distanceMeters,
            movingSeconds = agg.movingSeconds,
            elapsedSeconds = agg.elapsedSeconds,
            elevationGainMeters = agg.elevationGainMeters,
            elevationLossMeters = agg.elevationLossMeters,
            startedAt = agg.startedAt,
            endedAt = agg.endedAt,
            bikeProfileIds = sorted.mapNotNull { it.bikeProfileId }.distinct(),
            isMock = sorted.first().isMock
        )
    }

    /** Whether [rides] can be merged (≥ [MIN_RIDES], no mock/real mix). */
    fun canMerge(rides: List<RecordedRide>): Boolean =
        rides.size >= MIN_RIDES && rides.map { it.isMock }.distinct().size == 1

    private fun requireMergeable(rides: List<RecordedRide>) {
        require(rides.size >= MIN_RIDES) { "Merge needs at least $MIN_RIDES rides" }
        require(rides.map { it.isMock }.distinct().size == 1) {
            "Cannot merge mock and real rides"
        }
    }

    /**
     * Concatenates the sorted sources' tracks, flagging the first point of every
     * source after the first as a segment start (the gap between rides = a pause).
     * Points already flagged inside a source keep their flag.
     */
    private fun concatTracks(sorted: List<RecordedRide>): List<TrackPoint> {
        val out = ArrayList<TrackPoint>(sorted.sumOf { it.points.size })
        sorted.forEachIndexed { index, ride ->
            ride.points.forEachIndexed { pointIndex, point ->
                // The very first point of a following source opens a new segment.
                out += if (index > 0 && pointIndex == 0 && !point.segmentStart) {
                    point.copy(segmentStart = true)
                } else {
                    point
                }
            }
        }
        return out
    }

    private data class Aggregate(
        val startedAt: Long,
        val endedAt: Long,
        val distanceMeters: Double,
        val movingSeconds: Long,
        val elapsedSeconds: Long,
        val avgSpeedMps: Double,
        val maxSpeedMps: Double,
        val elevationGainMeters: Double,
        val elevationLossMeters: Double
    )

    private fun aggregate(sorted: List<RecordedRide>, points: List<TrackPoint>): Aggregate {
        val distanceMeters = sorted.sumOf { it.distanceMeters }
        val movingSeconds = sorted.sumOf { it.movingSeconds }
        val elapsedSeconds = sorted.sumOf { it.elapsedSeconds }
        val avgSpeedMps = if (movingSeconds > 0) distanceMeters / movingSeconds else 0.0

        // Peak is the max of the recorded per-ride peaks and every plausible sample.
        val speedCandidates = sorted.map { it.maxSpeedMps } +
            points.mapNotNull { it.speedMps?.toDouble() }
                .filter { it in 0.0..RideTracker.MAX_PLAUSIBLE_SPEED_MPS }
        val maxSpeedMps = speedCandidates.maxOrNull() ?: 0.0

        val elevation = computeElevation(points, sorted)

        return Aggregate(
            startedAt = sorted.minOf { it.startedAt },
            endedAt = sorted.maxOf { it.endedAt },
            distanceMeters = distanceMeters,
            movingSeconds = movingSeconds,
            elapsedSeconds = elapsedSeconds,
            avgSpeedMps = avgSpeedMps,
            maxSpeedMps = maxSpeedMps,
            elevationGainMeters = elevation.first,
            elevationLossMeters = elevation.second
        )
    }

    /**
     * Recomputes gain/loss over the stitched track with the shared integrator,
     * breaking continuity at every [TrackPoint.segmentStart] so the gap between two
     * merged rides banks no phantom step. Falls back to the summed source values
     * when the track carries no altitudes at all.
     */
    private fun computeElevation(
        points: List<TrackPoint>,
        sorted: List<RecordedRide>
    ): Pair<Double, Double> {
        if (points.none { it.altitudeMeters != null }) {
            return sorted.sumOf { it.elevationGainMeters } to
                sorted.sumOf { it.elevationLossMeters }
        }
        val acc = ElevationAccumulator()
        points.forEachIndexed { index, point ->
            if (index > 0 && point.segmentStart) acc.breakSegment()
            acc.add(point.altitudeMeters, point.accuracyMeters)
        }
        return acc.gain to acc.loss
    }
}

