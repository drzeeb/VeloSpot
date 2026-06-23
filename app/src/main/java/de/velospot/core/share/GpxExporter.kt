package de.velospot.core.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import de.velospot.core.gpx.GpxWriter
import de.velospot.domain.model.RecordedRide
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single GPX file to export: its full file name (incl. `.gpx`) and content. */
data class GpxDocument(val fileName: String, val content: String)

/**
 * Builds GPX files from recorded rides and either shares them (cache +
 * [FileProvider], mirroring [ImageSharer]) or hands the raw documents to the
 * caller to save via the Storage Access Framework.
 *
 * A single ride (or the "combine into one file" option) yields one document; the
 * "separate" option yields one per ride, each named after its ride.
 */
object GpxExporter {

    private const val EXPORT_DIR = "shared_gpx"
    private const val MIME_GPX = "application/gpx+xml"
    private const val MAX_NAME_LENGTH = 60

    /**
     * Builds the GPX [GpxDocument]s for [rides]. With [combineIntoSingleFile] (or a
     * single ride) all tracks go into one document; otherwise one per ride. File
     * names are sanitised and de-duplicated within the batch.
     */
    fun buildDocuments(
        rides: List<RecordedRide>,
        combineIntoSingleFile: Boolean,
        combinedFileName: String
    ): List<GpxDocument> {
        if (rides.isEmpty()) return emptyList()
        val used = mutableSetOf<String>()
        return if (combineIntoSingleFile || rides.size == 1) {
            val base = if (rides.size == 1) fileBaseName(rides.first()) else combinedFileName
            listOf(GpxDocument(uniqueFileName(base, used), GpxWriter.write(rides)))
        } else {
            rides.map { ride ->
                GpxDocument(uniqueFileName(fileBaseName(ride), used), GpxWriter.write(listOf(ride)))
            }
        }
    }

    /** Writes [documents] to the cache and opens the system share sheet. */
    fun share(context: Context, documents: List<GpxDocument>, chooserTitle: String) {
        if (documents.isEmpty()) return

        val dir = File(context.cacheDir, EXPORT_DIR).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() } // drop stale exports
        }
        val uris = documents.map { doc ->
            val file = File(dir, doc.fileName).apply { writeText(doc.content) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        val sendIntent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_GPX
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = MIME_GPX
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
        }.apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** A ride's export base name: its (sanitised) name, else a date stamp. */
    private fun fileBaseName(ride: RecordedRide): String {
        val named = ride.name?.trim()?.takeIf { it.isNotBlank() }
        if (named != null) return named
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(ride.startedAt))
        return "VeloSpot-$stamp"
    }

    /** Sanitised `<base>.gpx`, disambiguated against [used] within the batch. */
    private fun uniqueFileName(base: String, used: MutableSet<String>): String {
        var name = sanitise(base)
        if (!used.add(name)) {
            var i = 2
            while (!used.add("$name-$i")) i++
            name = "$name-$i"
        }
        return "$name.gpx"
    }

    /** Replaces filesystem-unsafe characters and trims to a sane length. */
    private fun sanitise(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
        val safe = cleaned.ifBlank { "VeloSpot-ride" }
        return if (safe.length > MAX_NAME_LENGTH) safe.substring(0, MAX_NAME_LENGTH).trim() else safe
    }
}

