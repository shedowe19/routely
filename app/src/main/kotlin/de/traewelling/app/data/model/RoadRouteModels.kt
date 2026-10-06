package de.traewelling.app.data.model

/** A public stop or route vertex. Device location fixes are never routing inputs. */
data class RoutePoint(val latitude: Double, val longitude: Double)

/** Candidate road paths between two public stop coordinates, never provider ETAs. */
data class RoadRouteGeometry(
    val from: RoutePoint,
    val to: RoutePoint,
    val alternatives: List<List<RoutePoint>>,
    val fetchedAtMillis: Long
)

/** Bind geometry to ordered visits, so repeated station visits stay distinct. */
data class GpsSegmentGeometry(
    val fromKey: String,
    val toKey: String,
    val geometry: RoadRouteGeometry
)
