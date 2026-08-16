package de.velospot.data.photo

import android.net.Uri
import de.velospot.core.photo.NormalizedCropRect

/**
 * Abstraction over the rider's on-disk bike photos, so callers (the bike-garage
 * ViewModel, the repository) depend on a small seam that can be faked in plain-JVM
 * unit tests instead of the Android-bound [BikePhotoStorage] implementation.
 */
interface BikePhotoStore {

    /**
     * Copies the gallery image at [sourceUri] into app storage for [bikeId]
     * (downscaled + re-encoded) and returns the stored file's absolute path, or
     * `null` when the source could not be read.
     */
    suspend fun savePhoto(bikeId: String, sourceUri: Uri): String?

    /**
     * Copies the gallery image at [sourceUri] into app storage for [bikeId],
     * first applying the rider-chosen framing [crop] (a normalized 0..1 rect in the
     * EXIF-oriented source image) before downscaling + re-encoding. A `null` crop
     * (or [NormalizedCropRect.FULL]) keeps the whole image, matching [savePhoto].
     * The default implementation ignores the crop and delegates, so existing test
     * fakes that only override the two-argument overload keep working.
     */
    suspend fun savePhoto(bikeId: String, sourceUri: Uri, crop: NormalizedCropRect?): String? =
        savePhoto(bikeId, sourceUri)

    /** Deletes [bikeId]'s stored photo, if any. */
    suspend fun deletePhoto(bikeId: String)
}

