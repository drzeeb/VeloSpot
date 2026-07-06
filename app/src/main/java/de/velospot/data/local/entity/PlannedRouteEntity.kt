package de.velospot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a user-planned multi-waypoint route.
 *
 * Stored in a dedicated database ([de.velospot.data.local.database.PlannedRoutesDatabase])
 * independent of the asset-seeded parking store, so its schema can evolve freely.
 * The waypoints and the routed polyline are persisted as compact JSON so a route
 * is fully self-contained; aggregate stats are denormalised into columns for the
 * list view.
 */
@Entity(tableName = "planned_routes")
data class PlannedRouteEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    /** JSON-serialised `List<RouteWaypoint>` (the stops, in forward order). */
    val waypointsJson: String,
    /** JSON-serialised `List<RoutePoint>` (the routed on-road polyline). */
    val geometryJson: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val energyJoules: Double?,
    val createdAt: Long
)

