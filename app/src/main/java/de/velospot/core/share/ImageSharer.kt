package de.velospot.core.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Saves a generated bitmap to the app's cache and launches the system share sheet
 * so the user can send it to WhatsApp, Telegram, Instagram, etc.
 *
 * The file is written under `cacheDir/shared_images/` and exposed through the
 * app's [FileProvider] (authority `${applicationId}.fileprovider`). Cache files
 * are transient — the OS may reclaim them — which is exactly right for a one-off
 * share image.
 */
object ImageSharer {

    private const val SHARE_DIR = "shared_images"
    private const val MIME_PNG = "image/png"

    /**
     * Writes [bitmap] as a PNG and starts a share chooser.
     *
     * @param chooserTitle title shown on the system share sheet.
     * @param subject optional subject (used by e.g. email targets).
     */
    fun shareBitmap(
        context: Context,
        bitmap: Bitmap,
        chooserTitle: String,
        subject: String? = null
    ) {
        val uri = run {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            val file = File(dir, "velospot_ride_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_PNG
            putExtra(Intent.EXTRA_STREAM, uri)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}

