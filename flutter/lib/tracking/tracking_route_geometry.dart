import 'dart:math' as math;

import 'models.dart';

enum GpsGeometrySource { roadModel, tripPolyline }

/// A public station or route vertex. Device positions are never routing inputs.
class RoutePoint {
  const RoutePoint(this.latitude, this.longitude);
  final double latitude;
  final double longitude;
  @override
  bool operator ==(Object other) =>
      other is RoutePoint &&
      latitude == other.latitude &&
      longitude == other.longitude;
  @override
  int get hashCode => Object.hash(latitude, longitude);
}

class RouteGeometry {
  RouteGeometry({
    required this.from,
    required this.to,
    required List<List<RoutePoint>> alternatives,
    required this.fetchedAtMillis,
  }) : alternatives = List.unmodifiable(
         alternatives.map((p) => List<RoutePoint>.unmodifiable(p)),
       );
  final RoutePoint from;
  final RoutePoint to;
  final List<List<RoutePoint>> alternatives;
  final int fetchedAtMillis;
  @override
  bool operator ==(Object other) =>
      other is RouteGeometry &&
      from == other.from &&
      to == other.to &&
      fetchedAtMillis == other.fetchedAtMillis &&
      routeAlternativesEqual(alternatives, other.alternatives);
  @override
  int get hashCode => Object.hash(
    from,
    to,
    fetchedAtMillis,
    Object.hashAll(alternatives.map(Object.hashAll)),
  );
}

typedef RoadRouteGeometry = RouteGeometry;

class GpsSegmentGeometry {
  const GpsSegmentGeometry({
    required this.fromKey,
    required this.toKey,
    required this.geometry,
    this.source = GpsGeometrySource.roadModel,
  });
  final String fromKey;
  final String toKey;
  final RouteGeometry geometry;
  final GpsGeometrySource source;
  @override
  bool operator ==(Object other) =>
      other is GpsSegmentGeometry &&
      fromKey == other.fromKey &&
      toKey == other.toKey &&
      geometry == other.geometry &&
      source == other.source;
  @override
  int get hashCode => Object.hash(fromKey, toKey, geometry, source);
}

bool routePointsEqual(List<RoutePoint>? first, List<RoutePoint>? second) {
  if (identical(first, second)) return true;
  if (first == null || second == null || first.length != second.length) {
    return false;
  }
  for (var i = 0; i < first.length; i++) {
    if (first[i] != second[i]) return false;
  }
  return true;
}

bool routeAlternativesEqual(
  List<List<RoutePoint>> first,
  List<List<RoutePoint>> second,
) {
  if (first.length != second.length) return false;
  for (var i = 0; i < first.length; i++) {
    if (!routePointsEqual(first[i], second[i])) return false;
  }
  return true;
}

class TrackingGeometryPath {
  TrackingGeometryPath({
    required List<RoutePoint> points,
    required List<double> lengths,
    required this.length,
  }) : points = List.unmodifiable(points),
       lengths = List.unmodifiable(lengths);
  final List<RoutePoint> points;
  final List<double> lengths;
  final double length;
  @override
  bool operator ==(Object other) {
    if (other is! TrackingGeometryPath ||
        length != other.length ||
        !routePointsEqual(points, other.points) ||
        lengths.length != other.lengths.length) {
      return false;
    }
    for (var i = 0; i < lengths.length; i++) {
      if (lengths[i] != other.lengths[i]) return false;
    }
    return true;
  }

  @override
  int get hashCode =>
      Object.hash(Object.hashAll(points), Object.hashAll(lengths), length);
}

class TrackingGeometrySegment {
  TrackingGeometrySegment({
    required this.source,
    required List<TrackingGeometryPath> paths,
  }) : paths = List.unmodifiable(paths);
  final GpsGeometrySource source;
  final List<TrackingGeometryPath> paths;
}

class TrackingGeometryProjection {
  const TrackingGeometryProjection({
    required this.fraction,
    required this.length,
    required this.path,
    this.across = 0,
  });
  final double fraction;
  final double length;
  final TrackingGeometryPath path;
  final double across;
}

class TrackingGeometryResult {
  const TrackingGeometryResult(
    this.projection, {
    this.ambiguous = false,
    this.beforeOrigin = false,
  });
  final TrackingGeometryProjection? projection;
  final bool ambiguous;
  final bool beforeOrigin;
}

class _RemainingVertex {
  const _RemainingVertex(this.fraction, this.point);
  final double fraction;
  final RoutePoint point;
}

class _GeometryCandidate {
  const _GeometryCandidate(
    this.projection,
    this.supportedEndpoint,
    this.beforeOrigin,
  );
  final TrackingGeometryProjection projection;
  final bool supportedEndpoint;
  final bool beforeOrigin;
}

/// Validated route shapes; never a nearest-track or timetable visit matcher.
class TrackingRouteGeometry {
  static const _earthRadius = 6371000.0;
  static const _sharedRemainingToleranceMeters = 1.0;
  static double _radians(double degrees) => degrees * math.pi / 180;
  static bool _valid(RoutePoint point) =>
      point.latitude.isFinite &&
      point.latitude >= -90 &&
      point.latitude <= 90 &&
      point.longitude.isFinite &&
      point.longitude >= -180 &&
      point.longitude <= 180;
  static bool _coordinates(TrackingStop stop) =>
      stop.latitude != null &&
      stop.longitude != null &&
      _valid(RoutePoint(stop.latitude!, stop.longitude!));
  static double distance(RoutePoint a, RoutePoint b) {
    final x =
        _radians(b.longitude - a.longitude) *
        _earthRadius *
        math.cos(_radians((a.latitude + b.latitude) / 2));
    final y = _radians(b.latitude - a.latitude) * _earthRadius;
    return math.sqrt(x * x + y * y);
  }

  static TrackingGeometrySegment? prepare(
    RouteGeometry? geometry,
    TrackingStop from,
    TrackingStop to,
    GpsGeometrySource source,
    int nowMillis,
  ) {
    final rail = source == GpsGeometrySource.tripPolyline;
    if (geometry == null ||
        !_coordinates(from) ||
        !_coordinates(to) ||
        geometry.fetchedAtMillis <= 0 ||
        nowMillis < geometry.fetchedAtMillis ||
        nowMillis - geometry.fetchedAtMillis > (rail ? 900000 : 86400000) ||
        geometry.alternatives.isEmpty ||
        geometry.alternatives.length > (rail ? 1 : 3) ||
        !_valid(geometry.from) ||
        !_valid(geometry.to)) {
      return null;
    }
    final first = RoutePoint(from.latitude!, from.longitude!);
    final endpoint = RoutePoint(to.latitude!, to.longitude!);
    if (distance(geometry.from, first) > 1 ||
        distance(geometry.to, endpoint) > 1) {
      return null;
    }
    final chord = distance(first, endpoint);
    final paths = <TrackingGeometryPath>[];
    for (final points in geometry.alternatives) {
      if (points.length < 2 ||
          points.length > (rail ? 40000 : 5000) ||
          points.any((p) => !_valid(p)) ||
          distance(points.first, first) > (rail ? 250 : 150) ||
          distance(points.last, endpoint) > (rail ? 250 : 150)) {
        continue;
      }
      if (rail) {
        var invalidEdge = false;
        for (var i = 1; i < points.length; i++) {
          if (distance(points[i - 1], points[i]) > 10000) {
            invalidEdge = true;
            break;
          }
        }
        if (invalidEdge) continue;
      }
      // Physical station arrival zones are separate from provider snapping.
      final connected = <RoutePoint>[first];
      for (final point in points) {
        if (distance(connected.last, point) > .01) connected.add(point);
      }
      if (distance(connected.last, endpoint) > .01) connected.add(endpoint);
      final lengths = <double>[];
      for (var i = 1; i < connected.length; i++) {
        lengths.add(distance(connected[i - 1], connected[i]));
      }
      final length = lengths.fold(0.0, (sum, v) => sum + v);
      if (connected.length < 2 ||
          !length.isFinite ||
          length < 100 ||
          length > (rail ? 500000 : 50000) ||
          (!rail && length > chord * 5 + 1000)) {
        continue;
      }
      paths.add(
        TrackingGeometryPath(
          points: connected,
          lengths: lengths,
          length: length,
        ),
      );
    }
    return paths.isEmpty
        ? null
        : TrackingGeometrySegment(source: source, paths: paths);
  }

  /// Nearby nonadjacent branches must agree in chainage; nearest can jump loops.
  static TrackingGeometryResult project(
    TrackingGeometryPath path,
    LocationFix fix, {
    bool arrivalEndpoint = false,
  }) {
    var cumulative = 0.0;
    final candidates = <_GeometryCandidate>[];
    final corridor = math.max(100.0, fix.accuracyMeters * 2);
    final supportedEnd =
        arrivalEndpoint &&
        distance(RoutePoint(fix.latitude, fix.longitude), path.points.last) +
                fix.accuracyMeters <=
            120;
    for (var i = 0; i < path.lengths.length; i++) {
      final from = path.points[i],
          to = path.points[i + 1],
          length = path.lengths[i];
      if (length <= .01) continue;
      final scale =
          _earthRadius * math.cos(_radians((from.latitude + to.latitude) / 2));
      final x = _radians(to.longitude - from.longitude) * scale;
      final y = _radians(to.latitude - from.latitude) * _earthRadius;
      final fx = _radians(fix.longitude - from.longitude) * scale;
      final fy = _radians(fix.latitude - from.latitude) * _earthRadius;
      final along = (fx * x + fy * y) / (length * length);
      final clamped = along.clamp(0.0, 1.0);
      final across = math.sqrt(
        math.pow(fx - clamped * x, 2) + math.pow(fy - clamped * y, 2),
      );
      if (across <= corridor) {
        final supportedEndpoint =
            !(i == 0 && along < 0) &&
            !(i == path.lengths.length - 1 && along > 1 && !supportedEnd);
        candidates.add(
          _GeometryCandidate(
            TrackingGeometryProjection(
              fraction: (cumulative + clamped * length) / path.length,
              length: path.length,
              path: path,
              across: across,
            ),
            supportedEndpoint,
            i == 0 && along < 0,
          ),
        );
      }
      cumulative += length;
    }
    if (candidates.isEmpty) return const TrackingGeometryResult(null);
    // Kotlin's false-before-true tie ordering rejects unsupported extrapolation.
    candidates.sort((a, b) {
      final distanceOrder = a.projection.across.compareTo(b.projection.across);
      if (distanceOrder != 0) return distanceOrder;
      return (a.supportedEndpoint ? 1 : 0).compareTo(
        b.supportedEndpoint ? 1 : 0,
      );
    });
    final nearest = candidates.first, projection = nearest.projection;
    final plausible = candidates.where(
      (c) =>
          c.projection.across <=
          projection.across + math.max(10.0, fix.accuracyMeters * 2),
    );
    if (plausible.any(
      (c) =>
          (c.projection.fraction - projection.fraction).abs() * path.length >
          math.max(50.0, fix.accuracyMeters * 2),
    )) {
      return const TrackingGeometryResult(null, ambiguous: true);
    }
    if (!nearest.supportedEndpoint) {
      return TrackingGeometryResult(null, beforeOrigin: nearest.beforeOrigin);
    }
    return TrackingGeometryResult(projection);
  }

  /// Compare every pair at all remaining vertices, preventing unsampled detours.
  static bool sharedRemainingPath(
    List<TrackingGeometryProjection> projections,
  ) {
    if (projections.length < 2 || projections.length > 3) return false;
    final remaining = <List<_RemainingVertex>>[];
    for (final projection in projections) {
      final vertices = _remainingVertices(projection);
      if (vertices == null) return false;
      remaining.add(vertices);
    }
    for (var i = 0; i < remaining.length; i++) {
      for (var j = i + 1; j < remaining.length; j++) {
        if (!_sameRemainingShape(remaining[i], remaining[j])) return false;
      }
    }
    return true;
  }

  static List<_RemainingVertex>? _remainingVertices(
    TrackingGeometryProjection projection,
  ) {
    final path = projection.path;
    if (!projection.fraction.isFinite ||
        projection.fraction < 0 ||
        projection.fraction > 1 ||
        !projection.length.isFinite ||
        !path.length.isFinite ||
        path.length <= 0 ||
        (projection.length - path.length).abs() > .01 ||
        path.points.length < 2 ||
        path.points.length > 5002 ||
        path.lengths.length != path.points.length - 1 ||
        path.points.any((p) => !_valid(p)) ||
        path.lengths.any((v) => !v.isFinite || v <= 0) ||
        (path.lengths.fold(0.0, (sum, v) => sum + v) - path.length).abs() >
            .01) {
      return null;
    }
    final travelled = projection.fraction * path.length,
        remaining = path.length - travelled;
    if (remaining <= .01) return null;
    final vertices = <_RemainingVertex>[];
    var cumulative = 0.0;
    for (var i = 0; i < path.lengths.length; i++) {
      final next = cumulative + path.lengths[i];
      if (vertices.isEmpty && next > travelled) {
        final fraction = ((travelled - cumulative) / path.lengths[i]).clamp(
          0.0,
          1.0,
        );
        vertices.add(
          _RemainingVertex(
            0,
            _interpolate(path.points[i], path.points[i + 1], fraction),
          ),
        );
      }
      if (vertices.isNotEmpty) {
        vertices.add(
          _RemainingVertex(
            ((next - travelled) / remaining).clamp(0.0, 1.0),
            path.points[i + 1],
          ),
        );
      }
      cumulative = next;
    }
    return vertices.length < 2 ? null : vertices;
  }

  static bool _sameRemainingShape(
    List<_RemainingVertex> first,
    List<_RemainingVertex> second,
  ) {
    final breakpoints = {
      ...first.map((v) => v.fraction),
      ...second.map((v) => v.fraction),
    }.toList()..sort();
    var firstEdge = 0, secondEdge = 0;
    for (final fraction in breakpoints) {
      while (firstEdge < first.length - 2 &&
          first[firstEdge + 1].fraction < fraction) {
        firstEdge++;
      }
      while (secondEdge < second.length - 2 &&
          second[secondEdge + 1].fraction < fraction) {
        secondEdge++;
      }
      if (distance(
            _interpolateAt(first, firstEdge, fraction),
            _interpolateAt(second, secondEdge, fraction),
          ) >
          _sharedRemainingToleranceMeters) {
        return false;
      }
    }
    return true;
  }

  static RoutePoint _interpolateAt(
    List<_RemainingVertex> vertices,
    int edge,
    double fraction,
  ) {
    final from = vertices[edge],
        to = vertices[edge + 1],
        span = to.fraction - from.fraction;
    final along = span > 0
        ? ((fraction - from.fraction) / span).clamp(0.0, 1.0)
        : 1.0;
    return _interpolate(from.point, to.point, along);
  }

  static RoutePoint _interpolate(
    RoutePoint from,
    RoutePoint to,
    double fraction,
  ) => RoutePoint(
    from.latitude + (to.latitude - from.latitude) * fraction,
    from.longitude + (to.longitude - from.longitude) * fraction,
  );
}
