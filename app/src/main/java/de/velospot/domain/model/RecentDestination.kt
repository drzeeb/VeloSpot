package de.velospot.domain.model

/**
 * How a [RecentDestination] is categorised.
 *
 * - [RECENT] — an ordinary recently-navigated destination (shown newest-first).
 * - [HOME] / [WORK] — a user-pinned quick shortcut. At most one of each exists.
 */
enum class DestinationKind { RECENT, HOME, WORK }

/**
 * A place the rider has navigated to before, kept so it can be re-selected with a
 * single tap from the chips under the search bar. Commuters navigate to the same
 * places daily, so recording them avoids re-typing the address every time.
 *
 * Persisted in a dedicated, isolated store, independent of the parking / saved
 * places / rides databases.
 */
data class RecentDestination(
    /** Stable id derived from the rounded coordinate, so repeat visits de-duplicate. */
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    /** Epoch millis of the most recent navigation to this destination. */
    val lastUsedAt: Long,
    val kind: DestinationKind
)

