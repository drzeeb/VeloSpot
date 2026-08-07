package de.velospot.core.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import de.velospot.domain.model.RecordedRide
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Outcome of a [HealthConnectExporter.exportRide] attempt. */
sealed interface HealthConnectExportResult {
    /** The ride's records were written (or replaced) successfully. */
    data object Success : HealthConnectExportResult

    /** The write permissions have not been granted — the caller must request them. */
    data object PermissionsMissing : HealthConnectExportResult

    /** Health Connect is not installed / not supported on this device. */
    data object Unavailable : HealthConnectExportResult

    /** The ride had nothing exportable (e.g. zero-length / no time span). */
    data object NothingToExport : HealthConnectExportResult

    /** The write failed unexpectedly (I/O / provider error). */
    data class Error(val throwable: Throwable) : HealthConnectExportResult
}

/**
 * Writes a finished [RecordedRide] into Android **Health Connect**.
 *
 * This is the **I/O side** of the feature: it owns the [HealthConnectClient],
 * checks availability and permissions and inserts the records. The pure
 * record-building maths live in [HealthConnectRideMapper] so they stay JVM-unit
 * testable without any Android dependency (mirroring `SensorParsers` vs
 * `BleSensorController`).
 *
 * Idempotency: every record carries a stable `clientRecordId` derived from the
 * ride's id (see [HealthConnectRideData]), so re-exporting the same ride
 * **replaces** the previous records instead of duplicating them.
 */
class HealthConnectExporter(private val context: Context) {

    /**
     * The exact set of Health Connect **write** permissions VeloSpot needs. Exposed
     * so the UI can request them via
     * `PermissionController.createRequestPermissionResultContract()`.
     */
    val writePermissions: Set<String> by lazy {
        setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(ElevationGainedRecord::class),
            HealthPermission.getWritePermission(SpeedRecord::class)
        )
    }

    /** Maps the SDK status into the small [HealthConnectAvailability] enum. */
    fun availability(): HealthConnectAvailability = when (
        runCatching { HealthConnectClient.getSdkStatus(context) }
            .getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)
    ) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            HealthConnectAvailability.UPDATE_REQUIRED
        // SDK_UNAVAILABLE covers both "provider not installed" and "unsupported
        // device". minSdk is 26 (Health Connect's floor), so treat it as installable
        // and let the Play-Store link surface — a genuinely unsupported device simply
        // won't find the listing.
        else -> HealthConnectAvailability.NOT_INSTALLED
    }

    /** The client, or `null` when Health Connect is unavailable on this device. */
    private fun clientOrNull(): HealthConnectClient? =
        if (availability() == HealthConnectAvailability.AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }

    /** True only when **all** of [writePermissions] have already been granted. */
    suspend fun hasAllPermissions(): Boolean {
        val client = clientOrNull() ?: return false
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        return granted.containsAll(writePermissions)
    }

    /**
     * Inserts the ride's records, verifying availability and permissions first.
     * Never throws — failures are reported through [HealthConnectExportResult].
     */
    suspend fun exportRide(ride: RecordedRide): HealthConnectExportResult {
        val client = clientOrNull() ?: return HealthConnectExportResult.Unavailable
        if (!hasAllPermissions()) return HealthConnectExportResult.PermissionsMissing

        val data = HealthConnectRideMapper.buildRideData(ride)
        // A ride with no real time span carries no valid session to write.
        if (data.endTimeMillis <= data.startTimeMillis) {
            return HealthConnectExportResult.NothingToExport
        }
        val records = buildRecords(data)
        return runCatching {
            client.insertRecords(records)
            HealthConnectExportResult.Success as HealthConnectExportResult
        }.getOrElse { HealthConnectExportResult.Error(it) }
    }

    /**
     * Best-effort auto-export invoked right after a ride is saved when the opt-in
     * setting is on. **Silently** no-ops when disabled, unavailable or not permitted
     * so it can never block or fail the ride save.
     */
    suspend fun autoExport(ride: RecordedRide, enabled: Boolean) {
        if (!enabled) return
        // Skip debug/simulator rides — they must not pollute the health store.
        if (ride.isMock) return
        runCatching { exportRide(ride) }
    }

    /** A Play-Store deep link to install/update the Health Connect provider app. */
    fun playStoreIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse(
            "market://details?id=$PROVIDER_PACKAGE" +
                "&url=healthconnect%3A%2F%2Fonboarding"
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage("com.android.vending")
    }

    /** Builds the Health Connect metadata carrying the stable idempotency id. */
    private fun metadataOf(clientRecordId: String): Metadata = Metadata.activelyRecorded(
        clientRecordId = clientRecordId,
        device = Device(type = Device.TYPE_PHONE)
    )

    /** Maps the pure [data] onto the concrete Health Connect record objects. */
    private fun buildRecords(data: HealthConnectRideData): List<Record> {
        val start = Instant.ofEpochMilli(data.startTimeMillis)
        val end = Instant.ofEpochMilli(data.endTimeMillis)
        val zone = ZoneId.systemDefault()
        val startOffset: ZoneOffset = zone.rules.getOffset(start)
        val endOffset: ZoneOffset = zone.rules.getOffset(end)

        return buildList {
            add(
                ExerciseSessionRecord(
                    startTime = start,
                    startZoneOffset = startOffset,
                    endTime = end,
                    endZoneOffset = endOffset,
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                    title = data.title,
                    metadata = metadataOf(data.exerciseClientRecordId)
                )
            )
            if (data.hasDistance) {
                add(
                    DistanceRecord(
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        distance = Length.meters(data.distanceMeters),
                        metadata = metadataOf(data.distanceClientRecordId)
                    )
                )
            }
            if (data.hasEnergy) {
                add(
                    TotalCaloriesBurnedRecord(
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        energy = Energy.kilocalories(data.energyKilocalories.toDouble()),
                        metadata = metadataOf(data.energyClientRecordId)
                    )
                )
            }
            if (data.hasElevationGain) {
                add(
                    ElevationGainedRecord(
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        elevation = Length.meters(data.elevationGainMeters),
                        metadata = metadataOf(data.elevationClientRecordId)
                    )
                )
            }
            if (data.hasSpeedSamples) {
                add(
                    SpeedRecord(
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        samples = data.speedSamples.map { sample ->
                            SpeedRecord.Sample(
                                time = Instant.ofEpochMilli(sample.timeMillis),
                                speed = Velocity.metersPerSecond(sample.metersPerSecond)
                            )
                        },
                        metadata = metadataOf(data.speedClientRecordId)
                    )
                )
            }
        }
    }

    companion object {
        /** The Health Connect provider app package (used for `<queries>` + deep links). */
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    }
}

