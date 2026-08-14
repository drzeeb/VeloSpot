package de.velospot.feature.backup.scheduler

/**
 * The action a scheduled automatic-backup run should take, decided purely from three
 * booleans so the branching is JVM-unit-testable without WorkManager, SAF or Android.
 * See [BackupWorkDecision.decide].
 */
enum class BackupWorkOutcome {
    /** The schedule is off — do nothing but let the scheduler settle (cancel). */
    DISABLED,

    /** No destination folder has been picked yet — skip (nothing to write to). */
    SKIP_NO_DESTINATION,

    /** No passphrase is stored — skip (an automatic backup is always encrypted). */
    SKIP_NO_PASSPHRASE,

    /** Everything is in place — write the full dump, overwriting the single file. */
    RUN
}

/** Pure decision table for the automatic-backup worker, kept side-effect-free. */
object BackupWorkDecision {

    /**
     * @param enabled whether the stored schedule is currently enabled.
     * @param hasDestination whether a SAF destination tree Uri has been stored.
     * @param hasPassphrase whether a passphrase is stored for the unattended backup.
     */
    fun decide(
        enabled: Boolean,
        hasDestination: Boolean,
        hasPassphrase: Boolean
    ): BackupWorkOutcome = when {
        !enabled -> BackupWorkOutcome.DISABLED
        !hasDestination -> BackupWorkOutcome.SKIP_NO_DESTINATION
        !hasPassphrase -> BackupWorkOutcome.SKIP_NO_PASSPHRASE
        else -> BackupWorkOutcome.RUN
    }
}

