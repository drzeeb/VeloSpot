package de.velospot.screenshots

import android.content.ContentValues
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Captures full-**screen** screenshots of the running app and writes them as PNGs.
 *
 * Uses [android.app.UiAutomation.takeScreenshot], which grabs the entire composited
 * display — so it includes both the native MapLibre `SurfaceView` **and** Compose
 * popups that live in their own windows (`DropdownMenu`, `ModalBottomSheet`). A plain
 * `PixelCopy` of the Activity window would miss those popups.
 *
 * The device **status bar is cropped off** so personal info (clock, carrier,
 * notifications) never ends up in the published screenshots.
 *
 * Files go into the shared MediaStore Pictures collection under [RELATIVE_DIR]. That
 * survives the app uninstall AGP performs after a connected run (scoped storage blocks
 * `adb pull` of the app's own external dir, and TestStorage is unreliable on some OEM
 * devices), and the host can simply pull them:
 *
 *   adb pull /sdcard/Pictures/VeloSpotScreenshots ./screenshots
 */
object ScreenshotSaver {

    /** Shared-storage sub-folder the PNGs are written to. */
    const val RELATIVE_DIR = "Pictures/VeloSpotScreenshots"

    /**
     * Captures the whole screen (native map + Compose popups) via UiAutomation and
     * crops the status bar from the top so no personal status-bar info is shown.
     */
    fun capture(): Bitmap {
        val full = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("UiAutomation.takeScreenshot() returned null")
        // takeScreenshot may hand back a HARDWARE bitmap; copy to a mutable config so
        // we can crop it.
        val src = if (full.config == Bitmap.Config.HARDWARE) {
            full.copy(Bitmap.Config.ARGB_8888, false).also { full.recycle() }
        } else {
            full
        }
        val top = statusBarHeightPx().coerceIn(0, src.height - 1)
        if (top == 0) return src
        val cropped = Bitmap.createBitmap(src, 0, top, src.width, src.height - top)
        if (cropped !== src) src.recycle()
        return cropped
    }

    /** Status bar height in pixels (matches the takeScreenshot resolution). */
    private fun statusBarHeightPx(): Int {
        val res = Resources.getSystem()
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    /** Writes [bitmap] as a PNG into the shared Pictures collection; returns its Uri. */
    fun save(name: String, bitmap: Bitmap): Uri {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Screenshot export requires API 29+ (scoped MediaStore)."
        }
        val fileName = if (name.endsWith(".png")) name else "$name.png"
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // Replace any previous file with the same name so re-runs don't pile up "(1)".
        resolver.delete(
            collection,
            "${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.DISPLAY_NAME}=?",
            arrayOf("$RELATIVE_DIR/", fileName)
        )

        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending)
            ?: error("MediaStore insert failed for $fileName")
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: error("Could not open output stream for $uri")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )
        return uri
    }

    /** Convenience: capture the screen and save it under [name]. */
    fun captureAndSave(name: String): Uri = save(name, capture())
}

