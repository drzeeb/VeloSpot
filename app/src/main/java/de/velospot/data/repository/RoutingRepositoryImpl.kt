package de.velospot.data.repository

import de.velospot.data.remote.api.OsrmApi
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.repository.RoutingRepository
import javax.inject.Inject

class RoutingRepositoryImpl @Inject constructor(
    private val osrmApi: OsrmApi
) : RoutingRepository {

    override suspend fun getBikeRoute(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double
    ): BikeRoute {
        val url = buildString {
            append("https://router.project-osrm.org/route/v1/bicycle/")
            append(startLongitude)
            append(',')
            append(startLatitude)
            append(';')
            append(endLongitude)
            append(',')
            append(endLatitude)
            append("?overview=full&geometries=geojson&alternatives=false&steps=false")
        }

        val response = osrmApi.getBikeRoute(url)
        if (response.code != "Ok") {
            throw IllegalStateException("Routing failed: ${response.code}")
        }

        val bestRoute = response.routes.firstOrNull()
            ?: throw IllegalStateException("No bike route returned")

        val points = bestRoute.geometry.coordinates.mapNotNull { coordinate ->
            if (coordinate.size < 2) {
                null
            } else {
                RoutePoint(
                    latitude = coordinate[1],
                    longitude = coordinate[0]
                )
            }
        }

        if (points.isEmpty()) {
            throw IllegalStateException("Route geometry is empty")
        }

        return BikeRoute(
            points = points,
            distanceMeters = bestRoute.distance,
            durationSeconds = bestRoute.duration
        )
    }
}

