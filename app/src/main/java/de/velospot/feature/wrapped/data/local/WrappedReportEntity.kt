package de.velospot.feature.wrapped.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted row for one generated "VeloSpot Wrapped" report.
 *
 * The full [de.velospot.feature.wrapped.domain.WrappedReport] is stored as a
 * Moshi-serialised JSON snapshot in [snapshotJson] so the whole (nested) report —
 * stats, comparison and highlights — round-trips losslessly. The remaining columns
 * are **denormalised copies** of `report.period` / `report.generatedAt` so the
 * history list can be queried and ordered without deserialising every snapshot.
 *
 * Lives in a dedicated database ([WrappedDatabase]) so the whole feature can move
 * to a `:feature:wrapped` Gradle module later without touching the ride store.
 */
@Entity(
    tableName = "wrapped_reports",
    indices = [Index("generatedAt"), Index("periodStart")]
)
data class WrappedReportEntity(
    /** Stable, deterministic id (`"$type-$periodStart-$periodEnd"`) so re-generating
     * the same closed bucket upserts rather than duplicates. */
    @PrimaryKey val id: String,
    /** The [de.velospot.feature.wrapped.domain.WrappedPeriodType] name. */
    val type: String,
    val periodStart: Long,
    val periodEnd: Long,
    val generatedAt: Long,
    /** The full Moshi-serialised `WrappedReport`. */
    val snapshotJson: String
)

