import 'dart:math' as math;

import 'models.dart';
import 'tracking_route_geometry.dart';

/// Ordered, local station tracking. No permissions, network, speech or check-in side effects.
class StationTrackingEngine {
  StationTrackingEngine(
    List<TrackingStop> stops, {
    TrackingProgress progress = const TrackingProgress(),
    int radiusMeters = 0,
  }) : _route = List.of(stops),
       _state = progress.copyWith(),
       _configuredRadius = _normalizeRadius(radiusMeters),
       _protectCoordinateCursor =
           progress.gpsEstablished || progress.arrivedAtCurrent,
       _initializationAttempted =
           progress.gpsEstablished || progress.arrivedAtCurrent,
       _mayBootstrapOrigin =
           progress.nextIndex == 0 &&
           !progress.arrivedAtCurrent &&
           !progress.completed &&
           stops.isNotEmpty &&
           stops.first.isOrigin,
       _recoveryEligible = !progress.completed {
    _alignCursor();
  }
  List<TrackingStop> _route;
  TrackingProgress _state;
  int _configuredRadius;
  bool _gpsEnabled = true,
      _protectCoordinateCursor,
      _initializationAttempted,
      _mayBootstrapOrigin;
  LocationFix? _lastReliableFix, _dwellAnchor, _railMovementAnchor;
  int? _latestAcceptedFixMillis, _insideArrivalSinceMillis;
  String? _observedKey, _gapPassCandidateKey;
  double? _previousDistance, _minimumDistance, _smoothedSpeed;
  bool _approachConfirmed = false, _recoveryEligible, _recoveryPending = false;
  int _insideArrivalFixCount = 0,
      _lastEvaluationMillis = 0,
      _movementBasisSinceMillis = 0;
  _RecoveryCandidate? _recoveryCandidate;
  List<GpsSegmentGeometry> _segmentGeometries = [];
  final Map<(String, String), _PreparedRail> _preparedRail = {};
  _MovementBasis? _movementBasis;
  final Set<String> _releasedAnnouncements = {};

  static const maxFixAgeMillis = 30000;
  static const maxAccuracyMeters = 100.0;
  static const arrivalRadiusMeters = 120.0,
      destinationArrivalRadiusMeters = 300.0;
  static const _maxFutureFixMillis = 5000,
      _departureRadiusMeters = 220.0,
      _departureIncreaseMeters = 35.0;
  static const _fastPassRadiusMeters = 300.0, _fastPassIncreaseMeters = 80.0;
  static const _timetableAnnouncementMillis = 180000,
      _midwayRadiusMeters = 150.0;
  static const _midwayTimeWindowMillis = 600000,
      _midwayCorridorMarginMeters = 300.0;
  static const _recoveryMaxAccuracyMeters = 75.0,
      _recoveryMaxSpeedMetersPerSecond = 100.0;
  static const _recoveryMinFixes = 3, _recoveryMinDurationMillis = 6000;
  static const _destinationMaxSpeedMetersPerSecond = 3.0,
      _destinationDwellMillis = 10000;
  static const _originWaitMaxSpeedMetersPerSecond = 1.5,
      _originWaitMinMillis = 3000;

  TrackingStop? get currentStop => _get(_route, _state.nextIndex);
  TrackingProgress getProgress() => _state.copyWith();
  List<TrackingStop> get stops => List.unmodifiable(_route);
  void setRadiusMeters(int radiusMeters) =>
      _configuredRadius = _normalizeRadius(radiusMeters);
  void updateRoute(List<TrackingStop> stops) {
    final previousRoute = _route,
        oldIndex = _state.nextIndex,
        oldKey = _state.nextStopKey,
        oldStop = currentStop;
    _route = List.of(stops);
    final retained = oldKey == null
        ? -1
        : _route.indexWhere((s) => s.key == oldKey);
    int? following;
    if (retained < 0 && oldKey != null) {
      for (final stop in previousRoute.skip(oldIndex + 1)) {
        final i = _route.indexWhere((s) => s.key == stop.key);
        if (i >= 0) {
          following = i;
          break;
        }
      }
    }
    final index = retained >= 0
        ? retained
        : following ?? oldIndex.clamp(0, _route.length);
    final newStop = _get(_route, index), newKey = newStop?.key;
    final sameCoordinates =
        oldStop?.latitude == newStop?.latitude &&
        oldStop?.longitude == newStop?.longitude;
    if (!_sameRecoveryIdentities(previousRoute, _route)) {
      _recoveryCandidate = null;
      _recoveryEligible = !_state.completed;
    }
    _state = _state.copyWith(
      nextIndex: index,
      nextStopKey: newKey ?? (_route.isEmpty ? oldKey : null),
      arrivedAtCurrent:
          _state.arrivedAtCurrent && oldKey == newKey && sameCoordinates,
    );
    if (oldKey != newKey || !sameCoordinates) _resetObservation();
    _skipCancelled();
  }

  void updateSegmentGeometries(List<GpsSegmentGeometry> snapshot) {
    _segmentGeometries = List.of(snapshot);
    final keys = snapshot.map((s) => _pairKey(s.fromKey, s.toKey)).toSet();
    _preparedRail.removeWhere((key, value) => !keys.contains(key));
  }

  GpsGeometrySource? geometrySource() {
    final fix = _lastReliableFix, stop = currentStop;
    if (fix == null ||
        !_state.gpsEstablished ||
        _recoveryPending ||
        !_gpsEnabled ||
        !_isReliable(fix, _lastEvaluationMillis) ||
        stop == null) {
      return null;
    }
    final previous = _lastWhere(
      _route.take(_state.nextIndex),
      (s) => !s.cancelled,
    );
    final from = previous ?? (stop.isOrigin ? stop : null);
    final to = previous != null
        ? stop
        : _firstWhere(_route.skip(_state.nextIndex + 1), (s) => !s.cancelled);
    if (from == null || to == null) return null;
    final segment = _railSegment(from, to, _lastEvaluationMillis);
    if (segment == null) return null;
    return TrackingRouteGeometry.project(
              segment.paths.single,
              fix,
            ).projection !=
            null
        ? segment.source
        : null;
  }

  void setGpsEnabled(bool enabled) {
    if (_gpsEnabled == enabled) return;
    _gpsEnabled = enabled;
    _lastReliableFix = null;
    _smoothedSpeed = null;
    _state = _state.copyWith(gpsEstablished: false, arrivedAtCurrent: false);
    _protectCoordinateCursor = enabled;
    _initializationAttempted = false;
    _mayBootstrapOrigin = enabled;
    _recoveryEligible = enabled && !_state.completed;
    _recoveryPending = false;
    _recoveryCandidate = null;
    _movementBasis = null;
    _railMovementAnchor = null;
    _resetObservation();
  }

  void invalidateLocation() {
    _lastReliableFix = null;
    _smoothedSpeed = null;
    _recoveryEligible = _gpsEnabled && !_state.completed;
    _recoveryCandidate = null;
    _movementBasis = null;
    _railMovementAnchor = null;
    _resetObservation();
  }

  void resetLocationClock() {
    invalidateLocation();
    _latestAcceptedFixMillis = null;
  }

  void releaseAnnouncement(String key) {
    if (_state.announcedKeys.contains(key)) {
      _state = _state.copyWith(
        announcedKeys: {..._state.announcedKeys}..remove(key),
      );
      _releasedAnnouncements.add(key);
    }
  }

  void acknowledgeAnnouncement(String key) {
    _state = _state.copyWith(announcedKeys: {..._state.announcedKeys, key});
    _releasedAnnouncements.remove(key);
  }

  bool hasReliableLocation(int nowMillis) =>
      _gpsEnabled &&
      _lastReliableFix != null &&
      _isReliable(_lastReliableFix!, nowMillis);
  bool isReacquiringLocation() => _recoveryPending && !_state.completed;
  bool isOriginAnnouncementRelevant(
    String key,
    TrackingSource source,
    int nowMillis,
  ) {
    final stop = currentStop;
    if (stop == null ||
        _state.completed ||
        _recoveryPending ||
        stop.cancelled ||
        !stop.isOrigin ||
        stop.key != key) {
      return false;
    }
    final departure =
        stop.effectiveDepartureMillis ?? stop.effectiveArrivalMillis;
    if (departure == null ||
        departure < nowMillis ||
        departure - nowMillis > _timetableAnnouncementMillis) {
      return false;
    }
    final fix = _lastReliableFix;
    if (fix != null && _gpsEnabled && _isReliable(fix, nowMillis)) {
      return _shouldAnnounceWaitingOrigin(
        stop,
        fix,
        nowMillis,
        requireUnannounced: false,
      );
    }
    return source == TrackingSource.timetable;
  }

  TrackingUpdate onLocation(LocationFix fix, int nowMillis) {
    _lastEvaluationMillis = nowMillis;
    if (!_gpsEnabled) return onTimetable(nowMillis);
    if (_latestAcceptedFixMillis != null &&
        fix.timeMillis <= _latestAcceptedFixMillis!) {
      return onTimetable(nowMillis);
    }
    if (!_isReliable(fix, nowMillis)) {
      _recoveryCandidate = null;
      _railMovementAnchor = null;
      return onTimetable(nowMillis);
    }
    final oldFix = _lastReliableFix;
    _latestAcceptedFixMillis = fix.timeMillis;
    if (oldFix != null &&
        _freshPair(oldFix, fix) &&
        !_coherentRecoveryMovement(oldFix, fix)) {
      _recoveryCandidate = null;
      _railMovementAnchor = null;
      return TrackingUpdate(currentStop, TrackingSource.timetable);
    }
    if (oldFix != null &&
        fix.timeMillis - oldFix.timeMillis > maxFixAgeMillis) {
      final stop = currentStop;
      final candidate =
          stop != null &&
              !stop.isDestination &&
              _approachConfirmed &&
              (!stop.isOrigin || _state.arrivedAtCurrent)
          ? stop.key
          : null;
      _resetObservation();
      _recoveryEligible = !_state.completed;
      _recoveryCandidate = null;
      _railMovementAnchor = null;
      _gapPassCandidateKey = candidate;
      _observedKey = candidate;
    }
    _updateSpeed(fix, oldFix);
    _lastReliableFix = fix;
    _protectCoordinateCursor = true;
    if (!_initializationAttempted) {
      final anchored = _initializeAtMidwayStop(fix, nowMillis);
      if (anchored || _state.nextIndex == 0) _establishGpsCursor();
    }
    _observeMovementBasis(fix, nowMillis);
    _observeRailMovement(oldFix, fix, nowMillis);
    var advanced = false;
    if (_mayBootstrapOrigin && oldFix != null) {
      advanced = _bootstrapDepartedOrigin(oldFix, fix, nowMillis);
      if (advanced) _mayBootstrapOrigin = false;
    }
    if (!advanced && oldFix != null) {
      advanced = _recoverPassedStopAfterGap(oldFix, fix, nowMillis);
    }
    if (advanced) _clearPendingRecovery();
    if (!advanced && _canAdvanceObservedVisit(fix, oldFix, nowMillis)) {
      return _observeCurrentStop(fix, oldFix, nowMillis, canAdvance: true);
    }
    if (!advanced && _recoverLaterVisit(fix, oldFix, nowMillis)) {
      return _observeCurrentStop(fix, null, nowMillis, canAdvance: false);
    }
    if (_recoveryPending && !_nearCurrentVisit(fix)) {
      return TrackingUpdate(currentStop, TrackingSource.timetable);
    }
    return _observeCurrentStop(fix, oldFix, nowMillis, canAdvance: !advanced);
  }

  TrackingUpdate _observeCurrentStop(
    LocationFix fix,
    LocationFix? previousFix,
    int nowMillis, {
    required bool canAdvance,
  }) {
    _skipCancelled();
    if (_state.completed) return _finishedUpdate(TrackingSource.gps);
    final stop = currentStop;
    if (stop == null) return TrackingUpdate(null, _sourceForCurrent(nowMillis));
    if (!stop.hasCoordinates) return onTimetable(nowMillis);
    _observeMovementBasis(fix, nowMillis);
    if (_observedKey != stop.key) {
      _resetObservation();
      _observedKey = stop.key;
      _previousDistance = previousFix != null && _freshPair(previousFix, fix)
          ? _distance(previousFix, stop)
          : null;
    }
    final distance = _distance(fix, stop), previous = _previousDistance;
    final approachingNow =
        previous != null &&
        previous - distance >= math.max(5.0, fix.accuracyMeters * 0.1);
    if (approachingNow) _approachConfirmed = true;
    _minimumDistance = math.min(_minimumDistance ?? distance, distance);
    final retryPending =
        _releasedAnnouncements.contains(stop.key) &&
        (_approachConfirmed || _state.arrivedAtCurrent);
    final announcementCandidate =
        (!stop.isOrigin || !_state.arrivedAtCurrent) &&
            (approachingNow || retryPending) &&
            distance <= _announcementRadius() &&
            !_state.announcedKeys.contains(stop.key)
        ? stop
        : null;
    final arrivalRadius = stop.isDestination
        ? destinationArrivalRadiusMeters
        : arrivalRadiusMeters;
    final insideArrival = distance + fix.accuracyMeters <= arrivalRadius;
    _updateArrivalObservations(fix, insideArrival);
    if (!stop.isDestination &&
        insideArrival &&
        (_approachConfirmed || stop.isOrigin || _insideArrivalFixCount >= 2)) {
      _state = _state.copyWith(arrivedAtCurrent: true);
      _establishGpsCursor();
      _confirmCurrentVisit();
    }
    final speed = fix.speedMetersPerSecond;
    final lowSpeed =
        speed != null &&
        speed.isFinite &&
        speed >= 0 &&
        speed <= _destinationMaxSpeedMetersPerSecond;
    final dwelled =
        _insideArrivalSinceMillis != null &&
        fix.timeMillis - _insideArrivalSinceMillis! >= _destinationDwellMillis;
    if (stop.isDestination &&
        insideArrival &&
        previous != null &&
        ((lowSpeed && _insideArrivalFixCount >= 2) || dwelled)) {
      _state = _state.copyWith(arrivedAtCurrent: true, completed: true);
      _establishGpsCursor();
      _confirmCurrentVisit();
      _previousDistance = distance;
      return TrackingUpdate(
        stop,
        TrackingSource.gps,
        announcement: announcementCandidate == null
            ? null
            : _announce(announcementCandidate),
        destinationReached: true,
      );
    }
    if (canAdvance &&
        _shouldAdvanceCurrentStop(
          stop,
          fix,
          previousFix,
          distance,
          nowMillis,
        )) {
      _establishGpsCursor();
      _advance();
      _clearPendingRecovery();
      return _observeCurrentStop(
        fix,
        previousFix,
        nowMillis,
        canAdvance: false,
      );
    }
    _previousDistance = distance;
    final originAnnouncement =
        announcementCandidate == null &&
            _shouldAnnounceWaitingOrigin(stop, fix, nowMillis)
        ? stop
        : null;
    final event = announcementCandidate ?? originAnnouncement;
    return TrackingUpdate(
      stop,
      _sourceForCurrent(nowMillis),
      announcement: event == null || _recoveryPending ? null : _announce(event),
    );
  }

  TrackingUpdate onTimetable(int nowMillis) {
    _lastEvaluationMillis = nowMillis;
    _skipCancelled();
    if (_state.completed) return _finishedUpdate(_sourceForCurrent(nowMillis));
    final initial = currentStop;
    if (initial == null) {
      return const TrackingUpdate(null, TrackingSource.timetable);
    }
    TrackingStop stop = initial;
    if (_recoveryPending) return TrackingUpdate(stop, TrackingSource.timetable);
    if (stop.hasCoordinates && hasReliableLocation(nowMillis)) {
      final fix = _lastReliableFix!;
      return TrackingUpdate(
        stop,
        _sourceForCurrent(nowMillis),
        announcement: _shouldAnnounceWaitingOrigin(stop, fix, nowMillis)
            ? _announce(stop)
            : null,
      );
    }
    while ((!_gpsEnabled ||
            !stop.hasCoordinates ||
            !_protectCoordinateCursor) &&
        !stop.isDestination) {
      final endTime =
          stop.effectiveDepartureMillis ?? stop.effectiveArrivalMillis;
      if (endTime == null || endTime >= nowMillis) break;
      _advance();
      _skipCancelled();
      final successor = currentStop;
      if (successor == null) {
        return const TrackingUpdate(null, TrackingSource.timetable);
      }
      stop = successor;
      if (stop.hasCoordinates && hasReliableLocation(nowMillis)) {
        return TrackingUpdate(stop, _sourceForCurrent(nowMillis));
      }
    }
    final eventTime = stop.isOrigin
        ? stop.effectiveDepartureMillis ?? stop.effectiveArrivalMillis
        : stop.effectiveArrivalMillis ?? stop.effectiveDepartureMillis;
    final announce =
        eventTime != null &&
        eventTime >= nowMillis &&
        eventTime - nowMillis <= _timetableAnnouncementMillis &&
        !_state.announcedKeys.contains(stop.key);
    return TrackingUpdate(
      stop,
      TrackingSource.timetable,
      announcement: announce ? _announce(stop) : null,
    );
  }

  bool _shouldAnnounceWaitingOrigin(
    TrackingStop stop,
    LocationFix fix,
    int nowMillis, {
    bool requireUnannounced = true,
  }) {
    if (_recoveryPending ||
        !stop.isOrigin ||
        !_state.arrivedAtCurrent ||
        (requireUnannounced && _state.announcedKeys.contains(stop.key)) ||
        _insideArrivalFixCount < 2 ||
        !stop.hasCoordinates) {
      return false;
    }
    final departure =
        stop.effectiveDepartureMillis ?? stop.effectiveArrivalMillis;
    if (departure == null ||
        departure < nowMillis ||
        departure - nowMillis > _timetableAnnouncementMillis ||
        _distance(fix, stop) + fix.accuracyMeters > arrivalRadiusMeters ||
        _insideArrivalSinceMillis == null) {
      return false;
    }
    final stableFor = fix.timeMillis - _insideArrivalSinceMillis!,
        speed = fix.speedMetersPerSecond;
    final lowSpeed =
        speed != null &&
        speed.isFinite &&
        speed >= 0 &&
        speed <= _originWaitMaxSpeedMetersPerSecond;
    return stableFor >= _originWaitMinMillis &&
        (lowSpeed || (speed == null && stableFor >= _destinationDwellMillis));
  }

  void _alignCursor() {
    if (_route.isEmpty) return;
    final matching = _state.nextStopKey == null
        ? -1
        : _route.indexWhere((s) => s.key == _state.nextStopKey);
    final index = matching >= 0
        ? matching
        : _state.nextIndex.clamp(0, _route.length);
    _state = _state.copyWith(
      nextIndex: index,
      nextStopKey: _get(_route, index)?.key,
    );
    _skipCancelled();
  }

  bool _initializeAtMidwayStop(LocationFix fix, int nowMillis) {
    if (_state.gpsEstablished || _state.completed || _state.arrivedAtCurrent) {
      return false;
    }
    final candidates = <int>[];
    for (var i = 0; i < _route.length; i++) {
      final stop = _route[i];
      if (stop.cancelled || !stop.hasCoordinates) continue;
      final arrival = stop.effectiveArrivalMillis ?? stop.plannedArrivalMillis;
      final departure = stop.effectiveDepartureMillis ?? arrival;
      if ([arrival, departure].whereType<int>().any(
            (t) => t <= nowMillis + _midwayTimeWindowMillis,
          ) &&
          _distance(fix, stop) + fix.accuracyMeters <= _midwayRadiusMeters) {
        candidates.add(i);
      }
    }
    if (candidates.length == 1 && candidates.single <= _state.nextIndex) {
      final i = candidates.single;
      _state = _state.copyWith(nextIndex: i, nextStopKey: _route[i].key);
      _mayBootstrapOrigin = i == 0 && _route[i].isOrigin;
      _resetObservation();
      _clearPendingRecovery();
      return true;
    }
    return false;
  }

  bool _recoverLaterVisit(
    LocationFix fix,
    LocationFix? previous,
    int nowMillis,
  ) {
    if (!_recoveryEligible ||
        _state.completed ||
        fix.accuracyMeters > _recoveryMaxAccuracyMeters ||
        fix.timeMillis > nowMillis) {
      _recoveryCandidate = null;
      return false;
    }
    final nearby = <int>[];
    for (var i = 0; i < _route.length; i++) {
      final stop = _route[i];
      if (!stop.cancelled &&
          stop.hasCoordinates &&
          _distance(fix, stop) + fix.accuracyMeters <= _midwayRadiusMeters) {
        nearby.add(i);
      }
    }
    int? candidateIndex = nearby.length == 1 ? nearby.single : null;
    if (candidateIndex != null) {
      final stop = _route[candidateIndex];
      if (candidateIndex <= _state.nextIndex ||
          stop.key.trim().isEmpty ||
          _route.where((s) => !s.cancelled && s.key == stop.key).length != 1 ||
          _route
              .sublist(_state.nextIndex, candidateIndex)
              .any((s) => s.isDestination && !s.cancelled) ||
          (stop.stationId != null &&
              _route
                      .where(
                        (s) => !s.cancelled && s.stationId == stop.stationId,
                      )
                      .length !=
                  1)) {
        candidateIndex = null;
      }
    }
    if (candidateIndex == null) {
      _recoveryCandidate = null;
      return false;
    }
    final candidate = _route[candidateIndex];
    _recoveryPending = true;
    if (previous != null &&
        _freshPair(previous, fix) &&
        !_coherentRecoveryMovement(previous, fix)) {
      _recoveryCandidate = null;
      return false;
    }
    final evidence = _recoveryCandidate;
    final valid =
        evidence != null &&
        evidence.key == candidate.key &&
        _freshPair(evidence.latestFix, fix) &&
        _coherentRecoveryMovement(evidence.latestFix, fix);
    final updated = valid
        ? _RecoveryCandidate(
            candidate.key,
            evidence.firstFix,
            fix,
            evidence.count + 1,
          )
        : _RecoveryCandidate(candidate.key, fix, fix, 1);
    _recoveryCandidate = updated;
    if (updated.count < _recoveryMinFixes ||
        fix.timeMillis - updated.firstFix.timeMillis <
            _recoveryMinDurationMillis) {
      return false;
    }
    _state = _state.copyWith(
      nextIndex: candidateIndex,
      nextStopKey: candidate.key,
      arrivedAtCurrent: false,
    );
    _mayBootstrapOrigin = false;
    _resetObservation();
    _establishGpsCursor();
    _clearPendingRecovery();
    return true;
  }

  bool _coherentRecoveryMovement(LocationFix previous, LocationFix fix) =>
      _freshPair(previous, fix) &&
      distanceMeters(
            previous.latitude,
            previous.longitude,
            fix.latitude,
            fix.longitude,
          ) <=
          _recoveryMaxSpeedMetersPerSecond *
                  (fix.timeMillis - previous.timeMillis) /
                  1000 +
              previous.accuracyMeters +
              fix.accuracyMeters;
  bool _nearCurrentVisit(LocationFix fix) =>
      currentStop != null &&
      currentStop!.hasCoordinates &&
      _distance(fix, currentStop!) + fix.accuracyMeters <= _midwayRadiusMeters;
  void _confirmCurrentVisit() {
    _recoveryEligible = false;
    _clearPendingRecovery();
  }

  void _clearPendingRecovery() {
    _recoveryPending = false;
    _recoveryCandidate = null;
  }

  bool _bootstrapDepartedOrigin(
    LocationFix previous,
    LocationFix fix,
    int nowMillis,
  ) {
    if (_state.nextIndex != 0 ||
        _state.arrivedAtCurrent ||
        _state.completed ||
        fix.timeMillis - previous.timeMillis > maxFixAgeMillis) {
      return false;
    }
    final origin = currentStop;
    if (origin == null || !origin.isOrigin || !origin.hasCoordinates) {
      return false;
    }
    return _directedDeparture(
      origin,
      previous,
      fix,
      nowMillis,
      _firstWhere(_route.skip(1), (s) => !s.cancelled),
    );
  }

  bool _recoverPassedStopAfterGap(
    LocationFix previous,
    LocationFix fix,
    int nowMillis,
  ) {
    final stop = currentStop;
    if (stop == null ||
        _gapPassCandidateKey != stop.key ||
        stop.isDestination ||
        fix.timeMillis - previous.timeMillis > maxFixAgeMillis) {
      return false;
    }
    return _directedDeparture(
      stop,
      previous,
      fix,
      nowMillis,
      _firstWhere(_route.skip(_state.nextIndex + 1), (s) => !s.cancelled),
    );
  }

  bool _directedDeparture(
    TrackingStop stop,
    LocationFix previous,
    LocationFix fix,
    int nowMillis,
    TrackingStop? next,
  ) {
    if (next == null ||
        !stop.hasCoordinates ||
        !next.hasCoordinates ||
        !_hasMovementOnCurrentBasis(previous)) {
      return false;
    }
    final from = _distance(fix, stop),
        priorFrom = _distance(previous, stop),
        toNext = _distance(fix, next),
        priorToNext = _distance(previous, next);
    final between = distanceMeters(
      stop.latitude!,
      stop.longitude!,
      next.latitude!,
      next.longitude!,
    );
    final movement = math.max(
      _departureIncreaseMeters,
      fix.accuracyMeters + previous.accuracyMeters,
    );
    final trend = math.max(5.0, fix.accuracyMeters * 0.1),
        departure = math.max(
          from - priorFrom,
          from - (_minimumDistance ?? priorFrom),
        );
    final rail = _railSegment(stop, next, nowMillis);
    if (rail != null) {
      final pair = _directedRailPair(rail, previous, fix);
      if (pair == null) return false;
      final along = pair.$2.fraction * pair.$2.length,
          remaining = pair.$2.length - along;
      if ((from > _departureRadiusMeters + fix.accuracyMeters ||
              remaining + 2 * fix.accuracyMeters < along) &&
          _cumulativeRailMovement(rail, pair.$2) >= movement &&
          (pair.$2.fraction - pair.$1.fraction) * pair.$2.length >= trend &&
          from - priorFrom >= trend) {
        _establishGpsCursor();
        _advance();
        return true;
      }
      return false;
    }
    if ((from > _departureRadiusMeters + fix.accuracyMeters ||
            toNext + 2 * fix.accuracyMeters < from) &&
        departure >= movement &&
        from - priorFrom >= trend &&
        priorToNext - toNext >= trend &&
        toNext < between &&
        from + toNext <= between + _midwayCorridorMarginMeters) {
      _establishGpsCursor();
      _advance();
      return true;
    }
    return false;
  }

  void _updateArrivalObservations(LocationFix fix, bool insideArrival) {
    if (!insideArrival) {
      _insideArrivalSinceMillis = null;
      _insideArrivalFixCount = 0;
      _dwellAnchor = null;
      return;
    }
    _insideArrivalFixCount++;
    final anchor = _dwellAnchor;
    final stable =
        anchor != null &&
        distanceMeters(
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
            );
    if (!stable) {
      _dwellAnchor = fix;
      _insideArrivalSinceMillis = fix.timeMillis;
    }
  }

  bool _canAdvanceObservedVisit(
    LocationFix fix,
    LocationFix? previous,
    int nowMillis,
  ) {
    final stop = currentStop;
    if (stop == null ||
        _observedKey != stop.key ||
        !stop.hasCoordinates ||
        previous == null ||
        !_freshPair(previous, fix)) {
      return false;
    }
    return _shouldAdvanceCurrentStop(
      stop,
      fix,
      previous,
      _distance(fix, stop),
      nowMillis,
    );
  }

  bool _shouldAdvanceCurrentStop(
    TrackingStop stop,
    LocationFix fix,
    LocationFix? previousFix,
    double distance,
    int nowMillis,
  ) {
    final previous = _previousDistance;
    if (previous == null) return false;
    final rising =
        distance > previous + math.max(5.0, fix.accuracyMeters * 0.1);
    final riseFromMinimum =
        distance - math.min(_minimumDistance ?? distance, distance);
    final towardSuccessor =
        previousFix != null &&
        _movingTowardSuccessor(
          stop,
          previousFix,
          fix,
          distance,
          riseFromMinimum,
          nowMillis,
        );
    final leftArrival =
        !stop.isDestination &&
        _state.arrivedAtCurrent &&
        rising &&
        (distance > _departureRadiusMeters || towardSuccessor) &&
        riseFromMinimum >= _departureIncreaseMeters;
    final fastPass =
        !stop.isOrigin &&
        !stop.isDestination &&
        _approachConfirmed &&
        rising &&
        (_minimumDistance ?? double.maxFinite) <= _fastPassRadiusMeters &&
        (towardSuccessor ||
            (distance > _departureRadiusMeters &&
                riseFromMinimum >= _fastPassIncreaseMeters));
    return leftArrival || fastPass;
  }

  bool _movingTowardSuccessor(
    TrackingStop stop,
    LocationFix previous,
    LocationFix fix,
    double fromStop,
    double riseFromMinimum,
    int nowMillis,
  ) {
    if (!_freshPair(previous, fix)) return false;
    final next = _firstWhere(
      _route.skip(_state.nextIndex + 1),
      (s) => !s.cancelled,
    );
    if (next == null ||
        !stop.hasCoordinates ||
        !next.hasCoordinates ||
        !_hasMovementOnCurrentBasis(previous)) {
      return false;
    }
    final rail = _railSegment(stop, next, nowMillis);
    if (rail != null) {
      final pair = _directedRailPair(rail, previous, fix);
      if (pair == null) return false;
      final progress = pair.$2.fraction * pair.$2.length,
          movement = _cumulativeRailMovement(rail, pair.$2);
      return (pair.$2.fraction - pair.$1.fraction) * pair.$2.length >=
              math.max(5.0, fix.accuracyMeters * 0.1) &&
          movement >=
              math.max(
                _departureIncreaseMeters,
                (fix.accuracyMeters + previous.accuracyMeters) / 2,
              ) &&
          pair.$2.length - progress + 2 * fix.accuracyMeters < progress;
    }
    final toNext = _distance(fix, next),
        priorToNext = _distance(previous, next);
    return priorToNext - toNext >= math.max(5.0, fix.accuracyMeters * 0.1) &&
        math.max(priorToNext - toNext, riseFromMinimum) >=
            math.max(
              _departureIncreaseMeters,
              (fix.accuracyMeters + previous.accuracyMeters) / 2,
            ) &&
        toNext + 2 * fix.accuracyMeters < fromStop;
  }

  TrackingGeometrySegment? _railSegment(
    TrackingStop from,
    TrackingStop to,
    int nowMillis,
  ) {
    if (!from.hasCoordinates ||
        !to.hasCoordinates ||
        from.key.trim().isEmpty ||
        to.key.trim().isEmpty ||
        _route.where((s) => s.key == from.key).length != 1 ||
        _route.where((s) => s.key == to.key).length != 1) {
      return null;
    }
    final bindings = _segmentGeometries
        .where(
          (s) =>
              s.source == GpsGeometrySource.tripPolyline &&
              s.fromKey == from.key &&
              s.toKey == to.key,
        )
        .toList();
    if (bindings.length != 1) return null;
    final binding = bindings.single,
        age = nowMillis - binding.geometry.fetchedAtMillis;
    if (binding.geometry.fetchedAtMillis <= 0 || age < 0 || age > 900000) {
      return null;
    }
    final first = RoutePoint(from.latitude!, from.longitude!),
        last = RoutePoint(to.latitude!, to.longitude!);
    final key = _pairKey(from.key, to.key), cached = _preparedRail[key];
    if (cached == null ||
        cached.binding != binding ||
        cached.from != first ||
        cached.to != last) {
      _preparedRail[key] = _PreparedRail(
        binding,
        first,
        last,
        TrackingRouteGeometry.prepare(
          binding.geometry,
          from,
          to,
          binding.source,
          nowMillis,
        ),
      );
    }
    return _preparedRail[key]?.segment;
  }

  void _observeMovementBasis(LocationFix fix, int nowMillis) {
    final from = currentStop,
        to = _firstWhere(
          _route.skip(_state.nextIndex + 1),
          (s) => !s.cancelled,
        );
    if (from == null || to == null) return;
    final points = _railSegment(from, to, nowMillis)?.paths.single.points;
    final basis = _MovementBasis(
      from.key,
      to.key,
      from.hasCoordinates ? RoutePoint(from.latitude!, from.longitude!) : null,
      to.hasCoordinates ? RoutePoint(to.latitude!, to.longitude!) : null,
      points,
    );
    if (basis != _movementBasis) {
      _movementBasis = basis;
      _railMovementAnchor = null;
      _movementBasisSinceMillis = fix.timeMillis;
    }
  }

  bool _hasMovementOnCurrentBasis(LocationFix previous) =>
      _movementBasis != null &&
      previous.timeMillis >= _movementBasisSinceMillis;
  void _observeRailMovement(
    LocationFix? previous,
    LocationFix fix,
    int nowMillis,
  ) {
    final from = currentStop,
        to = _firstWhere(
          _route.skip(_state.nextIndex + 1),
          (s) => !s.cancelled,
        );
    if (from == null || to == null) return;
    final segment = _railSegment(from, to, nowMillis);
    if (segment == null || segment.paths.length != 1) {
      _railMovementAnchor = null;
      return;
    }
    final path = segment.paths.single;
    if (TrackingRouteGeometry.project(path, fix).projection == null) {
      _railMovementAnchor = null;
      return;
    }
    final pair = previous != null && _hasMovementOnCurrentBasis(previous)
        ? _directedRailPair(segment, previous, fix)
        : null;
    final anchor = _railMovementAnchor;
    if (pair == null ||
        anchor == null ||
        TrackingRouteGeometry.project(path, anchor).projection == null) {
      _railMovementAnchor = fix;
    }
  }

  double _cumulativeRailMovement(
    TrackingGeometrySegment segment,
    TrackingGeometryProjection current,
  ) {
    final anchor = _railMovementAnchor;
    if (anchor == null || segment.paths.length != 1) return 0;
    final first = TrackingRouteGeometry.project(
      segment.paths.single,
      anchor,
    ).projection;
    return first == null
        ? 0
        : (current.fraction - first.fraction) * current.length;
  }

  (TrackingGeometryProjection, TrackingGeometryProjection)? _directedRailPair(
    TrackingGeometrySegment segment,
    LocationFix previous,
    LocationFix fix,
  ) {
    if (!_freshPair(previous, fix) || segment.paths.length != 1) return null;
    final path = segment.paths.single,
        prior = TrackingRouteGeometry.project(
          segment.paths.single,
          previous,
        ).projection;
    final current = TrackingRouteGeometry.project(path, fix).projection;
    if (prior == null || current == null) return null;
    final movement = (current.fraction - prior.fraction) * path.length;
    return movement >= 0 &&
            movement <=
                _recoveryMaxSpeedMetersPerSecond *
                        (fix.timeMillis - previous.timeMillis) /
                        1000 +
                    previous.accuracyMeters +
                    fix.accuracyMeters
        ? (prior, current)
        : null;
  }

  void _establishGpsCursor() {
    _state = _state.copyWith(gpsEstablished: true);
    _initializationAttempted = true;
  }

  void _skipCancelled() {
    while (!_state.completed && currentStop?.cancelled == true) {
      _advance();
    }
  }

  void _advance() {
    final index = math.min(_state.nextIndex + 1, _route.length);
    _state = _state.copyWith(
      nextIndex: index,
      nextStopKey: _get(_route, index)?.key,
      arrivedAtCurrent: false,
    );
    _resetObservation();
  }

  void _resetObservation() {
    _observedKey = null;
    _previousDistance = null;
    _minimumDistance = null;
    _approachConfirmed = false;
    _gapPassCandidateKey = null;
    _insideArrivalSinceMillis = null;
    _insideArrivalFixCount = 0;
    _dwellAnchor = null;
  }

  TrackingStop _announce(TrackingStop stop) {
    _state = _state.copyWith(
      announcedKeys: {..._state.announcedKeys, stop.key},
    );
    _releasedAnnouncements.remove(stop.key);
    return stop;
  }

  TrackingSource _sourceForCurrent(int nowMillis) =>
      !_recoveryPending &&
          _state.gpsEstablished &&
          currentStop?.hasCoordinates == true &&
          hasReliableLocation(nowMillis)
      ? TrackingSource.gps
      : TrackingSource.timetable;
  TrackingUpdate _finishedUpdate(TrackingSource source) =>
      TrackingUpdate(currentStop, source, destinationReached: true);
  void _updateSpeed(LocationFix fix, LocationFix? previous) {
    final speed = fix.speedMetersPerSecond;
    final measured = speed != null && speed.isFinite && speed >= 0
        ? speed
        : previous != null && _freshPair(previous, fix)
        ? distanceMeters(
                previous.latitude,
                previous.longitude,
                fix.latitude,
                fix.longitude,
              ) /
              ((fix.timeMillis - previous.timeMillis) / 1000)
        : null;
    if (measured != null) {
      _smoothedSpeed = _smoothedSpeed == null
          ? measured
          : _smoothedSpeed! * 0.65 + measured * 0.35;
    }
  }

  double _announcementRadius() => _configuredRadius > 0
      ? _configuredRadius.toDouble()
      : ((_smoothedSpeed ?? 0) * 45).clamp(300, 2000).toDouble();
  bool _isReliable(LocationFix fix, int nowMillis) =>
      validCoordinates(fix.latitude, fix.longitude) &&
      fix.accuracyMeters.isFinite &&
      fix.accuracyMeters >= 0 &&
      fix.accuracyMeters <= maxAccuracyMeters &&
      fix.timeMillis >= nowMillis - maxFixAgeMillis &&
      fix.timeMillis <= nowMillis + _maxFutureFixMillis;
  static bool _freshPair(LocationFix a, LocationFix b) =>
      b.timeMillis - a.timeMillis >= 1 &&
      b.timeMillis - a.timeMillis <= maxFixAgeMillis;
  static double _distance(LocationFix fix, TrackingStop stop) => distanceMeters(
    fix.latitude,
    fix.longitude,
    stop.latitude!,
    stop.longitude!,
  );
  static int _normalizeRadius(int radius) =>
      {300, 500, 1000, 2000}.contains(radius) ? radius : 0;
  static T? _get<T>(List<T> values, int i) =>
      i >= 0 && i < values.length ? values[i] : null;
  static T? _firstWhere<T>(Iterable<T> values, bool Function(T) test) {
    for (final value in values) {
      if (test(value)) return value;
    }
    return null;
  }

  static T? _lastWhere<T>(Iterable<T> values, bool Function(T) test) {
    T? found;
    for (final value in values) {
      if (test(value)) found = value;
    }
    return found;
  }

  static (String, String) _pairKey(String from, String to) => (from, to);
  static bool _sameRecoveryIdentities(
    List<TrackingStop> a,
    List<TrackingStop> b,
  ) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      final first = a[i], last = b[i];
      if ((
            first.key,
            first.stationId,
            first.latitude,
            first.longitude,
            first.cancelled,
            first.isDestination,
          ) !=
          (
            last.key,
            last.stationId,
            last.latitude,
            last.longitude,
            last.cancelled,
            last.isDestination,
          )) {
        return false;
      }
    }
    return true;
  }
}

class _MovementBasis {
  const _MovementBasis(
    this.fromKey,
    this.toKey,
    this.from,
    this.to,
    this.points,
  );
  final String fromKey, toKey;
  final RoutePoint? from, to;
  final List<RoutePoint>? points;
  @override
  bool operator ==(Object other) =>
      other is _MovementBasis &&
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

class _RecoveryCandidate {
  const _RecoveryCandidate(this.key, this.firstFix, this.latestFix, this.count);
  final String key;
  final LocationFix firstFix, latestFix;
  final int count;
}

class _PreparedRail {
  const _PreparedRail(this.binding, this.from, this.to, this.segment);
  final GpsSegmentGeometry binding;
  final RoutePoint from, to;
  final TrackingGeometrySegment? segment;
}
