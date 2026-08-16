package de.velospot.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.ExifInterface
import android.graphics.Matrix
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.core.photo.BikePhotoScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin Android layer that owns the rider's uploaded bike photos on disk.
 *
 * Photos live under `filesDir/bike_photos/<bikeId>.jpg` (app-internal storage, so
 * only this app can read them and they are wiped on uninstall). A picked gallery
 * image is copied in — never referenced by its transient `content://` Uri — and
 * downscaled / re-encoded via the pure [BikePhotoScaling] maths so a multi-megabyte
 * original never bloats app storage. The path is deterministic from the bike id, so
 * replacing a photo simply overwrites the same file and deleting a bike removes it.
 *
 * All disk work runs off the main thread ([Dispatchers.IO]).
 */
@Singleton
class BikePhotoStorage @Inject constructor(
    @ApplicationContext private val context: Context
) : BikePhotoStore {

    private fun dir(): File = File(context.filesDir, PHOTO_DIR).apply { mkdirs() }

    /** The (possibly not-yet-existing) file backing [bikeId]'s photo. */
    fun photoFile(bikeId: String): File = File(dir(), "$bikeId.jpg")

    /**
     * Copies the gallery image at [sourceUri] into app storage for [bikeId],
     * downscaled to [BikePhotoScaling.MAX_DIMENSION] px on its longest edge and
     * re-encoded as JPEG. Returns the absolute path of the stored file, or `null`
     * when the source could not be read / decoded.
     */
    override suspend fun savePhoto(bikeId: String, sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            // 1) Read just the bounds to compute a cheap power-of-two pre-scale.
            //    NOTE: in `inJustDecodeBounds` mode `decodeStream` *always* returns
            //    null (it only fills `bounds`), so the null-check must be on the
            //    stream — not on the decode result — or every save would bail here.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val opened = context.contentResolver.openInputStream(sourceUri)
                ?: return@runCatching null
            opened.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = BikePhotoScaling.sampleSize(bounds.outWidth, bounds.outHeight)
            }
            val decoded = context.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@runCatching null

            // 2) Respect the photo's EXIF orientation so portrait shots aren't sideways.
            val oriented = applyExifOrientation(sourceUri, decoded)

            // 3) Resize exactly to fit the cap, then re-encode as JPEG.
            val (w, h) = BikePhotoScaling.targetDimensions(oriented.width, oriented.height)
            val scaled = if (w != oriented.width || h != oriented.height) {
                oriented.scale(w, h)
            } else {
                oriented
            }

            val file = photoFile(bikeId)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, BikePhotoScaling.JPEG_QUALITY, out)
            }
            if (scaled !== decoded) scaled.recycle()
            if (oriented !== decoded && oriented !== scaled) oriented.recycle()
            decoded.recycle()
            file.absolutePath
        }.getOrNull()
    }

    /** Deletes [bikeId]'s stored photo, if any. */
    override suspend fun deletePhoto(bikeId: String) = withContext(Dispatchers.IO) {
        runCatching { photoFile(bikeId).delete() }
        Unit
    }


    private fun applyExifOrientation(sourceUri: Uri, bitmap: Bitmap): Bitmap {
        val rotation = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (rotation == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val PHOTO_DIR = "bike_photos"
    }
}


