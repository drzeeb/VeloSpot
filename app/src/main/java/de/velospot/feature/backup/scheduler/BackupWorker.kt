package de.velospot.feature.backup.scheduler

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.velospot.core.backup.BackupSchema
import de.velospot.data.backup.BackupManager
import de.velospot.data.backup.BackupSecretStore
import de.velospot.feature.backup.data.BackupScheduleDataStore
import kotlinx.coroutines.flow.first

/**
 * The self-rescheduling background worker that writes one automatic VeloSpot backup.
 *
 * On each run it reads the stored schedule; if disabled the run is a no-op (the
 * `finally` reschedule cancels pending work). When enabled it needs both a picked SAF
 * destination folder and a stored passphrase (via [BackupSecretStore]); if either is
 * missing the run is skipped without crashing. Otherwise it **overwrites the single
 * destination file** ([DEST_FILE_NAME]) in the destination tree — deleting any prior
 * copy and creating it fresh so each run is a clean, full, encrypted dump.
 *
 * Whatever the outcome, it always asks [BackupScheduler] to enqueue the next
 * occurrence in a `finally`, so a single hiccup never stops the schedule forever.
 * The branch decision is factored into the pure [BackupWorkDecision] for testing.
 *
 * Injected via [HiltWorker] + the app's `HiltWorkerFactory` (see BaseApplication).
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
    private val scheduleDataStore: BackupScheduleDataStore,
    private val secretStore: BackupSecretStore,
    private val scheduler: BackupScheduler
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val schedule = scheduleDataStore.schedule.first()
            val treeUriString = scheduleDataStore.destinationTreeUri.first()
            val passphrase = secretStore.getPassphrase()

            val decision = BackupWorkDecision.decide(
                enabled = schedule.enabled,
                hasDestination = !treeUriString.isNullOrBlank(),
                hasPassphrase = !passphrase.isNullOrBlank()
            )
            if (decision != BackupWorkOutcome.RUN) {
                // Disabled or not yet configured — nothing to write this run.
                return Result.success()
            }

            val written = runCatching {
                writeToTree(Uri.parse(treeUriString), passphrase!!)
            }.getOrDefault(false)

            return if (written) Result.success() else Result.retry()
        } catch (_: Exception) {
            return Result.retry()
        } finally {
            // Always enqueue the next occurrence, even on failure, so the chain lives on.
            scheduler.reschedule()
        }
    }

    /**
     * Overwrites [DEST_FILE_NAME] inside the SAF [treeUri]: deletes any existing child
     * with that name, creates it fresh and streams the (encrypted) dump into it.
     * Returns whether the backup was written successfully.
     */
    private suspend fun writeToTree(treeUri: Uri, passphrase: String): Boolean {
        val resolver = context.contentResolver
        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)

        // Delete a prior copy so createDocument never yields a "(1)"-suffixed duplicate.
        findChild(treeUri, parentDocId, DEST_FILE_NAME)?.let { existing ->
            runCatching { DocumentsContract.deleteDocument(resolver, existing) }
        }

        val fileUri = DocumentsContract.createDocument(
            resolver, parentUri, BackupSchema.MIME_TYPE, DEST_FILE_NAME
        ) ?: return false

        return resolver.openOutputStream(fileUri)?.use { out ->
            backupManager.writeBackup(out, passphrase) == BackupManager.BackupOutcome.Success
        } ?: false
    }

    /** Finds a direct child document named [name] in [treeUri], or `null`. */
    private fun findChild(treeUri: Uri, parentDocId: String, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            }
        }
        return null
    }

    companion object {
        /** The single destination file overwritten on every automatic run. */
        const val DEST_FILE_NAME = "velospot-auto-backup.${BackupSchema.FILE_EXTENSION}"
    }
}

