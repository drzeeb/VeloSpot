package de.velospot.data.repository

import de.velospot.data.local.dao.RecentDestinationDao
import de.velospot.data.local.entity.RecentDestinationEntity
import de.velospot.domain.model.DestinationKind
import de.velospot.domain.model.RecentDestination
import de.velospot.domain.repository.DestinationHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [DestinationHistoryRepository].
 *
 * A destination's id is derived from its rounded coordinate ([coordKey]) so
 * navigating to the same place again updates the existing row (bumping its
 * last-used time) instead of creating a duplicate.
 */
@Singleton
class DestinationHistoryRepositoryImpl @Inject constructor(
    private val dao: RecentDestinationDao
) : DestinationHistoryRepository {

    override fun recentDestinations(limit: Int): Flow<List<RecentDestination>> =
        dao.recentsFlow(limit).map { list -> list.map { it.toDomain() } }

    override fun pinnedDestinations(): Flow<List<RecentDestination>> =
        dao.pinnedFlow().map { list -> list.map { it.toDomain() } }

    override suspend fun record(name: String, latitude: Double, longitude: Double, address: String?) {
        val id = coordKey(latitude, longitude)
        val existing = dao.getById(id)
        // Keep an existing Home / Work pin; otherwise it's an ordinary recent.
        val kind = existing?.kind?.takeIf { it == "HOME" || it == "WORK" } ?: "RECENT"
        dao.upsert(
            RecentDestinationEntity(
                id         = id,
                name       = name.ifBlank { existing?.name.orEmpty().ifBlank { id } },
                latitude   = latitude,
                longitude  = longitude,
                address    = address ?: existing?.address,
                lastUsedAt = System.currentTimeMillis(),
                kind       = kind
            )
        )
        if (kind == "RECENT") dao.trimRecents(MAX_RECENTS)
    }

    override suspend fun pin(id: String, kind: DestinationKind) {
        if (kind == DestinationKind.RECENT) {
            dao.setKind(id, DestinationKind.RECENT.name)
            return
        }
        dao.demoteKind(kind.name)   // enforce a single Home / a single Work
        dao.setKind(id, kind.name)
    }

    override suspend fun remove(id: String) = dao.delete(id)

    private fun RecentDestinationEntity.toDomain() = RecentDestination(
        id         = id,
        name       = name,
        latitude   = latitude,
        longitude  = longitude,
        address    = address,
        lastUsedAt = lastUsedAt,
        kind       = runCatching { DestinationKind.valueOf(kind) }.getOrDefault(DestinationKind.RECENT)
    )

    private companion object {
        /** Cap on stored ordinary recents (pinned Home / Work are not counted). */
        const val MAX_RECENTS = 12

        /** Rounds a coordinate to ~11 m so the same spot maps to one stable id. */
        fun coordKey(lat: Double, lon: Double): String =
            String.format(Locale.US, "%.4f,%.4f", lat, lon)
    }
}

