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
            val tracks = runCatching {
                context.contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }.orEmpty()
            }.getOrDefault(emptyList())
            for (track in tracks) {
                GpxRideFactory.toRecordedRide(track)?.let { rides.add(it) }
            }
        }
        rides
    }

    private companion object {
        private const val MIME_GPX = "application/gpx+xml"
    }
}

