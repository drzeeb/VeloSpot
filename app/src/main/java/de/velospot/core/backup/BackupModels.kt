package de.velospot.core.backup

import com.squareup.moshi.JsonClass

/**
 * Pure, Android-free data-transfer objects for a VeloSpot backup.
 *
 * Each DTO mirrors, field-for-field, one Room entity (or settings source) but
 * carries **no** Room/Android types, so the whole (de)serialisation layer stays a
 * plain-Kotlin, JVM-unit-testable concern. The Android layer maps its Room
 * entities to/from these DTOs; nothing here knows a database exists.
 */

/** Mirror of `RecordedRideEntity` (incl. the full serialised GPS track). */
@JsonClass(generateAdapter = true)
data class RideBackup(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val distanceMeters: Double,
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val pointsJson: String,
    val name: String? = null,
    val isMock: Boolean = false,
    val archivedAt: Long? = null,
    val bikeProfileId: String? = null,
    val sourceRouteId: String? = null,
    val weatherJson: String? = null
)

/** Mirror of `BikeProfileEntity` (a bike in the rider's garage). */
@JsonClass(generateAdapter = true)
data class BikeProfileBackup(
    val id: String,
    val name: String,
    val brand: String? = null,
    val model: String? = null,
    val type: String,
    val tireSize: String? = null,
    val weightKg: Double? = null,
    val color: String? = null,
    val modelYear: Int? = null,
    val notes: String? = null,
    val isDefault: Boolean = false,
    val createdAt: Long,
    val serviceIntervalKm: Int? = null,
    val lastServiceNotifiedKm: Int = 0
)

/** Mirror of `SavedPlaceEntity` (a user-saved custom place). */
@JsonClass(generateAdapter = true)
data class SavedPlaceBackup(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val addedAt: Long
)

/** Mirror of `FavoriteSpaceEntity` (a favourited parking space, incl. notes). */
@JsonClass(generateAdapter = true)
data class FavoriteBackup(
    val parkingSpaceId: String,
    val addedAt: Long,
    val notes: String? = null
)

/** Mirror of `PlannedRouteEntity` (a user-planned multi-waypoint route). */
@JsonClass(generateAdapter = true)
data class PlannedRouteBackup(
    val id: String,
    val name: String,
    val waypointsJson: String,
    val geometryJson: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val energyJoules: Double? = null,
    val createdAt: Long
)

/** Mirror of `RouteAttemptEntity` (one leaderboard attempt of a planned route). */
@JsonClass(generateAdapter = true)
data class RouteAttemptBackup(
    val id: String,
    val routeId: String,
    val reversed: Boolean,
    val recordedAt: Long,
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val distanceMeters: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainMeters: Double,
    val rideId: String? = null
)

/** Mirror of `RecentDestinationEntity` (recent + pinned Home/Work destinations). */
@JsonClass(generateAdapter = true)
data class RecentDestinationBackup(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val lastUsedAt: Long,
    val kind: String
)

/** Mirror of `WrappedReportEntity` (a stored "VeloSpot Wrapped" report). */
@JsonClass(generateAdapter = true)
data class WrappedReportBackup(
    val id: String,
    val type: String,
    val periodStart: Long,
    val periodEnd: Long,
    val generatedAt: Long,
    val snapshotJson: String
)

/**
 * One persisted user setting, captured generically so the backup format is
 * agnostic to which keys exist today.
 *
 * @property store which store the key lives in — a `BackupSchema.SETTINGS_STORE_*`
 *   value (the DataStore preferences file, or a named SharedPreferences).
 * @property key the preference key.
 * @property type the value's stored type ([SettingType] name), so restore can
 *   re-create the exact same typed entry.
 * @property value the value rendered as a string (a `STRING_SET` is a JSON array).
 */
@JsonClass(generateAdapter = true)
data class SettingBackup(
    val store: String,
    val key: String,
    val type: String,
    val value: String
)

/** The supported preference value types. */
enum class SettingType { BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING, STRING_SET }

/**
 * The complete backup payload: every user store plus the app settings. Serialised
 * as `data.json` inside the ZIP container. The [BackupManifest] is stored
 * separately so compatibility can be checked without reading this (large) blob.
 */
@JsonClass(generateAdapter = true)
data class BackupData(
    val rides: List<RideBackup> = emptyList(),
    val bikeProfiles: List<BikeProfileBackup> = emptyList(),
    val savedPlaces: List<SavedPlaceBackup> = emptyList(),
    val favorites: List<FavoriteBackup> = emptyList(),
    val plannedRoutes: List<PlannedRouteBackup> = emptyList(),
    val routeAttempts: List<RouteAttemptBackup> = emptyList(),
    val recentDestinations: List<RecentDestinationBackup> = emptyList(),
    val wrappedReports: List<WrappedReportBackup> = emptyList(),
    val settings: List<SettingBackup> = emptyList()
)

