package de.velospot.data.backup

import android.content.Context
import android.net.Uri
import de.velospot.core.backup.BackupCompatibility
import de.velospot.core.backup.BackupCrypto
import de.velospot.core.backup.BackupData
import de.velospot.core.backup.BackupManifest
import de.velospot.core.backup.BackupSchema
import de.velospot.core.backup.BackupSerializer
import de.velospot.core.backup.BikeProfileBackup
import de.velospot.core.backup.FavoriteBackup
import de.velospot.core.backup.PlannedRouteBackup
import de.velospot.core.backup.RecentDestinationBackup
import de.velospot.core.backup.RideBackup
import de.velospot.core.backup.RouteAttemptBackup
import de.velospot.core.backup.SavedPlaceBackup
import de.velospot.core.backup.WrappedReportBackup
import de.velospot.data.local.dao.BikeProfileDao
import de.velospot.data.local.dao.FavoriteSpaceDao
import de.velospot.data.local.dao.PlannedRouteDao
import de.velospot.data.local.dao.RecentDestinationDao
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.dao.RouteAttemptDao
import de.velospot.data.local.dao.SavedPlaceDao
import de.velospot.data.local.entity.BikeProfileEntity
import de.velospot.data.local.entity.FavoriteSpaceEntity
import de.velospot.data.local.entity.PlannedRouteEntity
import de.velospot.data.local.entity.RecentDestinationEntity
import de.velospot.data.local.entity.RecordedRideEntity
import de.velospot.data.local.entity.RouteAttemptEntity
import de.velospot.data.local.entity.SavedPlaceEntity
import de.velospot.data.settings.AppSettingsBackup
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.feature.wrapped.data.local.WrappedReportDao
import de.velospot.feature.wrapped.data.local.WrappedReportEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates VeloSpot's fully-local **Backup & Restore**: reads every user store
 * through its DAO, serialises it (via the pure [BackupSerializer]) into a single
 * ZIP file written to / read from a Storage-Access-Framework [Uri]. No network, no
 * cloud — the file goes exactly where the user picks.
 *
 * Included: recorded rides (incl. their full GPS tracks and bike profiles), saved
 * places, favourites (incl. notes), planned routes + leaderboard attempts, recent
 * destinations, "VeloSpot Wrapped" reports and the app settings. **Excluded**: the
 * bundled asset-seeded OSM parking database ([de.velospot.data.local.database.BikeParkingDatabase]),
 * which is regenerated from assets and is not user data.
 *
 * Restore is **REPLACE-all**: each store is cleared then re-inserted from the
 * backup, transactionally per store and off the main thread. A backup written by a
 * newer app (a higher [BackupSchema.CURRENT_SCHEMA_VERSION]) is refused; a
 * corrupt/foreign file yields [RestoreOutcome.Corrupt] rather than a crash.
 *
 * Ride tracks are read from SQLite **in chunks** (mirroring `RecordedRidesRepositoryImpl`)
 * so a dense multi-MB `pointsJson` never has to be squeezed into a single
 * `CursorWindow` (which would throw `SQLiteBlobTooBigException`).
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serializer: BackupSerializer,
    private val recordedRideDao: RecordedRideDao,
    private val bikeProfileDao: BikeProfileDao,
    private val savedPlaceDao: SavedPlaceDao,
    private val favoriteSpaceDao: FavoriteSpaceDao,
    private val plannedRouteDao: PlannedRouteDao,
    private val routeAttemptDao: RouteAttemptDao,
    private val recentDestinationDao: RecentDestinationDao,
    private val wrappedReportDao: WrappedReportDao,
    private val appSettingsBackup: AppSettingsBackup
) {

    enum class BackupOutcome { Success, Failure }

    enum class RestoreOutcome {
        Success,
        /** The backup was written by a newer app version — update first. */
        TooNew,
        /** The file was not a readable VeloSpot backup (missing/corrupt manifest or data). */
        Corrupt,
        /** The file is an encrypted backup and the supplied passphrase was wrong/missing. */
        WrongPassword,
        /** An I/O or DB error occurred while restoring. */
        Failure
    }

    /**
     * Reads every store and writes one `.vsbackup` file to the SAF [uri].
     *
     * When [passphrase] is non-null and non-blank the ZIP payload is wrapped with
     * [BackupCrypto] (AES-256-GCM); otherwise a plain, legacy-format ZIP is written.
     * Returns [BackupOutcome.Failure] on any I/O error.
     */
    suspend fun createBackup(uri: Uri, passphrase: String? = null): BackupOutcome =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    writeBackup(out, passphrase)
                } ?: BackupOutcome.Failure
            }.getOrDefault(BackupOutcome.Failure)
        }

    /**
     * Builds the full dump and writes it (encrypted when [passphrase] is non-blank) to
     * an already-open [out] stream — used by the scheduled automatic-backup worker
     * targeting a SAF document it opened itself. Does **not** close [out]. Returns
     * [BackupOutcome.Failure] on any error.
     */
    suspend fun writeBackup(out: OutputStream, passphrase: String? = null): BackupOutcome =
        withContext(Dispatchers.IO) {
            runCatching {
                val zipBytes = buildZipBytes()
                val payload = if (!passphrase.isNullOrBlank()) {
                    BackupCrypto.encrypt(zipBytes, passphrase)
                } else {
                    zipBytes
                }
                out.write(payload)
                out.flush()
                BackupOutcome.Success
            }.getOrDefault(BackupOutcome.Failure)
        }

    /**
     * Reads the `.vsbackup` at the SAF [uri] and REPLACE-restores every store.
     *
     * Transparently handles both formats: an encrypted container is decrypted with
     * [passphrase] first (a wrong/missing passphrase ⇒ [RestoreOutcome.WrongPassword]);
     * a legacy plain ZIP is read directly. Refuses a newer-schema backup and never
     * crashes on a corrupt/foreign file.
     */
    suspend fun restoreBackup(uri: Uri, passphrase: String? = null): RestoreOutcome =
        withContext(Dispatchers.IO) {
            val rawBytes = runCatching { readAllBytes(uri) }.getOrNull()
                ?: return@withContext RestoreOutcome.Corrupt

            val zipBytes: ByteArray = if (BackupCrypto.isEncryptedContainer(rawBytes)) {
                if (passphrase.isNullOrBlank()) return@withContext RestoreOutcome.WrongPassword
                BackupCrypto.decrypt(rawBytes, passphrase)
                    ?: return@withContext RestoreOutcome.WrongPassword
            } else {
                rawBytes
            }

            val entries = runCatching { readZipEntries(zipBytes) }.getOrNull()
                ?: return@withContext RestoreOutcome.Corrupt

            val manifest = serializer.decodeManifest(entries[BackupSchema.MANIFEST_ENTRY])
            when (BackupCompatibility.check(manifest)) {
                is BackupCompatibility.Result.Unreadable -> return@withContext RestoreOutcome.Corrupt
                is BackupCompatibility.Result.TooNew     -> return@withContext RestoreOutcome.TooNew
                is BackupCompatibility.Result.Compatible -> Unit // proceed with the restore
            }

            val data = serializer.decodeData(entries[BackupSchema.DATA_ENTRY])
                ?: return@withContext RestoreOutcome.Corrupt

            runCatching { applyRestore(data) }
                .map { RestoreOutcome.Success }
                .getOrDefault(RestoreOutcome.Failure)
        }

    /**
     * Peeks the first bytes of the SAF [uri] to report whether it is an encrypted
     * backup container (so the UI can decide whether to prompt for a passphrase).
     * Never throws — returns `false` on any read error.
     */
    suspend fun isBackupEncrypted(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(8)
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n < 0) break
                    read += n
                }
                BackupCrypto.isEncryptedContainer(head.copyOf(read))
            } ?: false
        }.getOrDefault(false)
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /** Builds the entire backup ZIP (manifest + data) in memory. */
    private suspend fun buildZipBytes(): ByteArray {
        val data = collectBackupData()
        val manifest = buildManifest()
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry(BackupSchema.MANIFEST_ENTRY))
            zip.write(serializer.encodeManifest(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(BackupSchema.DATA_ENTRY))
            zip.write(serializer.encodeData(data).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }

    private suspend fun collectBackupData(): BackupData {
        val rides = recordedRideDao.getAllIds().mapNotNull { id ->
            val meta = recordedRideDao.getMetaById(id) ?: return@mapNotNull null
            RideBackup(
                id = meta.id,
                startedAt = meta.startedAt,
                endedAt = meta.endedAt,
                distanceMeters = meta.distanceMeters,
                elapsedSeconds = meta.elapsedSeconds,
                movingSeconds = meta.movingSeconds,
                avgSpeedMps = meta.avgSpeedMps,
                maxSpeedMps = meta.maxSpeedMps,
                elevationGainMeters = meta.elevationGainMeters,
                elevationLossMeters = meta.elevationLossMeters,
                pointsJson = readPointsJson(id),
                name = meta.name,
                isMock = meta.isMock,
                archivedAt = meta.archivedAt,
                bikeProfileId = meta.bikeProfileId,
                sourceRouteId = meta.sourceRouteId,
                weatherJson = meta.weatherJson
            )
        }
        return BackupData(
            rides = rides,
            bikeProfiles = bikeProfileDao.getAll().map { it.toBackup() },
            savedPlaces = savedPlaceDao.getAll().map { it.toBackup() },
            favorites = favoriteSpaceDao.getAll().map { it.toBackup() },
            plannedRoutes = plannedRouteDao.getAll().map { it.toBackup() },
            routeAttempts = routeAttemptDao.getAll().map { it.toBackup() },
            recentDestinations = recentDestinationDao.getAll().map { it.toBackup() },
            wrappedReports = wrappedReportDao.getAll().map { it.toBackup() },
            settings = appSettingsBackup.exportSettings()
        )
    }

    /**
     * Reassembles a ride's `pointsJson` from SQLite in bounded [CHUNK]-char slices,
     * so an oversized track never has to be read as one cell into the CursorWindow.
     */
    private suspend fun readPointsJson(id: String): String {
        val length = recordedRideDao.getPointsJsonLength(id) ?: 0
        if (length <= 0) return "[]"
        val sb = StringBuilder(length)
        var start = 1 // SQLite substr() is 1-based.
        while (start <= length) {
            val chunk = recordedRideDao.getPointsJsonChunk(id, start, CHUNK) ?: break
            sb.append(chunk)
            start += CHUNK
        }
        return sb.toString()
    }

    private fun buildManifest(): BackupManifest = BackupManifest(
        backupSchemaVersion = BackupSchema.CURRENT_SCHEMA_VERSION,
        appVersionCode = de.velospot.BuildConfig.VERSION_CODE.toLong(),
        appVersionName = de.velospot.BuildConfig.VERSION_NAME,
        createdAtEpochMs = System.currentTimeMillis(),
        databaseVersions = mapOf(
            BackupSchema.DB_RIDES to DB_VERSION_RIDES,
            BackupSchema.DB_SAVED_PLACES to DB_VERSION_SAVED_PLACES,
            BackupSchema.DB_FAVORITES to DB_VERSION_FAVORITES,
            BackupSchema.DB_PLANNED_ROUTES to DB_VERSION_PLANNED_ROUTES,
            BackupSchema.DB_DESTINATION_HISTORY to DB_VERSION_DESTINATION_HISTORY,
            BackupSchema.DB_WRAPPED to DB_VERSION_WRAPPED
        )
    )

    // ── Restore (REPLACE-all, per-store) ───────────────────────────────────────

    private suspend fun applyRestore(data: BackupData) {
        // Rides + bike profiles share RidesDatabase.
        recordedRideDao.deleteAll()
        bikeProfileDao.deleteAll()
        data.bikeProfiles.forEach { bikeProfileDao.upsert(it.toEntity()) }
        data.rides.forEach { recordedRideDao.upsert(it.toEntity()) }

        savedPlaceDao.deleteAll()
        data.savedPlaces.forEach { savedPlaceDao.upsert(it.toEntity()) }

        favoriteSpaceDao.deleteAll()
        data.favorites.forEach { favoriteSpaceDao.addFavorite(it.toEntity()) }

        plannedRouteDao.deleteAll()
        routeAttemptDao.deleteAll()
        data.plannedRoutes.forEach { plannedRouteDao.upsert(it.toEntity()) }
        data.routeAttempts.forEach { routeAttemptDao.upsert(it.toEntity()) }

        recentDestinationDao.deleteAll()
        data.recentDestinations.forEach { recentDestinationDao.upsert(it.toEntity()) }

        wrappedReportDao.deleteAll()
        data.wrappedReports.forEach { wrappedReportDao.upsert(it.toEntity()) }

        appSettingsBackup.importSettings(data.settings)
    }

    // ── ZIP reading ─────────────────────────────────────────────────────────────

    /** Reads all bytes at [uri]. Throws on an unopenable/unreadable stream. */
    private fun readAllBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Cannot open backup uri")

    /** Reads the in-memory ZIP [bytes] into a map of entry-name → UTF-8 text. */
    private fun readZipEntries(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private companion object {
        /** 256 KB text slices — comfortably under SQLite's ~2 MB CursorWindow limit. */
        const val CHUNK = 256 * 1024

        // Current @Database versions (informational, recorded in the manifest).
        const val DB_VERSION_RIDES = 8
        const val DB_VERSION_SAVED_PLACES = 1
        const val DB_VERSION_FAVORITES = 1
        const val DB_VERSION_PLANNED_ROUTES = 1
        const val DB_VERSION_DESTINATION_HISTORY = 2
        const val DB_VERSION_WRAPPED = 1
    }
}

// ── Entity ⇄ DTO mapping (kept out of the pure core module) ────────────────────

private fun BikeProfileEntity.toBackup() = BikeProfileBackup(
    id, name, brand, model, type, tireSize, weightKg, color, modelYear, notes,
    isDefault, createdAt, serviceIntervalKm, lastServiceNotifiedKm
)

private fun BikeProfileBackup.toEntity() = BikeProfileEntity(
    id, name, brand, model, type, tireSize, weightKg, color, modelYear, notes,
    isDefault, createdAt, serviceIntervalKm, lastServiceNotifiedKm
)

private fun SavedPlaceEntity.toBackup() =
    SavedPlaceBackup(id, name, latitude, longitude, address, addedAt)

private fun SavedPlaceBackup.toEntity() =
    SavedPlaceEntity(id, name, latitude, longitude, address, addedAt)

private fun FavoriteSpaceEntity.toBackup() =
    FavoriteBackup(parkingSpaceId, addedAt, notes)

private fun FavoriteBackup.toEntity() =
    FavoriteSpaceEntity(parkingSpaceId, addedAt, notes)

private fun PlannedRouteEntity.toBackup() = PlannedRouteBackup(
    id, name, waypointsJson, geometryJson, distanceMeters,
    elevationGainMeters, elevationLossMeters, energyJoules, createdAt
)

private fun PlannedRouteBackup.toEntity() = PlannedRouteEntity(
    id, name, waypointsJson, geometryJson, distanceMeters,
    elevationGainMeters, elevationLossMeters, energyJoules, createdAt
)

private fun RouteAttemptEntity.toBackup() = RouteAttemptBackup(
    id, routeId, reversed, recordedAt, elapsedSeconds, movingSeconds,
    distanceMeters, avgSpeedMps, maxSpeedMps, elevationGainMeters, rideId
)

private fun RouteAttemptBackup.toEntity() = RouteAttemptEntity(
    id, routeId, reversed, recordedAt, elapsedSeconds, movingSeconds,
    distanceMeters, avgSpeedMps, maxSpeedMps, elevationGainMeters, rideId
)

private fun RecentDestinationEntity.toBackup() =
    RecentDestinationBackup(id, name, latitude, longitude, address, lastUsedAt, kind)

private fun RecentDestinationBackup.toEntity() =
    RecentDestinationEntity(id, name, latitude, longitude, address, lastUsedAt, kind)

private fun WrappedReportEntity.toBackup() =
    WrappedReportBackup(id, type, periodStart, periodEnd, generatedAt, snapshotJson)

private fun WrappedReportBackup.toEntity() =
    WrappedReportEntity(id, type, periodStart, periodEnd, generatedAt, snapshotJson)

private fun RideBackup.toEntity() = RecordedRideEntity(
    id = id,
    startedAt = startedAt,
    endedAt = endedAt,
    distanceMeters = distanceMeters,
    elapsedSeconds = elapsedSeconds,
    movingSeconds = movingSeconds,
    avgSpeedMps = avgSpeedMps,
    maxSpeedMps = maxSpeedMps,
    elevationGainMeters = elevationGainMeters,
    elevationLossMeters = elevationLossMeters,
    pointsJson = pointsJson,
    name = name,
    isMock = isMock,
    archivedAt = archivedAt,
    bikeProfileId = bikeProfileId,
    sourceRouteId = sourceRouteId,
    weatherJson = weatherJson
)
