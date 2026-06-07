package de.velospot.data.repository

import de.velospot.data.remote.api.OsrmApi
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.EmptyRouteGeometryException
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.NoRouteFoundException
import de.velospot.domain.model.RoutePoint
import de.velospot.domain.model.RoutingFailedException
import de.velospot.domain.repository.RoutingRepository
import javax.inject.Inject

class RoutingRepositoryImpl @Inject constructor(
    private val osrmApi: OsrmApi
) : RoutingRepository {

    override suspend fun getBikeRoute(from: GeoCoordinate, to: GeoCoordinate): BikeRoute {
        val url = buildString {
            append("https://router.project-osrm.org/route/v1/bicycle/")
            append(from.longitude)
            append(',')
            append(from.latitude)
            append(';')
            append(to.longitude)
            append(',')
            append(to.latitude)
            append("?overview=full&geometries=geojson&alternatives=false&steps=false")
        }

        val response = osrmApi.getBikeRoute(url)
        if (response.code != "Ok") {
            throw RoutingFailedException(response.code)
        }

        val bestRoute = response.routes.firstOrNull()
            ?: throw NoRouteFoundException()

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
            throw EmptyRouteGeometryException()
        }

        return BikeRoute(
            points = points,
            distanceMeters = bestRoute.distance,
            durationSeconds = bestRoute.duration
        )
    }
}



