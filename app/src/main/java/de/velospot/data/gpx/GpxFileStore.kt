package de.velospot.data.gpx

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.core.gpx.GpxParser
import de.velospot.core.gpx.GpxRideFactory
import de.velospot.core.share.GpxDocument
import de.velospot.domain.model.RecordedRide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Storage-Access-Framework (SAF) file I/O for ride GPX export/import.
 *
 * Extracted from `MapViewModel` so the presentation layer no longer touches
 * `ContentResolver` / `DocumentsContract` / raw streams directly: the ViewModel
 * just hands over the picked [Uri]s and validated [GpxDocument]s and reacts to the
 * result. All work runs off the main thread; failures are swallowed into the
 * return value so callers only deal with a simple success/count/list outcome.
 */
@Singleton
class GpxFileStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Writes a single GPX document's [content] to the SAF-picked [uri]. */
    suspend fun writeDocument(uri: Uri, content: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                ?: error("no output stream for $uri")
        }.isSuccess
    }

    /**
     * Writes each [documents] entry into the SAF-picked folder [treeUri] as its own
     * file. Returns the number of documents successfully written (0 on total failure).
     */
    suspend fun writeDocumentsToTree(treeUri: Uri, documents: List<GpxDocument>): Int =
        withContext(Dispatchers.IO) {
            if (documents.isEmpty()) return@withContext 0
            val resolver = context.contentResolver
            var saved = 0
            runCatching {
                val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                for (doc in documents) {
                    runCatching {
                        val fileUri = DocumentsContract.createDocument(
                            resolver, parentUri, MIME_GPX, doc.fileName
                        ) ?: return@runCatching
                        resolver.openOutputStream(fileUri)?.use { it.write(doc.content.toByteArray()) }
                        saved++
                    }
                }
            }
            saved
        }

    /**
     * Reads the picked GPX file [uris] and converts every `<trk>` into a
     * [RecordedRide] (keeping its name). Unreadable/empty files are skipped; the
     * returned list is the rides ready to be persisted by the caller.
     */
    suspend fun importRides(uris: List<Uri>): List<RecordedRide> = withContext(Dispatchers.IO) {
        val rides = ArrayList<RecordedRide>()
        for (uri in uris) {
            rides.addAll(readRides(uri))
        }
        rides
    }

    /**
     * Reads a single opened GPX [uri] and converts every `<trk>` into an in-memory
     * [RecordedRide] **without** persisting anything. Used by the "open .gpx" intent
     * flow for both the direct-import path (caller persists the result) and the
     * transient preview path (caller shows it and only persists on demand). Returns
     * an empty list when the file is unreadable/empty or holds no usable track.
     */
    suspend fun readRides(uri: Uri): List<RecordedRide> = withContext(Dispatchers.IO) {
        val tracks = runCatching {
            context.contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }.orEmpty()
        }.getOrDefault(emptyList())
        GpxRideFactory.toRecordedRides(tracks)
    }

    /**
     * Copies an **incoming** GPX [source] (a `content://` uri handed to us by an
     * `ACTION_VIEW` intent from another app — Telegram, e-mail, a browser download, …)
     * into a private app-cache file and returns a stable `file://` [Uri] to it.
     *
     * The grant on a shared `content://` uri is **transient** — valid only for the
     * receiving intent/task and often not persistable (e.g. Telegram). Deferring the
     * read until the user taps a chooser button, on a background dispatcher and via
     * the application `ContentResolver`, made cold-start opens flaky (the first open
     * after launch could fail while later ones worked). Copying the bytes **now**,
     * while the grant is guaranteed valid, decouples the later parse from that grant
     * entirely — the returned cache uri is our own and always readable.
     *
     * Returns `null` when the source can't be read (the caller then falls back to the
     * original uri). Old cached opens are pruned so the cache doesn't grow unbounded.
     */
    suspend fun cacheIncomingGpx(source: Uri): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = java.io.File(context.cacheDir, OPENED_GPX_DIR).apply { mkdirs() }
            // Prune only *old* opens (older than an hour): a cold start can create two
            // ViewModels that each cache concurrently, so deleting every existing file
            // could wipe a sibling copy the user is about to import. Timestamped names
            // keep concurrent writes distinct.
            val cutoff = System.currentTimeMillis() - STALE_CACHE_MS
            dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) runCatching { f.delete() } }
            val target = java.io.File(dir, "opened-${System.currentTimeMillis()}.gpx")
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("no input stream for $source")
            Uri.fromFile(target)
        }.getOrNull()
    }

    private companion object {
        private const val MIME_GPX = "application/gpx+xml"
        private const val OPENED_GPX_DIR = "opened_gpx"
        private const val STALE_CACHE_MS = 60 * 60 * 1000L
    }
}

