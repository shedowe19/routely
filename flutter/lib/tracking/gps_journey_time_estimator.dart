import 'dart:math' as math;

import 'models.dart';
import 'tracking_route_geometry.dart';

/// Why fresh GPS progress cannot currently support a future-time forecast.
enum GpsTimeUnavailableReason {
  noFreshLocation,
  inaccurateLocation,
  visitUnconfirmed,
  routeUnsupported,
  waitingAtOrigin,
  outsideCorridor,
  insufficientMovement,
  unplausibleMovement,
  routeGeometryUnavailable,
  replacementStopUnconfirmed,
  ambiguousRoute,
}

class GpsStopTime {
  const GpsStopTime({
    required this.stopKey,
    this.stationId,
    this.plannedArrivalMillis,
    this.plannedDepartureMillis,
    this.arrivalMillis,
    this.departureMillis,
    this.arrivalObserved = false,
    this.departureObserved = false,
  });
  final String stopKey;
  final int? stationId,
      plannedArrivalMillis,
      plannedDepartureMillis,
      arrivalMillis,
      departureMillis;
  final bool arrivalObserved, departureObserved;
}

/// Ephemeral display information, never an API realtime field or saved override.
class GpsJourneyTimes {
  GpsJourneyTimes({
    required this.updatedAtMillis,
    required this.validUntilMillis,
    required List<GpsStopTime> stopTimes,
  }) : stopTimes = List.unmodifiable(stopTimes);
  final int updatedAtMillis, validUntilMillis;
  final List<GpsStopTime> stopTimes;

  /// A station ID or array index cannot distinguish repeated visits.
  GpsStopTime? timeFor(TrackingStop stop, int nowMillis) {
    if (updatedAtMillis <= 0 ||
        nowMillis < updatedAtMillis ||
        nowMillis > validUntilMillis ||
        nowMillis - updatedAtMillis > GpsJourneyTimeEstimator.maxFixAgeMillis ||
        stop.cancelled) {
      return null;
    }
    final arrival = _positive(stop.plannedArrivalMillis),
        departure = _positive(stop.plannedDepartureMillis);
    final matches = stopTimes.where((time) {
      if (stop.key.isNotEmpty &&
          !stop.key.contains(':') &&
          !time.stopKey.contains(':')) {
        return stop.key == time.stopKey &&
            arrival == time.plannedArrivalMillis &&
            departure == time.plannedDepartureMillis;
      }
      return stop.stationId != null &&
          stop.stationId == time.stationId &&
          (arrival != null || departure != null) &&
          arrival == time.plannedArrivalMillis &&
          departure == time.plannedDepartureMillis;
    }).toList();
    if (matches.length != 1) return null;
    final match = matches.single;
    if ((match.arrivalMillis == null && match.departureMillis == null) ||
        (match.arrivalMillis != null && match.arrivalMillis! <= 0) ||
        (match.departureMillis != null && match.departureMillis! <= 0)) {
      return null;
    }
    return match;
  }

  static int? _positive(int? value) =>
      value != null && value > 0 ? value : null;
}

class _Baseline {
  const _Baseline(
    this.key,
    this.stationId,
    this.latitude,
    this.longitude,
    this.arrival,
    this.departure,
    this.cancelled,
    this.origin,
    this.destination,
  );
  factory _Baseline.of(TrackingStop s) => _Baseline(
    s.key,
    s.stationId,
    s.latitude,
    s.longitude,
    s.plannedArrivalMillis,
    s.plannedDepartureMillis,
    s.cancelled,
    s.isOrigin,
    s.isDestination,
  );
  final String key;
  final int? stationId, arrival, departure;
  final double? latitude, longitude;
  final bool cancelled, origin, destination;
  @override
  bool operator ==(Object other) =>
      other is _Baseline &&
      key == other.key &&
      stationId == other.stationId &&
      latitude == other.latitude &&
      longitude == other.longitude &&
      arrival == other.arrival &&
      departure == other.departure &&
      cancelled == other.cancelled &&
      origin == other.origin &&
      destination == other.destination;
  @override
  int get hashCode => Object.hash(
    key,
    stationId,
    latitude,
    longitude,
    arrival,
    departure,
    cancelled,
    origin,
    destination,
  );
}

class _ArrivalCandidate {
  _ArrivalCandidate(this.first, this.latest);
  final LocationFix first;
  LocationFix latest;
  int count = 1;
}

class _ObservedVisit {
  _ObservedVisit(this.arrival, this.departureAnchor);
  final int arrival;
  LocationFix departureAnchor;
  int? departure;
}

class _SegmentSample {
  _SegmentSample(this.key, this.fix, _Projection p)
    : fraction = p.fraction,
      segmentMeters = p.length,
      path = p.path,
      uniqueRoadCandidate = p.uniqueRoadCandidate,
      candidateProgress = p.candidateProgress;
  final String key;
  final LocationFix fix;
  final double fraction, segmentMeters;
  final TrackingGeometryPath? path;
  final bool uniqueRoadCandidate;
  final Map<TrackingGeometryPath, double> candidateProgress;
}

class _Projection {
  const _Projection(
    this.fraction,
    this.length, {
    this.path,
    this.across = 0,
    this.uniqueRoadCandidate = false,
    this.candidateProgress = const {},
  });
  final double fraction, length, across;
  final TrackingGeometryPath? path;
  final bool uniqueRoadCandidate;
  final Map<TrackingGeometryPath, double> candidateProgress;
  _Projection copy({
    double? fraction,
    double? length,
    TrackingGeometryPath? path,
    double? across,
    bool? uniqueRoadCandidate,
    Map<TrackingGeometryPath, double>? candidateProgress,
  }) => _Projection(
    fraction ?? this.fraction,
    length ?? this.length,
    path: path ?? this.path,
    across: across ?? this.across,
    uniqueRoadCandidate: uniqueRoadCandidate ?? this.uniqueRoadCandidate,
    candidateProgress: candidateProgress ?? this.candidateProgress,
  );
}

class _RoadShape {
  _RoadShape(RouteGeometry route, this.source)
    : from = route.from,
      to = route.to,
      alternatives = route.alternatives;
  final RoutePoint from, to;
  final List<List<RoutePoint>> alternatives;
  final GpsGeometrySource source;
  @override
  bool operator ==(Object other) =>
      other is _RoadShape &&
      source == other.source &&
      from == other.from &&
      to == other.to &&
      routeAlternativesEqual(alternatives, other.alternatives);
  @override
  int get hashCode => Object.hash(
    source,
    from,
    to,
    Object.hashAll(alternatives.map(Object.hashAll)),
  );
}

class _RoadProjection {
  const _RoadProjection(this.projection, {this.ambiguous = false});
  final _Projection? projection;
  final bool ambiguous;
}

class _DepartureBasis {
  _DepartureBasis(TrackingStop from, TrackingStop to, _Projection projection)
    : fromKey = from.key,
      toKey = to.key,
      from = RoutePoint(from.latitude!, from.longitude!),
      to = RoutePoint(to.latitude!, to.longitude!),
      points = projection.path?.points;
  final String fromKey, toKey;
  final RoutePoint from, to;
  final List<RoutePoint>? points;
  @override
  bool operator ==(Object other) =>
      other is _DepartureBasis &&
      fromKey == other.fromKey &&
      toKey == other.toKey &&
      from == other.from &&
      to == other.to &&
      routePointsEqual(points, other.points);
  @override
  int get hashCode => Object.hash(
    fromKey,
    toKey,
    from,
    to,
    points == null ? null : Object.hashAll(points!),
  );
}

/// Uses an established ordered station visit and planned travel intervals.
/// It never predicts arrival by dividing distance by instantaneous speed.
class GpsJourneyTimeEstimator {
  static const maxFixAgeMillis = 30000;
  static const _maxAccuracyMeters = 75.0,
      _arrivalRadiusMeters = 120.0,
      _maxStationarySpeed = 3.0,
      _minSegmentMeters = 100.0,
      _maxSegmentMeters = 50000.0,
      _minSegmentFraction = .05,
      _maxSegmentFraction = .98,
      _maxTravelSpeed = 100.0,
      _earthRadiusMeters = 6371000.0,
      _maxCandidateForecastSpreadMillis = 60000.0;
  static const _minDwellMillis = 8000,
      _minMovementMillis = 8000,
      _minSampleIntervalMillis = 1000,
      _maxSegmentSamples = 32,
      _minTravelMillis = 15000,
      _maxTravelMillis = 5400000,
      _maxOffsetMillis = 21600000;
  List<_Baseline>? _baseline;
  int? _highestFixTime;
  LocationFix? _lastFix;
  String? _lastVisitKey;
  GpsJourneyTimes? _cached;
  bool _cachedHasForecast = false, _cachedRequiresRoadGeometry = false;
  GpsGeometrySource? _roadMode, _activeGeometrySource;
  (String, String)? _geometrySegmentKey;
  _RoadShape? _geometryShape;
  TrackingGeometryPath? _selectedRoadPath, _lockedRoadPath;
  RouteGeometry? _preparedGeometry;
  TrackingGeometrySegment? _preparedSegment;
  (RoutePoint, RoutePoint)? _preparedEndpoints;
  GpsTimeUnavailableReason? _forecastUnavailableReason =
      GpsTimeUnavailableReason.noFreshLocation;
  final _arrivals = <String, _ArrivalCandidate>{};
  final _observed = <String, _ObservedVisit>{};
  final _segmentSamples = <_SegmentSample>[];
  _DepartureBasis? _pendingDepartureBasis;

  void reset() {
    _baseline = null;
    _highestFixTime = null;
    _clearLocationState();
    _forecastUnavailableReason = GpsTimeUnavailableReason.noFreshLocation;
  }

  /// The consumed-fix watermark survives so an old cached fix cannot revive ETA.
  void invalidateLocation() {
    _clearLocationState();
    _forecastUnavailableReason = GpsTimeUnavailableReason.noFreshLocation;
  }

  GpsTimeUnavailableReason? unavailableReason() => _forecastUnavailableReason;
  GpsGeometrySource? geometrySource() => _activeGeometrySource;

  GpsJourneyTimes? update({
    required List<TrackingStop> route,
    required TrackingProgress progress,
    required TrackingSource source,
    required LocationFix? fix,
    required int nowMillis,
    List<GpsSegmentGeometry> segmentGeometries = const [],
    bool useRoadGeometry = false,
  }) {
    final currentBaseline = route.map(_Baseline.of).toList();
    if (!_baselineEqual(_baseline, currentBaseline)) {
      _clearLocationState();
      _baseline = currentBaseline;
      _forecastUnavailableReason =
          GpsTimeUnavailableReason.insufficientMovement;
    }
    final matchingIndices = <int>[];
    if (progress.nextStopKey != null) {
      for (var i = 0; i < route.length; i++) {
        if (route[i].key == progress.nextStopKey) matchingIndices.add(i);
      }
    }
    final index = matchingIndices.length == 1 ? matchingIndices.single : null;
    GpsTimeUnavailableReason? rejectedReason;
    if (fix == null ||
        fix.timeMillis <= 0 ||
        nowMillis < fix.timeMillis ||
        nowMillis - fix.timeMillis > maxFixAgeMillis) {
      rejectedReason = GpsTimeUnavailableReason.noFreshLocation;
    } else if (!fix.accuracyMeters.isFinite ||
        fix.accuracyMeters < 0 ||
        fix.accuracyMeters > _maxAccuracyMeters) {
      rejectedReason = GpsTimeUnavailableReason.inaccurateLocation;
    } else if (!_reliable(fix, nowMillis)) {
      rejectedReason = GpsTimeUnavailableReason.unplausibleMovement;
    } else if (source != TrackingSource.gps ||
        !progress.gpsEstablished ||
        index == null ||
        index != progress.nextIndex) {
      rejectedReason = GpsTimeUnavailableReason.visitUnconfirmed;
    } else if (route[index].cancelled ||
        !_coordinates(route[index]) ||
        !_uniqueVisits(route)) {
      rejectedReason = GpsTimeUnavailableReason.routeUnsupported;
    }
    if (rejectedReason != null) {
      invalidateLocation();
      _forecastUnavailableReason = rejectedReason;
      return null;
    }
    final currentFix = fix!, currentIndex = index!, stop = route[index];
    TrackingStop? previousStop;
    for (var i = currentIndex - 1; i >= 0; i--) {
      if (!route[i].cancelled) {
        previousStop = route[i];
        break;
      }
    }
    final requestedSource = useRoadGeometry
        ? GpsGeometrySource.roadModel
        : GpsGeometrySource.tripPolyline;
    final candidates = segmentGeometries
        .where(
          (g) =>
              g.fromKey == previousStop?.key &&
              g.toKey == stop.key &&
              g.source == requestedSource,
        )
        .toList();
    final candidateGeometry = previousStop != null && candidates.length == 1
        ? candidates.single.geometry
        : null;
    final roadSegment = previousStop == null
        ? null
        : _validateRoad(
            candidateGeometry,
            previousStop,
            stop,
            requestedSource,
            nowMillis,
          );
    final projectionSource = useRoadGeometry
        ? GpsGeometrySource.roadModel
        : roadSegment?.source;
    final usesPolyline = projectionSource != null;
    _updateRoadBasis(
      projectionSource,
      previousStop == null ? null : (previousStop.key, stop.key),
      roadSegment == null ? null : candidateGeometry,
    );
    if (usesPolyline &&
        previousStop != null &&
        roadSegment == null &&
        _cachedRequiresRoadGeometry) {
      _clearPredictionState();
      _forecastUnavailableReason =
          GpsTimeUnavailableReason.routeGeometryUnavailable;
    }
    final highWatermark = _highestFixTime;
    if (highWatermark != null && currentFix.timeMillis < highWatermark) {
      invalidateLocation();
      _forecastUnavailableReason = GpsTimeUnavailableReason.unplausibleMovement;
      return null;
    }
    if (highWatermark == currentFix.timeMillis) {
      final sameFixAndVisit =
          _sameFix(_lastFix, currentFix) && _lastVisitKey == stop.key;
      final retained =
          sameFixAndVisit &&
              _cached != null &&
              nowMillis <= _cached!.validUntilMillis
          ? _cached
          : null;
      if (retained != null) return retained;
      _forecastUnavailableReason ??=
          GpsTimeUnavailableReason.insufficientMovement;
      return sameFixAndVisit
          ? _publishObservedOnly(route, currentIndex, currentFix)
          : null;
    }
    _highestFixTime = currentFix.timeMillis;
    _activeGeometrySource = null;
    final previousFix =
        _lastFix != null &&
            currentFix.timeMillis - _lastFix!.timeMillis >= 1 &&
            currentFix.timeMillis - _lastFix!.timeMillis <= maxFixAgeMillis
        ? _lastFix
        : null;
    if (_lastFix != null && previousFix == null) _clearLocationState();
    if (previousFix != null &&
        _distance(
                  previousFix.latitude,
                  previousFix.longitude,
                  currentFix.latitude,
                  currentFix.longitude,
                ) /
                ((currentFix.timeMillis - previousFix.timeMillis) / 1000) >
            _maxTravelSpeed) {
      invalidateLocation();
      _forecastUnavailableReason = GpsTimeUnavailableReason.unplausibleMovement;
      return null;
    }
    final insideStation =
        _distance(
              currentFix.latitude,
              currentFix.longitude,
              stop.latitude!,
              stop.longitude!,
            ) +
            currentFix.accuracyMeters <=
        _arrivalRadiusMeters;
    final arrivalEndpoint =
        progress.arrivedAtCurrent && insideStation && _lastVisitKey == stop.key;
    final plannedTravel =
        previousStop?.plannedDepartureMillis != null &&
            stop.plannedArrivalMillis != null
        ? stop.plannedArrivalMillis! - previousStop!.plannedDepartureMillis!
        : null;
    final roadProjection = usesPolyline && roadSegment != null
        ? _projectRoad(roadSegment, currentFix, arrivalEndpoint, plannedTravel)
        : null;
    final projection = usesPolyline
        ? roadProjection?.projection
        : previousStop == null
        ? null
        : _project(previousStop, stop, currentFix, arrivalEndpoint);
    final compatible =
        previousStop != null &&
        projection != null &&
        _compatibleProgress(
          previousStop,
          stop,
          previousFix,
          currentFix,
          projection,
          arrivalEndpoint,
        );
    if (previousStop != null && projection != null && compatible) {
      _observeDeparture(
        previousStop,
        stop,
        previousFix,
        currentFix,
        projection,
      );
    } else {
      _pendingDepartureBasis = null;
    }
    final observedArrival = _observeArrival(
      stop,
      progress.arrivedAtCurrent,
      currentFix,
      insideStation,
    );
    int? offset;
    var offsetFromSegment = false, compatiblePosition = false;
    var unavailable = GpsTimeUnavailableReason.insufficientMovement;
    if (observedArrival != null &&
        insideStation &&
        progress.arrivedAtCurrent &&
        !stop.isOrigin) {
      final plan = stop.plannedArrivalMillis;
      if (plan != null) {
        offset = math.max(0, observedArrival.arrival - plan);
        if (stop.plannedDepartureMillis != null) {
          offset = math.max(
            offset,
            currentFix.timeMillis - stop.plannedDepartureMillis!,
          );
        }
      } else {
        unavailable = GpsTimeUnavailableReason.routeUnsupported;
      }
      _segmentSamples.clear();
    } else if (previousStop != null) {
      final departure = previousStop.plannedDepartureMillis,
          arrival = stop.plannedArrivalMillis;
      final supportedRoute =
          _coordinates(previousStop) &&
          departure != null &&
          arrival != null &&
          arrival - departure >= _minTravelMillis &&
          arrival - departure <= _maxTravelMillis &&
          (usesPolyline
              ? roadSegment != null
              : _segmentDistanceSupported(previousStop, stop));
      unavailable = usesPolyline && roadSegment == null
          ? GpsTimeUnavailableReason.routeGeometryUnavailable
          : !supportedRoute
          ? GpsTimeUnavailableReason.routeUnsupported
          : roadProjection?.ambiguous == true
          ? GpsTimeUnavailableReason.ambiguousRoute
          : projection == null
          ? GpsTimeUnavailableReason.outsideCorridor
          : !compatible
          ? GpsTimeUnavailableReason.unplausibleMovement
          : GpsTimeUnavailableReason.insufficientMovement;
      if (supportedRoute && projection != null && compatible) {
        if (usesPolyline &&
            _selectedRoadPath != null &&
            _selectedRoadPath != projection.path) {
          _clearPredictionState();
        }
        _selectedRoadPath = projection.path;
        _activeGeometrySource = projectionSource;
        compatiblePosition = true;
        if (!progress.arrivedAtCurrent &&
            projection.fraction >= _minSegmentFraction &&
            projection.fraction <= _maxSegmentFraction) {
          final supported = _observeSegment(stop.key, currentFix, projection);
          if (supported != null) {
            // Kotlin roundToLong rounds half values toward positive infinity.
            offset =
                currentFix.timeMillis -
                (departure +
                    ((arrival - departure) * supported.fraction + .5).floor());
          }
          offsetFromSegment = offset != null;
        } else {
          _segmentSamples.clear();
        }
      } else {
        _activeGeometrySource = null;
        if (usesPolyline && !compatible) {
          _clearPredictionState();
        } else {
          _segmentSamples.clear();
        }
      }
    } else {
      _segmentSamples.clear();
      unavailable = stop.isOrigin && progress.arrivedAtCurrent && insideStation
          ? GpsTimeUnavailableReason.waitingAtOrigin
          : GpsTimeUnavailableReason.visitUnconfirmed;
    }
    _lastFix = currentFix;
    _lastVisitKey = stop.key;
    if (offset == null) {
      final retained =
          _cachedHasForecast &&
              compatiblePosition &&
              _cached != null &&
              nowMillis <= _cached!.validUntilMillis
          ? _cached
          : null;
      if (retained != null) {
        _forecastUnavailableReason = null;
        return _cached = _mergeObserved(
          retained,
          _observedTimes(route, currentIndex),
        );
      }
      _forecastUnavailableReason = unavailable;
      return _publishObservedOnly(route, currentIndex, currentFix);
    }
    if (offset.abs() > _maxOffsetMillis) {
      _forecastUnavailableReason = GpsTimeUnavailableReason.unplausibleMovement;
      return _publishObservedOnly(route, currentIndex, currentFix);
    }
    final times = <GpsStopTime>[];
    for (var i = 0; i < route.length; i++) {
      final visit = route[i];
      if (visit.cancelled) continue;
      final actual = _observed[visit.key], forecast = i >= currentIndex;
      final arrival = actual != null && visit.plannedArrivalMillis != null
          ? actual.arrival
          : visit.plannedArrivalMillis != null && forecast
          ? _shifted(visit.plannedArrivalMillis!, offset)
          : null;
      final departure =
          actual?.departure ??
          (visit.plannedDepartureMillis != null && forecast
              ? _shifted(visit.plannedDepartureMillis!, offset)
              : null);
      if (arrival == null && departure == null) continue;
      times.add(
        GpsStopTime(
          stopKey: visit.key,
          stationId: visit.stationId,
          plannedArrivalMillis: visit.plannedArrivalMillis,
          plannedDepartureMillis: visit.plannedDepartureMillis,
          arrivalMillis: arrival,
          departureMillis: departure,
          arrivalObserved: actual != null && visit.plannedArrivalMillis != null,
          departureObserved: actual?.departure != null,
        ),
      );
    }
    if (times.isEmpty) {
      _cached = null;
      _cachedHasForecast = false;
      _forecastUnavailableReason = GpsTimeUnavailableReason.routeUnsupported;
      return null;
    }
    _cachedHasForecast = times.any(
      (t) =>
          (t.arrivalMillis != null && !t.arrivalObserved) ||
          (t.departureMillis != null && !t.departureObserved),
    );
    _cachedRequiresRoadGeometry =
        _cachedHasForecast && usesPolyline && offsetFromSegment;
    _forecastUnavailableReason = _cachedHasForecast ? null : unavailable;
    return _cached = GpsJourneyTimes(
      updatedAtMillis: currentFix.timeMillis,
      validUntilMillis: currentFix.timeMillis + maxFixAgeMillis,
      stopTimes: times,
    );
  }

  List<GpsStopTime> _observedTimes(List<TrackingStop> route, int currentIndex) {
    final times = <GpsStopTime>[];
    for (var i = 0; i <= currentIndex; i++) {
      final stop = route[i];
      if (stop.cancelled) continue;
      final actual = _observed[stop.key];
      if (actual == null) continue;
      final arrival = stop.plannedArrivalMillis == null ? null : actual.arrival;
      final departure = actual.departure;
      if (arrival == null && departure == null) continue;
      times.add(
        GpsStopTime(
          stopKey: stop.key,
          stationId: stop.stationId,
          plannedArrivalMillis: stop.plannedArrivalMillis,
          plannedDepartureMillis: stop.plannedDepartureMillis,
          arrivalMillis: arrival,
          departureMillis: departure,
          arrivalObserved: arrival != null,
          departureObserved: departure != null,
        ),
      );
    }
    return times;
  }

  GpsJourneyTimes? _publishObservedOnly(
    List<TrackingStop> route,
    int currentIndex,
    LocationFix fix,
  ) {
    _cachedHasForecast = false;
    _cachedRequiresRoadGeometry = false;
    final times = _observedTimes(route, currentIndex);
    return _cached = times.isEmpty
        ? null
        : GpsJourneyTimes(
            updatedAtMillis: fix.timeMillis,
            validUntilMillis: fix.timeMillis + maxFixAgeMillis,
            stopTimes: times,
          );
  }

  GpsJourneyTimes _mergeObserved(
    GpsJourneyTimes snapshot,
    List<GpsStopTime> actualTimes,
  ) {
    final merged = {for (final time in snapshot.stopTimes) time.stopKey: time};
    for (final actual in actualTimes) {
      final existing = merged[actual.stopKey];
      merged[actual.stopKey] = existing == null
          ? actual
          : GpsStopTime(
              stopKey: existing.stopKey,
              stationId: existing.stationId,
              plannedArrivalMillis: existing.plannedArrivalMillis,
              plannedDepartureMillis: existing.plannedDepartureMillis,
              arrivalMillis: actual.arrivalMillis ?? existing.arrivalMillis,
              departureMillis:
                  actual.departureMillis ?? existing.departureMillis,
              arrivalObserved:
                  actual.arrivalObserved || existing.arrivalObserved,
              departureObserved:
                  actual.departureObserved || existing.departureObserved,
            );
    }
    return GpsJourneyTimes(
      updatedAtMillis: snapshot.updatedAtMillis,
      validUntilMillis: snapshot.validUntilMillis,
      stopTimes: merged.values.toList(),
    );
  }

  _ObservedVisit? _observeArrival(
    TrackingStop stop,
    bool arrived,
    LocationFix fix,
    bool inside,
  ) {
    final speed = fix.speedMetersPerSecond;
    final lowSpeed =
        speed != null &&
        speed.isFinite &&
        speed >= 0 &&
        speed <= _maxStationarySpeed;
    final actual = _observed[stop.key];
    if (actual != null) {
      final anchor = actual.departureAnchor;
      if (actual.departure == null &&
          arrived &&
          inside &&
          (speed == null || lowSpeed) &&
          _distance(
                anchor.latitude,
                anchor.longitude,
                fix.latitude,
                fix.longitude,
              ) <=
              math.max(
                20.0,
                math.min(
                  30.0,
                  math.min(anchor.accuracyMeters, fix.accuracyMeters),
                ),
              )) {
        actual.departureAnchor = fix;
      }
      return actual;
    }
    if (!inside || (speed != null && !lowSpeed)) {
      _arrivals.remove(stop.key);
      return null;
    }
    final candidate = _arrivals[stop.key];
    final stable =
        candidate != null &&
        fix.timeMillis - candidate.latest.timeMillis <= maxFixAgeMillis &&
        _distance(
              candidate.first.latitude,
              candidate.first.longitude,
              fix.latitude,
              fix.longitude,
            ) <=
            math.max(
              20.0,
              math.min(
                30.0,
                math.min(candidate.first.accuracyMeters, fix.accuracyMeters),
              ),
            );
    final _ArrivalCandidate arrival;
    if (stable) {
      arrival = candidate;
      arrival.latest = fix;
      arrival.count++;
    } else {
      arrival = _ArrivalCandidate(fix, fix);
      _arrivals[stop.key] = arrival;
    }
    final firstSpeed = arrival.first.speedMetersPerSecond;
    final twoSlowFixes =
        lowSpeed &&
        firstSpeed != null &&
        firstSpeed.isFinite &&
        firstSpeed >= 0 &&
        firstSpeed <= _maxStationarySpeed &&
        arrival.count >= 2;
    final dwell =
        arrival.count >= 2 &&
        fix.timeMillis - arrival.first.timeMillis >= _minDwellMillis;
    if (arrived && (twoSlowFixes || dwell)) {
      return _observed[stop.key] = _ObservedVisit(
        arrival.first.timeMillis,
        arrival.latest,
      );
    }
    return null;
  }

  _SegmentSample? _observeSegment(
    String key,
    LocationFix fix,
    _Projection projection,
  ) {
    if (_segmentSamples.isNotEmpty && _segmentSamples.last.key != key) {
      _segmentSamples.clear();
    }
    if (_segmentSamples.isNotEmpty &&
        !_sameCandidates(
          _segmentSamples.last.candidateProgress,
          projection.candidateProgress,
        )) {
      _segmentSamples.clear();
    }
    if (_segmentSamples.isNotEmpty) {
      final preceding = _segmentSamples.last;
      if (fix.timeMillis - preceding.fix.timeMillis > maxFixAgeMillis ||
          _candidateMovement(preceding, projection) <
              -math.max(
                10.0,
                (preceding.fix.accuracyMeters + fix.accuracyMeters) / 2,
              )) {
        _segmentSamples.clear();
        _cached = null;
        return null;
      }
    }
    final sample = _SegmentSample(key, fix, projection);
    if (_segmentSamples.isEmpty ||
        fix.timeMillis - _segmentSamples.last.fix.timeMillis >=
            _minSampleIntervalMillis) {
      _segmentSamples.add(sample);
    }
    while (_segmentSamples.length > _maxSegmentSamples ||
        (_segmentSamples.isNotEmpty &&
            fix.timeMillis - _segmentSamples.first.fix.timeMillis >
                maxFixAgeMillis)) {
      _segmentSamples.removeAt(0);
    }
    final first = _segmentSamples.first;
    final movement = _candidateMovement(first, projection);
    if (_segmentSamples.length < 3 ||
        fix.timeMillis - first.fix.timeMillis < _minMovementMillis ||
        movement <
            math.max(
              50.0,
              3 * math.max(first.fix.accuracyMeters, fix.accuracyMeters),
            )) {
      return null;
    }
    if (projection.path != null && projection.uniqueRoadCandidate) {
      final unique = _segmentSamples
          .where((s) => s.path == projection.path && s.uniqueRoadCandidate)
          .toList();
      if (unique.length >= 3 &&
          fix.timeMillis - unique.first.fix.timeMillis >= _minMovementMillis &&
          (projection.fraction - unique.first.fraction) * projection.length >=
              math.max(
                50.0,
                3 *
                    math.max(
                      unique.first.fix.accuracyMeters,
                      fix.accuracyMeters,
                    ),
              )) {
        _lockedRoadPath = projection.path;
      }
    }
    return sample;
  }

  /// The weakest road candidate independently supports the movement window.
  double _candidateMovement(_SegmentSample first, _Projection current) {
    if (current.candidateProgress.isEmpty) {
      return (current.fraction - first.fraction) * current.length;
    }
    return current.candidateProgress.entries
        .map((entry) {
          final prior = first.candidateProgress[entry.key];
          return prior == null
              ? double.negativeInfinity
              : (entry.value - prior) * entry.key.length;
        })
        .reduce(math.min);
  }

  bool _compatibleProgress(
    TrackingStop from,
    TrackingStop to,
    LocationFix? previous,
    LocationFix fix,
    _Projection projection,
    bool arrivalEndpoint,
  ) {
    if (previous == null) return true;
    final sameVisit = _lastVisitKey == to.key,
        departedVisit = _lastVisitKey == from.key;
    if (!sameVisit && !departedVisit) return false;
    if (projection.candidateProgress.isNotEmpty) {
      return projection.candidateProgress.entries.every(
        (entry) => _compatiblePathProgress(
          from,
          to,
          previous,
          fix,
          projection.copy(
            fraction: entry.value,
            length: entry.key.length,
            path: entry.key,
            candidateProgress: const {},
          ),
          departedVisit,
          arrivalEndpoint,
        ),
      );
    }
    return _compatiblePathProgress(
      from,
      to,
      previous,
      fix,
      projection,
      departedVisit,
      arrivalEndpoint,
    );
  }

  bool _compatiblePathProgress(
    TrackingStop from,
    TrackingStop to,
    LocationFix previous,
    LocationFix fix,
    _Projection projection,
    bool departedVisit,
    bool arrivalEndpoint,
  ) {
    final priorRoad = projection.path == null
        ? null
        : _projectPath(projection.path!, previous, arrivalEndpoint);
    if (priorRoad?.ambiguous == true) return false;
    final prior = projection.path != null
        ? priorRoad?.projection
        : _project(from, to, previous, arrivalEndpoint);
    if (prior == null) {
      return departedVisit &&
          _distance(
                    previous.latitude,
                    previous.longitude,
                    from.latitude!,
                    from.longitude!,
                  ) +
                  previous.accuracyMeters <=
              _arrivalRadiusMeters &&
          (projection.path == null ||
              projection.fraction * projection.length <=
                  _maxTravelSpeed *
                          ((fix.timeMillis - previous.timeMillis) / 1000) +
                      previous.accuracyMeters +
                      fix.accuracyMeters);
    }
    final directedMeters =
        (projection.fraction - prior.fraction) * projection.length;
    return directedMeters >=
            -math.max(
              10.0,
              (previous.accuracyMeters + fix.accuracyMeters) / 2,
            ) &&
        (projection.path == null ||
            directedMeters <=
                _maxTravelSpeed *
                        ((fix.timeMillis - previous.timeMillis) / 1000) +
                    previous.accuracyMeters +
                    fix.accuracyMeters);
  }

  void _observeDeparture(
    TrackingStop previousStop,
    TrackingStop currentStop,
    LocationFix? previous,
    LocationFix fix,
    _Projection projection,
  ) {
    final actual = _observed[previousStop.key];
    if (actual == null ||
        actual.departure != null ||
        previous == null ||
        previousStop.plannedDepartureMillis == null ||
        projection.fraction <= 0) {
      _pendingDepartureBasis = null;
      return;
    }
    final basis = _DepartureBasis(previousStop, currentStop, projection);
    if (_lastVisitKey == previousStop.key) {
      _pendingDepartureBasis = basis;
    } else if (_lastVisitKey != currentStop.key ||
        _pendingDepartureBasis != basis) {
      _pendingDepartureBasis = null;
      return;
    }
    final immediatePrior = _projectSamePath(
      previousStop,
      currentStop,
      previous,
      projection,
    );
    if (immediatePrior == null ||
        projection.fraction <= immediatePrior.fraction) {
      return;
    }
    final anchor = actual.departureAnchor;
    final prior = _departureAnchorProjection(
      previousStop,
      currentStop,
      anchor,
      projection,
    );
    if (prior == null) return;
    final movement = (projection.fraction - prior.fraction) * projection.length;
    final fromPrevious = _distance(
      fix.latitude,
      fix.longitude,
      previousStop.latitude!,
      previousStop.longitude!,
    );
    if (movement >=
            math.max(35.0, anchor.accuracyMeters + fix.accuracyMeters) &&
        fromPrevious > _arrivalRadiusMeters + fix.accuracyMeters) {
      actual.departure = fix.timeMillis;
      _pendingDepartureBasis = null;
    }
  }

  _Projection? _departureAnchorProjection(
    TrackingStop from,
    TrackingStop to,
    LocationFix anchor,
    _Projection current,
  ) {
    final same = _projectSamePath(from, to, anchor, current);
    if (same != null) return same;
    if (!_coordinates(from) || !_coordinates(to)) return null;
    if (_distance(
              anchor.latitude,
              anchor.longitude,
              from.latitude!,
              from.longitude!,
            ) +
            anchor.accuracyMeters >
        _arrivalRadiusMeters) {
      return null;
    }
    if (current.path != null) {
      final result = TrackingRouteGeometry.project(current.path!, anchor);
      if (result.ambiguous || !result.beforeOrigin) return null;
    } else {
      final scale =
          _earthRadiusMeters *
          math.cos(_radians((from.latitude! + to.latitude!) / 2));
      final x = _radians(to.longitude! - from.longitude!) * scale;
      final y = _radians(to.latitude! - from.latitude!) * _earthRadiusMeters;
      final fx = _radians(anchor.longitude - from.longitude!) * scale;
      final fy =
          _radians(anchor.latitude - from.latitude!) * _earthRadiusMeters;
      if (fx * x + fy * y >= 0) return null;
    }
    return current.copy(fraction: 0, across: 0);
  }

  void _clearLocationState() {
    _clearPredictionState();
    _lastFix = null;
    _lastVisitKey = null;
    _arrivals.clear();
    _observed.clear();
    _pendingDepartureBasis = null;
  }

  void _clearPredictionState() {
    _cached = null;
    _cachedHasForecast = false;
    _cachedRequiresRoadGeometry = false;
    _segmentSamples.clear();
    _selectedRoadPath = null;
    _lockedRoadPath = null;
    _activeGeometrySource = null;
  }

  void _updateRoadBasis(
    GpsGeometrySource? source,
    (String, String)? key,
    RouteGeometry? geometry,
  ) {
    final shape = geometry != null && source != null
        ? _RoadShape(geometry, source)
        : null;
    if (source != _roadMode ||
        (key == _geometrySegmentKey && shape != _geometryShape)) {
      _clearPredictionState();
      _forecastUnavailableReason =
          GpsTimeUnavailableReason.insufficientMovement;
    } else if (key != _geometrySegmentKey) {
      _segmentSamples.clear();
      _selectedRoadPath = null;
      _lockedRoadPath = null;
      _activeGeometrySource = null;
    }
    _roadMode = source;
    _geometrySegmentKey = key;
    _geometryShape = shape;
  }

  TrackingGeometrySegment? _validateRoad(
    RouteGeometry? geometry,
    TrackingStop from,
    TrackingStop to,
    GpsGeometrySource source,
    int nowMillis,
  ) {
    final ageLimit = source == GpsGeometrySource.tripPolyline
        ? 900000
        : 86400000;
    if (geometry == null ||
        !_coordinates(from) ||
        !_coordinates(to) ||
        geometry.fetchedAtMillis <= 0 ||
        nowMillis - geometry.fetchedAtMillis < 0 ||
        nowMillis - geometry.fetchedAtMillis > ageLimit) {
      return null;
    }
    final endpoints = (
      RoutePoint(from.latitude!, from.longitude!),
      RoutePoint(to.latitude!, to.longitude!),
    );
    if (_preparedGeometry != geometry ||
        _preparedSegment?.source != source ||
        _preparedEndpoints != endpoints ||
        _geometrySegmentKey != (from.key, to.key)) {
      _preparedSegment = TrackingRouteGeometry.prepare(
        geometry,
        from,
        to,
        source,
        nowMillis,
      );
      _preparedGeometry = geometry;
      _preparedEndpoints = endpoints;
    }
    return _preparedSegment;
  }

  _RoadProjection _projectRoad(
    TrackingGeometrySegment segment,
    LocationFix fix,
    bool arrivalEndpoint,
    int? plannedTravelMillis,
  ) {
    final results = {
      for (final path in segment.paths.toSet())
        path: _projectPath(path, fix, arrivalEndpoint),
    };
    if (results.values.any((r) => r.ambiguous)) {
      _clearPredictionState();
      return const _RoadProjection(null, ambiguous: true);
    }
    final projected = results.values
        .map((r) => r.projection)
        .whereType<_Projection>()
        .toList();
    final nearestAcross = projected.isEmpty
        ? null
        : projected.map((p) => p.across).reduce(math.min);
    final acrossTolerance = math.max(10.0, fix.accuracyMeters * 2);
    final locked = _lockedRoadPath;
    if (locked != null && segment.paths.contains(locked)) {
      final result = results[locked]!;
      if (result.projection != null &&
          nearestAcross != null &&
          result.projection!.across <= nearestAcross + acrossTolerance) {
        return _RoadProjection(
          result.projection!.copy(
            uniqueRoadCandidate: true,
            candidateProgress: {locked: result.projection!.fraction},
          ),
          ambiguous: result.ambiguous,
        );
      }
      _clearPredictionState();
    }
    if (nearestAcross == null) return const _RoadProjection(null);
    final supported = projected
        .where((p) => p.across <= nearestAcross + acrossTolerance)
        .toList();
    final tolerance = math.max(50.0, fix.accuracyMeters * 2),
        reference = supported.first;
    final disagree = supported.any(
      (p) =>
          (p.fraction - reference.fraction).abs() *
                  math.max(p.length, reference.length) >
              tolerance ||
          (p.fraction * p.length - reference.fraction * reference.length)
                  .abs() >
              tolerance,
    );
    final travel =
        plannedTravelMillis != null &&
            plannedTravelMillis >= _minTravelMillis &&
            plannedTravelMillis <= _maxTravelMillis
        ? plannedTravelMillis
        : null;
    final forecastSpread = travel == null
        ? null
        : (supported.map((p) => p.fraction).reduce(math.max) -
                  supported.map((p) => p.fraction).reduce(math.min)) *
              travel;
    if (forecastSpread != null &&
        forecastSpread > _maxCandidateForecastSpreadMillis) {
      return const _RoadProjection(null, ambiguous: true);
    }
    if (disagree) {
      final sharedSuffix = TrackingRouteGeometry.sharedRemainingPath(
        supported
            .map(
              (p) => TrackingGeometryProjection(
                fraction: p.fraction,
                length: p.length,
                path: p.path!,
                across: p.across,
              ),
            )
            .toList(),
      );
      final remaining = supported
          .map((p) => (1 - p.fraction) * p.length)
          .toList();
      if (!sharedSuffix ||
          remaining.reduce(math.max) - remaining.reduce(math.min) >
              math.max(10.0, fix.accuracyMeters * 2) ||
          forecastSpread == null) {
        return const _RoadProjection(null, ambiguous: true);
      }
    }
    supported.sort((a, b) {
      final fractionOrder = a.fraction.compareTo(b.fraction);
      if (fractionOrder != 0) return fractionOrder;
      return (a.path != _selectedRoadPath ? 1 : 0).compareTo(
        b.path != _selectedRoadPath ? 1 : 0,
      );
    });
    return _RoadProjection(
      supported.first.copy(
        uniqueRoadCandidate: supported.length == 1,
        candidateProgress: {for (final p in supported) p.path!: p.fraction},
      ),
    );
  }

  _RoadProjection _projectPath(
    TrackingGeometryPath path,
    LocationFix fix, [
    bool arrivalEndpoint = false,
  ]) {
    final result = TrackingRouteGeometry.project(
          path,
          fix,
          arrivalEndpoint: arrivalEndpoint,
        ),
        p = result.projection;
    return _RoadProjection(
      p == null
          ? null
          : _Projection(p.fraction, p.length, path: p.path, across: p.across),
      ambiguous: result.ambiguous,
    );
  }

  _Projection? _projectSamePath(
    TrackingStop from,
    TrackingStop to,
    LocationFix fix,
    _Projection current, [
    bool arrivalEndpoint = false,
  ]) => current.path != null
      ? _projectPath(current.path!, fix, arrivalEndpoint).projection
      : _project(from, to, fix, arrivalEndpoint);
  bool _uniqueVisits(List<TrackingStop> route) {
    if (route.any(
      (s) =>
          (s.plannedArrivalMillis != null && s.plannedArrivalMillis! <= 0) ||
          (s.plannedDepartureMillis != null &&
              s.plannedDepartureMillis! <= 0) ||
          (s.plannedArrivalMillis != null &&
              s.plannedDepartureMillis != null &&
              s.plannedDepartureMillis! < s.plannedArrivalMillis!),
    )) {
      return false;
    }
    if (route.any((s) => s.key.trim().isEmpty) ||
        route.map((s) => s.key).toSet().length != route.length) {
      return false;
    }
    final fallback = route
        .where((s) => s.key.contains(':') && !s.cancelled)
        .toList();
    return !fallback.any(
      (s) =>
          s.stationId == null ||
          (s.plannedArrivalMillis == null &&
              s.plannedDepartureMillis == null) ||
          fallback
                  .where(
                    (o) =>
                        o.stationId == s.stationId &&
                        o.plannedArrivalMillis == s.plannedArrivalMillis &&
                        o.plannedDepartureMillis == s.plannedDepartureMillis,
                  )
                  .length >
              1,
    );
  }

  _Projection? _project(
    TrackingStop from,
    TrackingStop to,
    LocationFix fix, [
    bool arrivalEndpoint = false,
  ]) {
    if (!_coordinates(from) || !_coordinates(to)) return null;
    final scale =
        _earthRadiusMeters *
        math.cos(_radians((from.latitude! + to.latitude!) / 2));
    final x = _radians(to.longitude! - from.longitude!) * scale;
    final y = _radians(to.latitude! - from.latitude!) * _earthRadiusMeters;
    final length = math.sqrt(x * x + y * y);
    if (!length.isFinite ||
        length < _minSegmentMeters ||
        length > _maxSegmentMeters) {
      return null;
    }
    final fx = _radians(fix.longitude - from.longitude!) * scale;
    final fy = _radians(fix.latitude - from.latitude!) * _earthRadiusMeters;
    final fraction = (fx * x + fy * y) / (length * length);
    final across = (fx * y - fy * x).abs() / length;
    final supportedEndpoint =
        arrivalEndpoint &&
        _distance(fix.latitude, fix.longitude, to.latitude!, to.longitude!) +
                fix.accuracyMeters <=
            _arrivalRadiusMeters;
    if (!fraction.isFinite ||
        fraction < 0 ||
        (fraction > 1 && !supportedEndpoint) ||
        across > math.max(100.0, fix.accuracyMeters * 2)) {
      return null;
    }
    return _Projection(fraction, length);
  }

  static bool _reliable(LocationFix fix, int nowMillis) =>
      validCoordinates(fix.latitude, fix.longitude) &&
      fix.accuracyMeters.isFinite &&
      fix.accuracyMeters >= 0 &&
      fix.accuracyMeters <= _maxAccuracyMeters &&
      fix.timeMillis > 0 &&
      nowMillis >= fix.timeMillis &&
      nowMillis - fix.timeMillis <= maxFixAgeMillis &&
      (fix.speedMetersPerSecond == null ||
          (fix.speedMetersPerSecond!.isFinite &&
              fix.speedMetersPerSecond! >= 0 &&
              fix.speedMetersPerSecond! <= _maxTravelSpeed));
  static bool _coordinates(TrackingStop stop) =>
      stop.latitude != null &&
      stop.longitude != null &&
      validCoordinates(stop.latitude!, stop.longitude!);
  static bool _segmentDistanceSupported(TrackingStop from, TrackingStop to) {
    final length = _distance(
      from.latitude!,
      from.longitude!,
      to.latitude!,
      to.longitude!,
    );
    return length >= _minSegmentMeters && length <= _maxSegmentMeters;
  }

  static double _radians(double degrees) => degrees * math.pi / 180;
  static double _distance(double lat1, double lon1, double lat2, double lon2) {
    final x =
        _radians(lon2 - lon1) *
        _earthRadiusMeters *
        math.cos(_radians((lat1 + lat2) / 2));
    final y = _radians(lat2 - lat1) * _earthRadiusMeters;
    return math.sqrt(x * x + y * y);
  }

  static int? _shifted(int planned, int offset) {
    // Dart's VM uses signed 64-bit integers, matching Math.addExact's contract.
    final value = BigInt.from(planned) + BigInt.from(offset);
    if (value <= BigInt.zero || value > BigInt.parse('9223372036854775807')) {
      return null;
    }
    return value.toInt();
  }

  static bool _baselineEqual(List<_Baseline>? first, List<_Baseline> second) {
    if (first == null || first.length != second.length) return false;
    for (var i = 0; i < first.length; i++) {
      if (first[i] != second[i]) return false;
    }
    return true;
  }

  static bool _sameCandidates(
    Map<TrackingGeometryPath, double> first,
    Map<TrackingGeometryPath, double> second,
  ) => first.length == second.length && first.keys.every(second.containsKey);
  static bool _sameFix(LocationFix? first, LocationFix second) =>
      first != null &&
      first.latitude == second.latitude &&
      first.longitude == second.longitude &&
      first.accuracyMeters == second.accuracyMeters &&
      first.timeMillis == second.timeMillis &&
      first.speedMetersPerSecond == second.speedMetersPerSecond;
}
