import 'dart:math' as math;

import 'models.dart';

/// Candidate service with an exact boarding visit. Suggestions never authorize check-in.
class RecognizableRide {
  RecognizableRide({
    required this.tripId,
    required this.lineName,
    required this.stops,
    required this.originIndex,
    required this.fetchedAtMillis,
    this.departureRealMillis,
    this.departurePlannedMillis,
    this.cancelled = false,
    this.payload,
  });
  final String tripId, lineName;
  final List<TrackingStop> stops;
  final int originIndex, fetchedAtMillis;
  final int? departureRealMillis, departurePlannedMillis;
  final bool cancelled;
  final Object? payload;
  TrackingStop? get origin => originIndex >= 0 && originIndex < stops.length
      ? stops[originIndex]
      : null;
  String get id =>
      '$tripId|$lineName|${origin?.key ?? '${origin?.stationId}:${origin?.plannedDepartureMillis}'}';
}

class RecognizedRide {
  const RecognizedRide({
    required this.ride,
    required this.latestFixMillis,
    required this.nextStationName,
    required this.progressMeters,
    this.sessionId = 0,
  });
  final RecognizableRide ride;
  final int latestFixMillis, progressMeters, sessionId;
  final String nextStationName;
  String get id => ride.id;
}

enum RideRecognitionPhase {
  off,
  waitingForLocation,
  searching,
  observing,
  matches,
  error,
}

class RideRecognitionState {
  const RideRecognitionState({
    this.phase = RideRecognitionPhase.off,
    this.message = 'Fahrterkennung ist ausgeschaltet.',
    this.candidates = const [],
    this.sessionId = 0,
    this.authSessionRevision,
  });
  final RideRecognitionPhase phase;
  final String message;
  final List<RecognizedRide> candidates;
  final int sessionId;
  final String? authSessionRevision;
}

/// Conservative coordinate matching requires an observed origin, directed
/// travel, several fresh accurate fixes and the concrete visit's time window.
class RideRecognitionEngine {
  final List<LocationFix> _fixes = [];
  final Map<String, RecognizableRide> _routes = {};
  bool _latestFixReliable = false;
  int? _latestObservedFixMillis;
  static const maxFixAgeMillis = 30000,
      routeTtlMillis = 300000,
      maxRoutes = 12,
      maxFixes = 24;
  static const _historyMillis = 120000,
      _minObservationMillis = 8000,
      _maxMovementMetersPerSecond = 100.0;
  int get storedFixCount => _fixes.length;
  int get storedRouteCount => _routes.length;
  void updateRides(List<RecognizableRide> rides, int nowMillis) {
    _prune(nowMillis);
    for (final ride
        in rides
            .where(
              (r) =>
                  r.fetchedAtMillis >= nowMillis - routeTtlMillis &&
                  r.fetchedAtMillis <= nowMillis,
            )
            .take(maxRoutes)) {
      _routes[ride.id] = ride;
    }
    while (_routes.length > maxRoutes) {
      _routes.remove(_routes.keys.first);
    }
  }

  List<RecognizedRide> onLocation(LocationFix fix, int nowMillis) {
    if (_latestObservedFixMillis != null &&
        fix.timeMillis <= _latestObservedFixMillis!) {
      return matches(nowMillis);
    }
    if (fix.timeMillis <= nowMillis) _latestObservedFixMillis = fix.timeMillis;
    _latestFixReliable = isReliable(fix, nowMillis);
    if (!_latestFixReliable) return [];
    if (_fixes.isNotEmpty &&
        fix.timeMillis - _fixes.last.timeMillis > maxFixAgeMillis) {
      _fixes.clear();
    }
    _fixes.add(fix);
    _prune(nowMillis);
    return matches(nowMillis);
  }

  List<RecognizedRide> matches(int nowMillis) {
    _prune(nowMillis);
    if (!_latestFixReliable ||
        _fixes.length < 3 ||
        !isReliable(_fixes.last, nowMillis)) {
      return [];
    }
    final results = <RecognizedRide>[];
    for (final ride in _routes.values) {
      final result = _match(ride, _fixes.last, nowMillis);
      if (result != null) results.add(result);
    }
    results.sort((a, b) {
      final progress = b.progressMeters.compareTo(a.progressMeters);
      return progress != 0 ? progress : a.id.compareTo(b.id);
    });
    return results;
  }

  LocationFix? reliableLatestFix(int nowMillis) =>
      _fixes.isNotEmpty &&
          _latestFixReliable &&
          isReliable(_fixes.last, nowMillis)
      ? _fixes.last
      : null;
  void removeTrips(Set<String> tripIds) =>
      _routes.removeWhere((key, value) => tripIds.contains(value.tripId));
  void clear() {
    _fixes.clear();
    _routes.clear();
    _latestFixReliable = false;
    _latestObservedFixMillis = null;
  }

  RecognizedRide? _match(
    RecognizableRide ride,
    LocationFix latest,
    int nowMillis,
  ) {
    if (ride.cancelled) return null;
    final origin = ride.origin;
    if (origin == null || origin.cancelled || !origin.hasCoordinates) {
      return null;
    }
    TrackingStop? next;
    for (final stop in ride.stops.skip(ride.originIndex + 1)) {
      if (!stop.cancelled) {
        next = stop;
        break;
      }
    }
    if (next == null || !next.hasCoordinates) return null;
    final departure =
        ride.departureRealMillis ??
        origin.effectiveDepartureMillis ??
        ride.departurePlannedMillis;
    if (departure == null ||
        nowMillis < departure - 120000 ||
        nowMillis > departure + 480000) {
      return null;
    }
    if (next.effectiveArrivalMillis != null &&
        nowMillis > next.effectiveArrivalMillis! + 300000) {
      return null;
    }
    final segment = _localPoint(next.latitude!, next.longitude!, origin);
    if (segment == null) return null;
    final length = _hypot(segment.$1, segment.$2);
    if (length < 100 || length > 50000) return null;
    for (var index = _fixes.length - 1; index >= 0; index--) {
      final fix = _fixes[index];
      if (index > _fixes.length - 3 ||
          fix.timeMillis > latest.timeMillis - _minObservationMillis) {
        continue;
      }
      final point = _localPoint(fix.latitude, fix.longitude, origin);
      if (point == null ||
          _hypot(point.$1, point.$2) > 300 + fix.accuracyMeters) {
        continue;
      }
      final result = _matchObservation(
        ride,
        next,
        latest,
        origin,
        segment,
        length,
        _fixes.sublist(index),
      );
      if (result != null) return result;
    }
    return null;
  }

  RecognizedRide? _matchObservation(
    RecognizableRide ride,
    TrackingStop next,
    LocationFix latest,
    TrackingStop start,
    (double, double) segment,
    double length,
    List<LocationFix> samples,
  ) {
    if (samples.length < 3) return null;
    final duration = (latest.timeMillis - samples.first.timeMillis) / 1000;
    if (duration < _minObservationMillis / 1000) return null;
    final points = <(double, double)>[];
    for (final fix in samples) {
      final point = _localPoint(fix.latitude, fix.longitude, start);
      if (point == null) return null;
      points.add(point);
    }
    for (var i = 1; i < samples.length; i++) {
      final previous = samples[i - 1],
          current = samples[i],
          seconds = (samples[i].timeMillis - samples[i - 1].timeMillis) / 1000;
      final distance = _hypot(
        points[i].$1 - points[i - 1].$1,
        points[i].$2 - points[i - 1].$2,
      );
      if (seconds <= 0 ||
          distance >
              _maxMovementMetersPerSecond * seconds +
                  previous.accuracyMeters +
                  current.accuracyMeters) {
        return null;
      }
    }
    final projections = <double>[];
    for (var i = 0; i < samples.length; i++) {
      final point = points[i],
          along =
              (points[i].$1 * segment.$1 + points[i].$2 * segment.$2) / length;
      final lateral =
          (point.$1 * segment.$2 - point.$2 * segment.$1).abs() / length;
      if (lateral > math.max(180.0, samples[i].accuracyMeters * 3)) return null;
      projections.add(along);
    }
    final progress = projections.last - projections.first,
        accuracy = samples.map((s) => s.accuracyMeters).reduce(math.max);
    if (progress < math.max(80.0, accuracy * 4) ||
        progress / duration < 2.8 ||
        progress / duration > 95) {
      return null;
    }
    if (projections.last < 30 || projections.last > length + 150) return null;
    for (var i = 1; i < projections.length; i++) {
      if (projections[i] < projections[i - 1] - math.max(35.0, accuracy * 2)) {
        return null;
      }
    }
    return RecognizedRide(
      ride: ride,
      latestFixMillis: latest.timeMillis,
      nextStationName: next.name,
      progressMeters: progress.toInt(),
    );
  }

  void _prune(int nowMillis) {
    while (_fixes.isNotEmpty &&
        (nowMillis - _fixes.first.timeMillis > _historyMillis ||
            _fixes.length > maxFixes)) {
      _fixes.removeAt(0);
    }
    _routes.removeWhere(
      (key, value) => nowMillis - value.fetchedAtMillis > routeTtlMillis,
    );
  }

  static bool isReliable(LocationFix fix, int nowMillis) =>
      validCoordinates(fix.latitude, fix.longitude) &&
      fix.accuracyMeters.isFinite &&
      fix.accuracyMeters >= 0 &&
      fix.accuracyMeters <= 65 &&
      nowMillis - fix.timeMillis >= 0 &&
      nowMillis - fix.timeMillis <= maxFixAgeMillis;
  static int resolveOriginIndex(
    List<TrackingStop> stops, {
    int? stationId,
    int? plannedDepartureMillis,
    int? realDepartureMillis,
  }) {
    final matching = <int>[];
    if (stationId != null) {
      for (var i = 0; i < stops.length; i++) {
        if (stops[i].stationId == stationId) matching.add(i);
      }
    }
    for (final i in matching) {
      final stop = stops[i];
      if ((plannedDepartureMillis != null &&
              stop.plannedDepartureMillis == plannedDepartureMillis) ||
          (realDepartureMillis != null &&
              stop.effectiveDepartureMillis == realDepartureMillis)) {
        return i;
      }
    }
    return matching.length == 1 ? matching.single : -1;
  }

  static (double, double)? _localPoint(
    double lat,
    double lon,
    TrackingStop origin,
  ) {
    final originLat = origin.latitude, originLon = origin.longitude;
    if (originLat == null ||
        originLon == null ||
        !lat.isFinite ||
        !lon.isFinite ||
        !originLat.isFinite ||
        !originLon.isFinite) {
      return null;
    }
    return (
      (lon - originLon) * 111320 * math.cos(originLat * math.pi / 180),
      (lat - originLat) * 111320,
    );
  }

  static double _hypot(double x, double y) => math.sqrt(x * x + y * y);
}
