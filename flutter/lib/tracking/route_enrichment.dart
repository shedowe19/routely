import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import '../data/models.dart' as api;
import 'models.dart';
import 'tracking_route_geometry.dart';

/// Only public timetable identity. Realtime/platform/device positions are absent.
class TransitRouteVisit {
  const TransitRouteVisit({
    required this.key,
    this.stationId,
    this.stationUuid,
    this.arrivalPlannedMillis,
    this.departurePlannedMillis,
    required this.point,
    this.cancelled = false,
  });
  final String key;
  final int? stationId, arrivalPlannedMillis, departurePlannedMillis;
  final String? stationUuid;
  final RoutePoint point;
  final bool cancelled;
  @override
  bool operator ==(Object other) =>
      other is TransitRouteVisit &&
      key == other.key &&
      stationId == other.stationId &&
      stationUuid == other.stationUuid &&
      arrivalPlannedMillis == other.arrivalPlannedMillis &&
      departurePlannedMillis == other.departurePlannedMillis &&
      point == other.point &&
      cancelled == other.cancelled;
  @override
  int get hashCode => Object.hash(
    key,
    stationId,
    stationUuid,
    arrivalPlannedMillis,
    departurePlannedMillis,
    point,
    cancelled,
  );
}

class TransitRouteRequest {
  TransitRouteRequest({
    required this.statusId,
    required this.tripIdentity,
    required List<TransitRouteVisit> visits,
  }) : visits = List.unmodifiable(visits);
  final int statusId;
  final String tripIdentity;
  final List<TransitRouteVisit> visits;
  @override
  bool operator ==(Object other) {
    if (other is! TransitRouteRequest ||
        statusId != other.statusId ||
        tripIdentity != other.tripIdentity ||
        visits.length != other.visits.length) {
      return false;
    }
    for (var i = 0; i < visits.length; i++) {
      if (visits[i] != other.visits[i]) return false;
    }
    return true;
  }

  @override
  int get hashCode =>
      Object.hash(statusId, tripIdentity, Object.hashAll(visits));
}

class TransitRouteGeometry {
  TransitRouteGeometry({
    required this.request,
    required List<GpsSegmentGeometry> segments,
    required this.fetchedAtMillis,
  }) : segments = List.unmodifiable(segments);
  final TransitRouteRequest request;
  final List<GpsSegmentGeometry> segments;
  final int fetchedAtMillis;
}

/// Strict validation of OSRM's full GeoJSON response.
class RoadRouteParser {
  static const maxBodyBytes = 2 * 1024 * 1024,
      maxPointsPerRoute = 5000,
      maxAlternatives = 3;
  static const maxSnapMeters = 150.0, maxRouteMeters = 50000.0;
  static bool validPoint(RoutePoint point) =>
      validCoordinates(point.latitude, point.longitude);
  static double distance(RoutePoint from, RoutePoint to) =>
      distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude);
  static RouteGeometry? parse(
    String json,
    RoutePoint from,
    RoutePoint to,
    int nowMillis,
  ) {
    if (!validPoint(from) ||
        !validPoint(to) ||
        nowMillis <= 0 ||
        json.length > maxBodyBytes ||
        utf8.encode(json).length > maxBodyBytes) {
      return null;
    }
    final chord = distance(from, to);
    if (chord < 100 || chord > maxRouteMeters) return null;
    try {
      final root = _object(jsonDecode(json));
      if (root == null || root['code'] != 'Ok') return null;
      final waypoints = _array(root['waypoints']),
          routes = _array(root['routes']);
      if (waypoints == null ||
          waypoints.length != 2 ||
          !_validWaypoint(waypoints[0], from) ||
          !_validWaypoint(waypoints[1], to) ||
          routes == null) {
        return null;
      }
      final alternatives = <List<RoutePoint>>[];
      for (final route in routes.take(maxAlternatives)) {
        final path = _parseRoute(route, from, to, chord);
        if (path != null) alternatives.add(path);
      }
      return alternatives.isEmpty
          ? null
          : RouteGeometry(
              from: from,
              to: to,
              alternatives: alternatives,
              fetchedAtMillis: nowMillis,
            );
    } catch (_) {
      return null;
    }
  }

  static bool _validWaypoint(Object? value, RoutePoint expected) {
    final waypoint = _object(value);
    if (waypoint == null) return false;
    final d = _number(waypoint['distance']),
        location = _point(waypoint['location']);
    return d != null &&
        d >= 0 &&
        d <= maxSnapMeters &&
        location != null &&
        distance(expected, location) <= maxSnapMeters;
  }

  static List<RoutePoint>? _parseRoute(
    Object? value,
    RoutePoint from,
    RoutePoint to,
    double chord,
  ) {
    final route = _object(value);
    if (route == null) return null;
    final declared = _number(route['distance']),
        duration = _number(route['duration']);
    if (declared == null ||
        duration == null ||
        declared < 100 ||
        declared > maxRouteMeters ||
        duration <= 0 ||
        duration > 86400 ||
        declared / duration > 100) {
      return null;
    }
    final geometry = _object(route['geometry']);
    if (geometry == null || geometry['type'] != 'LineString') return null;
    final coordinates = _array(geometry['coordinates']);
    if (coordinates == null ||
        coordinates.length < 2 ||
        coordinates.length > maxPointsPerRoute) {
      return null;
    }
    final points = <RoutePoint>[];
    for (final coordinate in coordinates) {
      final point = _point(coordinate);
      if (point == null) return null;
      points.add(point);
    }
    if (distance(from, points.first) > maxSnapMeters ||
        distance(to, points.last) > maxSnapMeters) {
      return null;
    }
    var total = 0.0;
    for (var i = 1; i < points.length; i++) {
      total += distance(points[i - 1], points[i]);
    }
    if (!total.isFinite ||
        total < 100 ||
        total > maxRouteMeters ||
        total > chord * 5 + 1000 ||
        declared > chord * 5 + 1000 ||
        (total - declared).abs() > math.max(150.0, total * .10)) {
      return null;
    }
    return points;
  }
}

class _TransitProjection {
  const _TransitProjection(this.point, this.chainage, this.distance);
  final RoutePoint point;
  final double chainage, distance;
}

/// Native lines can contain station chords; only unique curved sections are used.
class TransitRouteParser {
  static const maxBodyBytes = 4 * 1024 * 1024,
      maxPoints = 40000,
      maxVisits = 256;
  static const maxFullMeters = 3000000.0,
      maxSegmentMeters = 500000.0,
      maxEdgeMeters = 10000.0,
      maxStopSnapMeters = 250.0,
      stopDistanceWindowMeters = 15.0,
      _candidateClusterMeters = 50.0,
      _curveEvidenceMeters = 15.0,
      _earthRadiusMeters = 6371000.0;
  static const _maxStopCandidates = 16;
  static bool validRequest(TransitRouteRequest request) =>
      request.statusId > 0 &&
      request.tripIdentity.trim().isNotEmpty &&
      request.tripIdentity.length <= 1024 &&
      request.visits.length >= 2 &&
      request.visits.length <= maxVisits &&
      request.visits.map((v) => v.key).toSet().length ==
          request.visits.length &&
      request.visits.every(
        (v) =>
            v.key.trim().isNotEmpty &&
            v.key.length <= 512 &&
            RoadRouteParser.validPoint(v.point),
      ) &&
      !request.visits.first.cancelled &&
      !request.visits.last.cancelled;
  static TransitRouteGeometry? parse(
    String json,
    TransitRouteRequest request,
    int nowMillis,
  ) {
    if (!validRequest(request) ||
        nowMillis <= 0 ||
        json.length > maxBodyBytes ||
        utf8.encode(json).length > maxBodyBytes) {
      return null;
    }
    try {
      final root = _object(jsonDecode(json)), data = _object(root?['data']);
      if (data == null || data['type'] != 'FeatureCollection') return null;
      final features = _array(data['features']);
      if (features == null || features.length != 1) return null;
      final feature = _object(features.single),
          properties = _object(feature?['properties']);
      // JSON integer identity: a fractional or string statusId is rejected.
      if (feature == null ||
          feature['type'] != 'Feature' ||
          properties?['statusId'] is! int ||
          properties!['statusId'] != request.statusId) {
        return null;
      }
      final geometry = _object(feature['geometry']);
      if (geometry == null || geometry['type'] != 'LineString') return null;
      final coordinates = _array(geometry['coordinates']);
      if (coordinates == null ||
          coordinates.length < 3 ||
          coordinates.length > maxPoints) {
        return null;
      }
      final points = <RoutePoint>[];
      for (final coordinate in coordinates) {
        final point = _point(coordinate);
        if (point == null) return null;
        if (points.isEmpty ||
            RoadRouteParser.distance(points.last, point) >= .01) {
          points.add(point);
        }
      }
      if (points.length < 3 ||
          RoadRouteParser.distance(request.visits.first.point, points.first) >
              maxStopSnapMeters ||
          RoadRouteParser.distance(request.visits.last.point, points.last) >
              maxStopSnapMeters) {
        return null;
      }
      final chainages = List<double>.filled(points.length, 0);
      for (var i = 1; i < points.length; i++) {
        final edge = RoadRouteParser.distance(points[i - 1], points[i]);
        if (!edge.isFinite || edge > maxEdgeMeters) return null;
        chainages[i] = chainages[i - 1] + edge;
      }
      if (chainages.last < 100 || chainages.last > maxFullMeters) return null;
      final candidates = <List<_TransitProjection>>[];
      for (var i = 0; i < request.visits.length; i++) {
        final found = _findCandidates(
          request.visits[i].point,
          points,
          chainages,
        );
        if (found == null) return null;
        final eligible = found
            .where(
              (c) =>
                  (i != 0 || c.chainage <= maxStopSnapMeters) &&
                  (i != request.visits.length - 1 ||
                      chainages.last - c.chainage <= maxStopSnapMeters),
            )
            .toList();
        if (eligible.isEmpty) return null;
        final nearest = eligible.map((c) => c.distance).reduce(math.min);
        candidates.add(
          eligible
              .where((c) => c.distance <= nearest + stopDistanceWindowMeters)
              .toList(),
        );
      }
      final bindings = _uniqueBinding(candidates);
      if (bindings == null) return null;
      final active = [
        for (var i = 0; i < request.visits.length; i++)
          if (!request.visits[i].cancelled) i,
      ];
      final segments = <GpsSegmentGeometry>[];
      for (var i = 1; i < active.length; i++) {
        final fromIndex = active[i - 1],
            toIndex = active[i],
            from = bindings[fromIndex],
            to = bindings[toIndex];
        final length = to.chainage - from.chainage;
        if (length < 100 || length > maxSegmentMeters) continue;
        var allCurved = true;
        for (var index = fromIndex; index < toIndex; index++) {
          if (!_hasCurve(
            _slice(bindings[index], bindings[index + 1], points, chainages),
          )) {
            allCurved = false;
            break;
          }
        }
        if (!allCurved) continue;
        final path = _slice(from, to, points, chainages);
        if (path.length < 3) continue;
        segments.add(
          GpsSegmentGeometry(
            fromKey: request.visits[fromIndex].key,
            toKey: request.visits[toIndex].key,
            geometry: RouteGeometry(
              from: request.visits[fromIndex].point,
              to: request.visits[toIndex].point,
              alternatives: [path],
              fetchedAtMillis: nowMillis,
            ),
            source: GpsGeometrySource.tripPolyline,
          ),
        );
      }
      return segments.isEmpty
          ? null
          : TransitRouteGeometry(
              request: request,
              segments: segments,
              fetchedAtMillis: nowMillis,
            );
    } catch (_) {
      return null;
    }
  }

  static List<_TransitProjection>? _findCandidates(
    RoutePoint stop,
    List<RoutePoint> points,
    List<double> chainages,
  ) {
    final result = <_TransitProjection>[];
    _TransitProjection? before, previous;
    bool addMinimum(
      _TransitProjection current,
      double previousDistance,
      double nextDistance,
    ) {
      if (current.distance > maxStopSnapMeters ||
          current.distance > previousDistance + .001 ||
          current.distance > nextDistance + .001) {
        return true;
      }
      if (result.isNotEmpty &&
          current.chainage - result.last.chainage <= _candidateClusterMeters) {
        if (current.distance < result.last.distance) {
          result[result.length - 1] = current;
        }
      } else {
        result.add(current);
      }
      return result.length <= _maxStopCandidates;
    }

    for (var i = 0; i < points.length - 1; i++) {
      final projected = _project(
        stop,
        points[i],
        points[i + 1],
        chainages[i],
        chainages[i + 1],
      );
      if (previous != null &&
          !addMinimum(
            previous,
            before?.distance ?? double.infinity,
            projected.distance,
          )) {
        return null;
      }
      before = previous;
      previous = projected;
    }
    if (previous != null &&
        !addMinimum(
          previous,
          before?.distance ?? double.infinity,
          double.infinity,
        )) {
      return null;
    }
    return result.isEmpty ? null : result;
  }

  /// Count complete ordered assignments up to two; greedy nearest fails at loops.
  static List<_TransitProjection>? _uniqueBinding(
    List<List<_TransitProjection>> candidates,
  ) {
    final counts = candidates
        .map((c) => List<int>.filled(c.length, 0))
        .toList();
    final predecessors = candidates
        .map((c) => List<int>.filled(c.length, -1))
        .toList();
    counts.first.fillRange(0, counts.first.length, 1);
    for (var visit = 1; visit < candidates.length; visit++) {
      for (var current = 0; current < candidates[visit].length; current++) {
        for (
          var previous = 0;
          previous < candidates[visit - 1].length;
          previous++
        ) {
          if (candidates[visit][current].chainage <=
                  candidates[visit - 1][previous].chainage + 1 ||
              counts[visit - 1][previous] == 0) {
            continue;
          }
          predecessors[visit][current] =
              counts[visit][current] == 0 && counts[visit - 1][previous] == 1
              ? previous
              : -1;
          counts[visit][current] = math.min(
            2,
            counts[visit][current] + counts[visit - 1][previous],
          );
        }
      }
    }
    if (counts.last.fold(0, (sum, v) => sum + v) != 1) return null;
    var candidate = counts.last.indexOf(1);
    final result = <_TransitProjection>[];
    for (var visit = candidates.length - 1; visit >= 0; visit--) {
      result.add(candidates[visit][candidate]);
      if (visit > 0) {
        candidate = predecessors[visit][candidate];
        if (candidate < 0) return null;
      }
    }
    return result.reversed.toList();
  }

  static List<RoutePoint> _slice(
    _TransitProjection from,
    _TransitProjection to,
    List<RoutePoint> points,
    List<double> chainages,
  ) {
    final result = <RoutePoint>[from.point];
    for (var i = 0; i < points.length; i++) {
      if (chainages[i] > from.chainage + .01 &&
          chainages[i] < to.chainage - .01) {
        result.add(points[i]);
      }
    }
    if (RoadRouteParser.distance(result.last, to.point) >= .01) {
      result.add(to.point);
    }
    return result;
  }

  static bool _hasCurve(List<RoutePoint> path) {
    if (path.length < 3) return false;
    return path
        .sublist(1, path.length - 1)
        .any(
          (point) =>
              _project(point, path.first, path.last, 0, 1).distance >=
                  _curveEvidenceMeters &&
              _greatCircleCrossTrackMeters(point, path.first, path.last) >=
                  _curveEvidenceMeters,
        );
  }

  static double _greatCircleCrossTrackMeters(
    RoutePoint point,
    RoutePoint first,
    RoutePoint last,
  ) {
    if (RoadRouteParser.distance(first, last) < 1) {
      return RoadRouteParser.distance(first, point);
    }
    final angular = RoadRouteParser.distance(first, point) / _earthRadiusMeters;
    double bearing(RoutePoint to) {
      final lat1 = first.latitude * math.pi / 180,
          lat2 = to.latitude * math.pi / 180;
      final lon =
          _longitudeDelta(to.longitude - first.longitude) * math.pi / 180;
      return math.atan2(
        math.sin(lon) * math.cos(lat2),
        math.cos(lat1) * math.sin(lat2) -
            math.sin(lat1) * math.cos(lat2) * math.cos(lon),
      );
    }

    return math
            .asin(
              (math.sin(angular) * math.sin(bearing(point) - bearing(last)))
                  .clamp(-1.0, 1.0),
            )
            .abs() *
        _earthRadiusMeters;
  }

  static _TransitProjection _project(
    RoutePoint stop,
    RoutePoint first,
    RoutePoint last,
    double start,
    double end,
  ) {
    final radians = math.pi / 180,
        scaleX =
            _earthRadiusMeters *
            math.cos(stop.latitude * math.pi / 180) *
            math.pi /
            180;
    final scaleY = _earthRadiusMeters * radians;
    final ax = _longitudeDelta(first.longitude - stop.longitude) * scaleX,
        ay = (first.latitude - stop.latitude) * scaleY;
    final bx = _longitudeDelta(last.longitude - stop.longitude) * scaleX,
        by = (last.latitude - stop.latitude) * scaleY;
    final dx = bx - ax, dy = by - ay, denominator = dx * dx + dy * dy;
    final fraction = denominator <= 0
        ? 0.0
        : (-(ax * dx + ay * dy) / denominator).clamp(0.0, 1.0);
    final lon = _longitudeDelta(
      first.longitude +
          _longitudeDelta(last.longitude - first.longitude) * fraction,
    );
    return _TransitProjection(
      RoutePoint(
        first.latitude + (last.latitude - first.latitude) * fraction,
        lon,
      ),
      start + (end - start) * fraction,
      math.sqrt(
        math.pow(ax + dx * fraction, 2) + math.pow(ay + dy * fraction, 2),
      ),
    );
  }

  static double _longitudeDelta(double value) =>
      ((value + 180) % 360 + 360) % 360 - 180;
}

Map<String, dynamic>? _object(Object? value) =>
    value is Map<String, dynamic> ? value : null;
List<dynamic>? _array(Object? value) => value is List ? value : null;
double? _number(Object? value) =>
    value is num && value.isFinite ? value.toDouble() : null;
RoutePoint? _point(Object? value) {
  final pair = _array(value);
  if (pair == null || pair.length != 2) return null;
  final lon = _number(pair[0]), lat = _number(pair[1]);
  if (lon == null || lat == null) return null;
  final point = RoutePoint(lat, lon);
  return RoadRouteParser.validPoint(point) ? point : null;
}

class TransitRouteSelection {
  static const maxGeometryAgeMillis = 900000, refreshAgeMillis = 840000;
  static const _railCategories = {
    'nationalexpress',
    'national',
    'regionalexp',
    'regional',
    'suburban',
    'subway',
    'tram',
  };
  static TransitRouteRequest? request({
    required int statusId,
    required api.Checkin checkin,
    required List<api.Stop> fullStops,
    required List<api.Stop> checkedInStops,
    required List<String> trackingKeys,
  }) {
    if (!_railCategories.contains(checkin.category?.trim().toLowerCase()) ||
        checkin.mode?.trim().toLowerCase() == 'bus' ||
        statusId <= 0 ||
        checkin.trip == null ||
        checkin.trip! <= 0 ||
        checkedInStops.length < 2 ||
        checkedInStops.length > 256 ||
        checkedInStops.length != trackingKeys.length ||
        trackingKeys.any((k) => k.trim().isEmpty) ||
        trackingKeys.toSet().length != trackingKeys.length) {
      return null;
    }
    final origins = <int>[], destinations = <int>[];
    for (var i = 0; i < fullStops.length; i++) {
      if (fullStops[i].matchesStopover(checkin.origin)) origins.add(i);
      if (fullStops[i].matchesStopover(checkin.destination)) {
        destinations.add(i);
      }
    }
    if (origins.length != 1 ||
        destinations.length != 1 ||
        destinations.single <= origins.single) {
      return null;
    }
    final fullRange = fullStops.sublist(
      origins.single,
      destinations.single + 1,
    );
    if (fullRange.length != checkedInStops.length) return null;
    final visits = <TransitRouteVisit>[];
    for (var i = 0; i < checkedInStops.length; i++) {
      final stop = checkedInStops[i];
      if (stop.uuid != null && stop.uuid != trackingKeys[i]) return null;
      final mapped = _visit(stop, trackingKeys[i]);
      if (mapped == null || _visit(fullRange[i], trackingKeys[i]) != mapped) {
        return null;
      }
      visits.add(mapped);
    }
    return TransitRouteRequest(
      statusId: statusId,
      tripIdentity: '${checkin.trip}:${checkin.tripUuid ?? ''}',
      visits: visits,
    );
  }

  static bool usable(
    TransitRouteRequest request,
    TransitRouteGeometry geometry,
    int nowMillis,
  ) {
    if (geometry.request != request ||
        geometry.fetchedAtMillis <= 0 ||
        nowMillis - geometry.fetchedAtMillis < 0 ||
        nowMillis - geometry.fetchedAtMillis > maxGeometryAgeMillis ||
        geometry.segments.isEmpty) {
      return false;
    }
    final active = request.visits.where((v) => !v.cancelled).toList();
    final pairs = <(String, String), (RoutePoint, RoutePoint)>{};
    for (var i = 1; i < active.length; i++) {
      pairs[(active[i - 1].key, active[i].key)] = (
        active[i - 1].point,
        active[i].point,
      );
    }
    final keys = geometry.segments.map((s) => (s.fromKey, s.toKey)).toList();
    if (keys.toSet().length != keys.length) return false;
    return geometry.segments.every((s) {
      final endpoints = pairs[(s.fromKey, s.toKey)];
      return endpoints != null &&
          s.source == GpsGeometrySource.tripPolyline &&
          s.geometry.from == endpoints.$1 &&
          s.geometry.to == endpoints.$2 &&
          s.geometry.fetchedAtMillis == geometry.fetchedAtMillis &&
          s.geometry.alternatives.length == 1 &&
          s.geometry.alternatives.single.length >= 2 &&
          s.geometry.alternatives.single.every(RoadRouteParser.validPoint);
    });
  }

  static TransitRouteVisit? _visit(api.Stop stop, String key) {
    final station = stop.station,
        lat = stop.station?.latitude,
        lon = stop.station?.longitude;
    if (station == null || lat == null || lon == null) return null;
    final point = RoutePoint(lat, lon);
    if (!RoadRouteParser.validPoint(point)) return null;
    final arrival = _plannedTime(stop.arrivalPlanned),
        departure = _plannedTime(stop.departurePlanned);
    if ((stop.arrivalPlanned != null && arrival == null) ||
        (stop.departurePlanned != null && departure == null)) {
      return null;
    }
    return TransitRouteVisit(
      key: key,
      stationId: station.id,
      stationUuid: station.uuid,
      arrivalPlannedMillis: arrival,
      departurePlannedMillis: departure,
      point: point,
      cancelled: stop.cancelled,
    );
  }

  static int? _plannedTime(String? value) {
    final millis = parseMillis(value);
    return millis != null && millis > 0 ? millis : null;
  }
}

class TransitRouteLease {
  const TransitRouteLease({
    required this.statusId,
    required this.generation,
    required this.sessionRevision,
    required this.request,
  });
  final int statusId, generation;
  final String sessionRevision;
  final TransitRouteRequest request;
  @override
  bool operator ==(Object other) =>
      other is TransitRouteLease &&
      statusId == other.statusId &&
      generation == other.generation &&
      sessionRevision == other.sessionRevision &&
      request == other.request;
  @override
  int get hashCode =>
      Object.hash(statusId, generation, sessionRevision, request);
}

/// Delayed responses can fill only their still-owned session and full-route basis.
class TransitRouteTrackingCache {
  TransitRouteLease? _lease;
  TransitRouteGeometry? _geometry;
  TransitRouteLease? get lease => _lease;
  bool bind(TransitRouteLease? owner) {
    if (_lease == owner) return false;
    _lease = owner;
    _geometry = null;
    return true;
  }

  bool adopt(
    TransitRouteLease owner,
    TransitRouteGeometry loaded,
    int nowMillis,
  ) {
    if (_lease != owner ||
        !TransitRouteSelection.usable(owner.request, loaded, nowMillis)) {
      return false;
    }
    _geometry = loaded;
    return true;
  }

  List<GpsSegmentGeometry> segments(int nowMillis) {
    final owner = _lease, geometry = _geometry;
    return owner != null &&
            geometry != null &&
            TransitRouteSelection.usable(owner.request, geometry, nowMillis)
        ? geometry.segments
        : const [];
  }

  bool needsRefresh(int nowMillis) {
    final owner = _lease, loaded = _geometry;
    if (owner == null) return false;
    if (loaded == null) return true;
    return !TransitRouteSelection.usable(owner.request, loaded, nowMillis) ||
        nowMillis - loaded.fetchedAtMillis >=
            TransitRouteSelection.refreshAgeMillis;
  }
}

final _processClock = Stopwatch()..start();
int _monotonicMillis() => _processClock.elapsedMilliseconds;
Future<void> _defaultWaitMillis(int millis) =>
    Future<void>.delayed(Duration(milliseconds: millis));

/// Anonymous fetch result; outages do not prove the public pair has no route.
class RoadRouteFetchResult {
  const RoadRouteFetchResult(
    this.geometry, {
    this.transientFailure = false,
    this.retryAfterMillis,
  });
  final RouteGeometry? geometry;
  final bool transientFailure;
  final int? retryAfterMillis;
}

class RoadRouteRateLimiter {
  RoadRouteRateLimiter({
    int Function()? nowMillis,
    Future<void> Function(int)? waitMillis,
  }) : _nowMillis = nowMillis ?? _monotonicMillis,
       _waitMillis = waitMillis ?? _waitMillisDefault;
  final int Function() _nowMillis;
  final Future<void> Function(int) _waitMillis;
  Future<void> _tail = Future.value();
  int? _lastStartMillis, _retryAfterMillis;
  Future<void> awaitTurn() {
    final completed = Completer<void>();
    final previous = _tail;
    _tail = completed.future;
    return () async {
      try {
        await previous;
        while (true) {
          final now = _nowMillis();
          final remaining = math.max(
            _lastStartMillis == null ? 0 : 1000 - (now - _lastStartMillis!),
            _retryAfterMillis == null ? 0 : _retryAfterMillis! - now,
          );
          if (remaining <= 0) break;
          await _waitMillis(remaining);
        }
        _lastStartMillis = _nowMillis();
      } finally {
        completed.complete();
      }
    }();
  }

  void postpone(int millis) {
    final delay = millis.clamp(
      RoadRouteStore.transientFailureTtlMillis,
      RoadRouteStore.failureTtlMillis,
    );
    final deadline = _nowMillis() + delay;
    if (_retryAfterMillis == null || deadline > _retryAfterMillis!) {
      _retryAfterMillis = deadline;
    }
  }

  static Future<void> _waitMillisDefault(int millis) =>
      _defaultWaitMillis(millis);
}

class _RoadEntry {
  const _RoadEntry(this.geometry, this.expiresAtMillis);
  final RouteGeometry? geometry;
  final int expiresAtMillis;
}

/// Bounded RAM-only cache. Shared jobs are independent of any awaiting caller.
class RoadRouteStore {
  RoadRouteStore({
    required this.fetch,
    RoadRouteRateLimiter? limiter,
    int Function()? nowMillis,
    int Function()? nowWallMillis,
    this.cacheLimit = 64,
    this.inFlightLimit = 16,
  }) : limiter = limiter ?? RoadRouteRateLimiter(),
       nowMillis = nowMillis ?? _monotonicMillis,
       nowWallMillis = nowWallMillis ?? _wallMillis {
    if (cacheLimit <= 0 || inFlightLimit <= 0) {
      throw ArgumentError('Cache limits must be positive');
    }
  }
  static const successTtlMillis = 86400000,
      failureTtlMillis = 900000,
      transientFailureTtlMillis = 60000;
  final Future<RoadRouteFetchResult> Function(RoutePoint, RoutePoint) fetch;
  final RoadRouteRateLimiter limiter;
  final int Function() nowMillis, nowWallMillis;
  final int cacheLimit, inFlightLimit;
  final _cache = <(RoutePoint, RoutePoint), _RoadEntry>{};
  final _inFlight = <(RoutePoint, RoutePoint), Future<RouteGeometry?>>{};
  Future<RouteGeometry?> getRoute(RoutePoint from, RoutePoint to) {
    final distance = RoadRouteParser.distance(from, to);
    if (!RoadRouteParser.validPoint(from) ||
        !RoadRouteParser.validPoint(to) ||
        distance < 100 ||
        distance > RoadRouteParser.maxRouteMeters) {
      return Future.value();
    }
    final key = (from, to), entry = _cache.remove((from, to));
    if (entry != null) {
      final geometry = entry.geometry,
          wallAge = geometry == null
              ? null
              : nowWallMillis() - geometry.fetchedAtMillis;
      if (nowMillis() < entry.expiresAtMillis &&
          (geometry == null ||
              (geometry.fetchedAtMillis > 0 &&
                  wallAge != null &&
                  wallAge >= 0 &&
                  wallAge <= successTtlMillis))) {
        _cache[key] = entry;
        return Future.value(geometry);
      }
    }
    final existing = _inFlight[key];
    if (existing != null) return existing;
    if (_inFlight.length >= inFlightLimit) return Future.value();
    final completer = Completer<RouteGeometry?>();
    _inFlight[key] = completer.future;
    () async {
      var result = const RoadRouteFetchResult(null, transientFailure: true);
      RouteGeometry? returned;
      try {
        await limiter.awaitTurn();
        result = await fetch(from, to);
        if (result.retryAfterMillis != null) {
          limiter.postpone(result.retryAfterMillis!);
        }
        if (result.geometry != null &&
            (result.geometry!.from != from || result.geometry!.to != to)) {
          result = const RoadRouteFetchResult(null);
        }
        returned = result.geometry;
      } catch (_) {
        returned = null;
      } finally {
        _inFlight.remove(key);
        final ttl = result.geometry != null
            ? successTtlMillis
            : result.transientFailure
            ? result.retryAfterMillis?.clamp(
                    transientFailureTtlMillis,
                    failureTtlMillis,
                  ) ??
                  transientFailureTtlMillis
            : failureTtlMillis;
        _cache[key] = _RoadEntry(result.geometry, nowMillis() + ttl);
        while (_cache.length > cacheLimit) {
          _cache.remove(_cache.keys.first);
        }
        completer.complete(returned);
      }
    }();
    return completer.future;
  }
}

int _wallMillis() => DateTime.now().millisecondsSinceEpoch;

class TransitRouteFetchResult {
  const TransitRouteFetchResult(this.geometry, {this.cacheable = true});
  final TransitRouteGeometry? geometry;
  final bool cacheable;
}

class _TransitEntry {
  const _TransitEntry(
    this.geometry,
    this.expiresAtMillis,
    this.refreshAtMillis,
    this.retryAtMillis,
  );
  final TransitRouteGeometry? geometry;
  final int expiresAtMillis, refreshAtMillis, retryAtMillis;
}

/// Session-owned injectable singleflight with hard expiry and failed soft refresh.
class TransitRouteStore {
  TransitRouteStore({
    required this.fetch,
    int Function()? nowMillis,
    this.cacheLimit = 8,
    this.inFlightLimit = 4,
    this.onClose,
  }) : nowMillis = nowMillis ?? _monotonicMillis {
    if (cacheLimit <= 0 || inFlightLimit <= 0) {
      throw ArgumentError('Cache limits must be positive');
    }
  }
  static const successTtlMillis = 900000,
      failureTtlMillis = 120000,
      refreshAfterMillis = 840000;
  final Future<TransitRouteFetchResult> Function(TransitRouteRequest) fetch;
  final int Function() nowMillis;
  final int cacheLimit, inFlightLimit;
  final void Function()? onClose;
  bool _closed = false;
  final _cache = <TransitRouteRequest, _TransitEntry>{};
  final _inFlight = <TransitRouteRequest, Future<TransitRouteGeometry?>>{};
  Future<TransitRouteGeometry?> getRoute(TransitRouteRequest request) {
    if (_closed || !TransitRouteParser.validRequest(request)) {
      return Future.value();
    }
    final key = TransitRouteRequest(
      statusId: request.statusId,
      tripIdentity: request.tripIdentity,
      visits: request.visits,
    );
    final entry = _cache.remove(key);
    if (entry != null) {
      _cache[key] = entry;
      final now = nowMillis();
      if (now < entry.expiresAtMillis &&
          (now < entry.refreshAtMillis || now < entry.retryAtMillis)) {
        return Future.value(entry.geometry);
      }
      if (now >= entry.expiresAtMillis && now < entry.retryAtMillis) {
        return Future.value();
      }
    }
    final existing = _inFlight[key];
    if (existing != null) return existing;
    if (_inFlight.length >= inFlightLimit) return Future.value();
    final completer = Completer<TransitRouteGeometry?>();
    _inFlight[key] = completer.future;
    () async {
      var result = const TransitRouteFetchResult(null);
      TransitRouteGeometry? returned;
      try {
        result = await fetch(key);
        if (result.geometry != null && result.geometry!.request != key) {
          result = const TransitRouteFetchResult(null);
        }
        returned = result.geometry;
      } catch (_) {
        returned = null;
      } finally {
        _inFlight.remove(key);
        if (!_closed) {
          final now = nowMillis(), previous = _cache[key];
          if (!result.cacheable) {
            _cache.remove(key);
          } else if (result.geometry != null) {
            _cache[key] = _TransitEntry(
              result.geometry,
              now + successTtlMillis,
              now + refreshAfterMillis,
              now,
            );
          } else if (previous?.geometry != null &&
              now < previous!.expiresAtMillis) {
            _cache[key] = _TransitEntry(
              previous.geometry,
              previous.expiresAtMillis,
              previous.refreshAtMillis,
              now + failureTtlMillis,
            );
          } else {
            _cache[key] = _TransitEntry(
              null,
              now + failureTtlMillis,
              now + failureTtlMillis,
              now + failureTtlMillis,
            );
          }
          while (_cache.length > cacheLimit) {
            _cache.remove(_cache.keys.first);
          }
        }
        completer.complete(_closed ? null : returned);
      }
    }();
    return completer.future;
  }

  void close() {
    if (_closed) return;
    _closed = true;
    _cache.clear();
    _inFlight.clear();
    onClose?.call();
  }
}
