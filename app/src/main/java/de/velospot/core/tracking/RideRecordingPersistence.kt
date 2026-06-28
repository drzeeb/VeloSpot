package de.velospot.core.tracking

import android.content.Context
import de.velospot.domain.model.RecordedRide
import de.velospot.domain.model.TrackPoint
import java.io.File
import java.util.UUID

/**
 * Crash-safe persistence for the **in-progress** ride recording.
 *
 * A `location`-typed foreground service keeps a recording alive while the app is
 * backgrounded, but the OS can still kill the process under memory pressure. The
 * [RideRecordingManager] is an in-memory singleton, so without this the partial
 * track would be lost on such a kill. This class streams every accepted fix to an
 * append-only file (plus a tiny running-aggregates meta file) as the ride is
 * recorded, so the next process start can [recover] the orphaned track into a
 * saved ride instead of dropping it.
 *
 * All file I/O is guarded — any failure degrades to a no-op rather than disturbing
 * the live recording. When [Context.getFilesDir] is unavailable (e.g. unit tests
 * with a bare mock context) every method is a no-op and [recover] returns `null`.
 *
 * Not internally synchronised: callers must serialise access (the manager funnels
 * every call through a single-consumer channel so begin → append → clear stay
 * ordered).
 */
internal class RideRecordingPersistence(context: Context) {

    private val dir: File? = context.filesDir?.let { File(it, DIR_NAME) }
    private val pointsFile: File? = dir?.let { File(it, POINTS_FILE) }
    private val metaFile: File? = dir?.let { File(it, META_FILE) }
    private val nameFile: File? = dir?.let { File(it, NAME_FILE) }

    /** Whether an (unfinished) recording session is currently persisted. */
    fun hasActiveSession(): Boolean = pointsFile?.exists() == true

    /** Starts a fresh session: (re)creates the dir and truncates the point stream. */
    fun begin(startedAt: Long) {
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            pointsFile?.writeText("")
            nameFile?.delete()
            writeMeta(startedAt, distanceMeters = 0.0, movingSeconds = 0, maxSpeedMps = 0.0,
                elevationGain = 0.0, elevationLoss = 0.0)
        }
    }

    /** Appends one accepted track point to the stream (one CSV line, flushed). */
    fun appendPoint(point: TrackPoint) {
        val f = pointsFile ?: return
        runCatching {
            f.appendText(encodePoint(point) + "\n")
        }
    }

    /** Rewrites the running aggregates + optional ride name (tiny files). */
    fun writeMeta(
        startedAt: Long,
        distanceMeters: Double,
        movingSeconds: Long,
        maxSpeedMps: Double,
        elevationGain: Double,
        elevationLoss: Double,
        name: String? = null
    ) {
        val f = metaFile ?: return
        runCatching {
            f.writeText(
                buildString {
                    appendLine("startedAt=$startedAt")
                    appendLine("distanceMeters=$distanceMeters")
                    appendLine("movingSeconds=$movingSeconds")
                    appendLine("maxSpeedMps=$maxSpeedMps")
                    appendLine("elevationGain=$elevationGain")
                    appendLine("elevationLoss=$elevationLoss")
                }
            )
            val trimmed = name?.trim()?.takeIf { it.isNotBlank() }
            if (trimmed != null) nameFile?.writeText(trimmed) else nameFile?.delete()
        }
    }

    /** Deletes the persisted session (called on a normal stop / discard / recovery). */
    fun clear() {
        val d = dir ?: return
        runCatching { d.deleteRecursively() }
    }

    /**
     * Rebuilds an orphaned session into a [RecordedRide], or `null` when there is
     * nothing to recover or the track is too short to be worth keeping. Does **not**
     * delete the files — the caller decides (and clears) after handling the result.
     */
    fun recover(): RecordedRide? {
        val pf = pointsFile ?: return null
        if (!pf.exists()) return null
        val points = runCatching {
            pf.readLines().mapNotNull { decodePoint(it) }
        }.getOrDefault(emptyList())
        if (points.size < MIN_POINTS) return null

        val meta = runCatching { metaFile?.readLines().orEmpty().toKeyValues() }
            .getOrDefault(emptyMap())
        val distance = meta["distanceMeters"]?.toDoubleOrNull() ?: 0.0
        if (distance < MIN_DISTANCE_METERS) return null

        val startedAt = meta["startedAt"]?.toLongOrNull() ?: points.first().timestamp
        val endedAt = points.last().timestamp
        val movingSeconds = meta["movingSeconds"]?.toLongOrNull() ?: 0L
        val name = runCatching { nameFile?.takeIf { it.exists() }?.readText()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

        return RecordedRide(
            id = UUID.randomUUID().toString(),
            startedAt = startedAt,
            endedAt = endedAt,
            distanceMeters = distance,
            elapsedSeconds = ((endedAt - startedAt) / 1000).coerceAtLeast(0),
            movingSeconds = movingSeconds,
            avgSpeedMps = if (movingSeconds > 0) distance / movingSeconds else 0.0,
            maxSpeedMps = meta["maxSpeedMps"]?.toDoubleOrNull() ?: 0.0,
            elevationGainMeters = meta["elevationGain"]?.toDoubleOrNull() ?: 0.0,
            elevationLossMeters = meta["elevationLoss"]?.toDoubleOrNull() ?: 0.0,
            points = points,
            name = name,
            isMock = false
        )
    }

    // ── Encoding helpers ─────────────────────────────────────────────────────

    private fun encodePoint(p: TrackPoint): String =
        listOf(
            p.latitude.toString(),
            p.longitude.toString(),
            p.timestamp.toString(),
            p.speedMps?.toString() ?: "",
            p.altitudeMeters?.toString() ?: "",
            p.accuracyMeters?.toString() ?: ""
        ).joinToString(",")

    private fun decodePoint(line: String): TrackPoint? = runCatching {
        val parts = line.split(',')
        if (parts.size < 3) return@runCatching null
        TrackPoint(
            latitude = parts[0].toDouble(),
            longitude = parts[1].toDouble(),
            timestamp = parts[2].toLong(),
            speedMps = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toFloat(),
            altitudeMeters = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }?.toDouble(),
            accuracyMeters = parts.getOrNull(5)?.takeIf { it.isNotEmpty() }?.toFloat()
        )
    }.getOrNull()

    private fun List<String>.toKeyValues(): Map<String, String> =
        mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()

    companion object {
        private const val DIR_NAME = "active_recording"
        private const val POINTS_FILE = "points.csv"
        private const val META_FILE = "meta"
        private const val NAME_FILE = "name"

        // Mirror RideTracker's "too short to keep" thresholds so a recovered ride is
        // held to the same bar as a normally-finished one.
        private const val MIN_POINTS = 2
        private const val MIN_DISTANCE_METERS = 20.0
    }
}

