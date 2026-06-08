package de.velospot.data.brouter

import android.content.Context
import de.velospot.domain.model.NoInternetConnectionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.abs
import kotlin.math.floor

private const val SEGMENTS_BASE_URL = "https://brouter.de/brouter/segments4/"

class BRouterSegmentManager(
    context: Context,
    private val okHttpClient: OkHttpClient
) {
    val segmentsDir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "brouter/segments"
    ).also { it.mkdirs() }

    // ── Public API ────────────────────────────────────────────────────────────

    fun hasAllSegments(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): Boolean = requiredSegmentNames(fromLat, fromLon, toLat, toLon)
        .all { File(segmentsDir, it).exists() }

    suspend fun ensureSegments(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val missing = requiredSegmentNames(fromLat, fromLon, toLat, toLon)
            .filter { !File(segmentsDir, it).exists() }
        for (name in missing) {
            downloadSegment(name) { dl, tot -> onProgress(dl, tot) }
        }
    }

    /**
     * Downloads the single 5°×5° segment tile that covers [lat]/[lon].
     * Downloading only the primary tile keeps the one-time download at
     * roughly 100–150 MB instead of a 3×3 grid (~1 GB).
     *
     * If the user routes to a destination outside this tile, the app falls
     * back to OSRM automatically.
     */
    suspend fun downloadSegmentsForLocation(
        lat: Double,
        lon: Double,
        onProgress: (
            downloaded: Long,
            total: Long,
            fileIndex: Int,
            totalFiles: Int,
            fileName: String
        ) -> Unit = { _, _, _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        // Only download the tile containing the user's position.
        val primary = segmentFileName(tileDegree(lon), tileDegree(lat))
        val missing  = listOf(primary).filter { !File(segmentsDir, it).exists() }
        val total    = missing.size
        missing.forEachIndexed { index, name ->
            downloadSegment(name) { dl, tot ->
                onProgress(dl, tot, index + 1, total, name)
            }
        }
    }

    /** Returns true when at least one segment file exists on the device. */
    fun hasAnySegments(): Boolean =
        segmentsDir.listFiles()?.any { it.extension == "rd5" } == true

    /** Total size of all downloaded segment files in bytes. */
    fun totalSegmentsSizeBytes(): Long =
        segmentsDir.listFiles()
            ?.filter { it.extension == "rd5" }
            ?.sumOf { it.length() } ?: 0L

    /** Deletes all downloaded `.rd5` segment files. */
    fun deleteAllSegments() {
        segmentsDir.listFiles()
            ?.filter { it.extension == "rd5" || it.extension == "tmp" }
            ?.forEach { it.delete() }
    }

    fun requiredSegmentNames(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): List<String> {
        val minLon = minOf(fromLon, toLon)
        val maxLon = maxOf(fromLon, toLon)
        val minLat = minOf(fromLat, toLat)
        val maxLat = maxOf(fromLat, toLat)
        val names = mutableListOf<String>()
        var lon = tileDegree(minLon)
        while (lon <= maxLon) {
            var lat = tileDegree(minLat)
            while (lat <= maxLat) {
                names += segmentFileName(lon, lat)
                lat += 5
            }
            lon += 5
        }
        return names
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun tileDegree(degree: Double): Int = floor(degree / 5.0).toInt() * 5

    private fun segmentFileName(lonDeg: Int, latDeg: Int): String {
        val lonStr = if (lonDeg >= 0) "E${lonDeg}" else "W${abs(lonDeg)}"
        val latStr = if (latDeg >= 0) "N${latDeg}" else "S${abs(latDeg)}"
        return "${lonStr}_${latStr}.rd5"
    }

    private fun downloadSegment(
        fileName: String,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val url = "$SEGMENTS_BASE_URL$fileName"
        val request = Request.Builder().url(url).build()
        try {
            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                check(resp.isSuccessful) { "Failed to download segment $fileName: HTTP ${resp.code}" }
                val body = checkNotNull(resp.body) { "Empty response body for $fileName" }
                val totalBytes = body.contentLength()
                val tmpFile = File(segmentsDir, "$fileName.tmp")
                val destFile = File(segmentsDir, fileName)
                tmpFile.outputStream().buffered().use { out ->
                    body.byteStream().buffered().use { input ->
                        val buffer = ByteArray(8_192)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, totalBytes)
                        }
                    }
                }
                tmpFile.renameTo(destFile)
            }
        } catch (e: java.net.UnknownHostException)  { throw NoInternetConnectionException(e) }
          catch (e: java.net.ConnectException)       { throw NoInternetConnectionException(e) }
          catch (e: java.net.SocketTimeoutException) { throw NoInternetConnectionException(e) }
          catch (e: java.net.SocketException)        { throw NoInternetConnectionException(e) }
    }
}
