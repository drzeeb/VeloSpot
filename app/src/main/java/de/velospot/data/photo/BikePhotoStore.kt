package de.velospot.data.photo

import android.net.Uri

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

    /** Deletes [bikeId]'s stored photo, if any. */
    suspend fun deletePhoto(bikeId: String)
}

