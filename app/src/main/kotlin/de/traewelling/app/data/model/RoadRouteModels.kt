package de.traewelling.app.data.model

/** A public stop or route vertex. Device location fixes are never routing inputs. */
data class RoutePoint(val latitude: Double, val longitude: Double)

/** Candidate paths between public stop coordinates, never provider ETAs or device traces. */
data class RouteGeometry(
    val from: RoutePoint,
    val to: RoutePoint,
    val alternatives: List<List<RoutePoint>>,
    val fetchedAtMillis: Long
)

/** Existing road contracts retain their source-specific parser and quality limits. */
typealias RoadRouteGeometry = RouteGeometry

enum class GpsGeometrySource { ROAD_MODEL, TRIP_POLYLINE }

/** Bind geometry to ordered visits, so repeated station visits stay distinct. */
data class GpsSegmentGeometry(
    val fromKey: String,
    val toKey: String,
    val geometry: RouteGeometry,
    val source: GpsGeometrySource = GpsGeometrySource.ROAD_MODEL
)
