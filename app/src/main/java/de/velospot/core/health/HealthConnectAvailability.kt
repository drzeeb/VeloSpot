package de.velospot.core.health

/**
 * Whether Health Connect can be used on this device right now, mapped from
 * `HealthConnectClient.getSdkStatus(...)` into a small, UI-friendly enum.
 */
enum class HealthConnectAvailability {
    /** Health Connect is installed and ready — export/permission flows can run. */
    AVAILABLE,

    /** The device supports Health Connect but the provider app is not installed. */
    NOT_INSTALLED,

    /** The provider app is installed but too old and must be updated. */
    UPDATE_REQUIRED,

    /** Health Connect is not supported on this device at all. */
    UNSUPPORTED;

    /** Whether a Play-Store install/update deep link should be offered to the user. */
    val isInstallable: Boolean get() = this == NOT_INSTALLED || this == UPDATE_REQUIRED
}

