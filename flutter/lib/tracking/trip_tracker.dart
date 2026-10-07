import 'models.dart';
import 'station_tracking_engine.dart';
import 'gps_journey_time_estimator.dart';
import 'journey_time_resolver.dart';
import 'tracking_location_clock.dart';
import 'tracking_route_geometry.dart';
import 'trip_change_monitor.dart';
import 'trip_progress.dart';

class TrackingJourney {
  TrackingJourney({
    required this.statusId,
    required this.sessionRevision,
    required this.stops,
    this.contentRevision = 0,
    this.lineName = '',
    this.destinationName = '',
    this.replacementBus = false,
    this.tripIdentity = '',
    this.manualDepartureMillis,
    this.manualArrivalMillis,
  });
  final int statusId, contentRevision;
  final String sessionRevision, lineName, destinationName, tripIdentity;
  final List<TrackingStop> stops;
  final bool replacementBus;
  final int? manualDepartureMillis, manualArrivalMillis;
  String get identity => '$sessionRevision:$statusId';
  Map<String, Object?> toJson() => {
    'statusId': statusId,
    'sessionRevision': sessionRevision,
    'contentRevision': contentRevision,
    'lineName': lineName,
    'destinationName': destinationName,
    'tripIdentity': tripIdentity,
    'replacementBus': replacementBus,
    'manualDepartureMillis': manualDepartureMillis,
    'manualArrivalMillis': manualArrivalMillis,
    'stops': stops.map((s) => s.toJson()).toList(),
  };
  factory TrackingJourney.fromJson(Map<String, dynamic> j) => TrackingJourney(
    statusId: j['statusId'] as int,
    sessionRevision: j['sessionRevision'] as String,
    contentRevision: j['contentRevision'] as int? ?? 0,
    tripIdentity: j['tripIdentity'] as String? ?? '',
    lineName: j['lineName'] as String? ?? '',
    destinationName: j['destinationName'] as String? ?? '',
    replacementBus: j['replacementBus'] == true,
    manualDepartureMillis: j['manualDepartureMillis'] as int?,
    manualArrivalMillis: j['manualArrivalMillis'] as int?,
    stops: (j['stops'] as List)
        .map((s) => TrackingStop.fromJson(Map<String, dynamic>.from(s as Map)))
        .toList(),
  );
}

class TripTrackingSnapshot {
  const TripTrackingSnapshot({
    required this.journey,
    required this.update,
    required this.progress,
    this.gpsTimes,
    this.eta,
    this.etaReason,
    this.geometrySource,
    this.evaluatedAtMillis = 0,
    this.reacquiring = false,
    this.changes = const [],
  });
  final TrackingJourney journey;
  final TrackingUpdate update;
  final TrackingProgress progress;
  final GpsJourneyTimes? gpsTimes;
  final JourneyTime? eta;
  final GpsTimeUnavailableReason? etaReason;
  final GpsGeometrySource? geometrySource;
  final bool reacquiring;
  final List<TripChangeEvent> changes;
  final int evaluatedAtMillis;
  TripProgressModel get progressModel => TripProgressModel.from(
    stops: journey.stops,
    progress: progress,
    source: update.source,
    nowMillis: evaluatedAtMillis > 0
        ? evaluatedAtMillis
        : DateTime.now().millisecondsSinceEpoch,
    gpsTimes: gpsTimes,
    destinationName: journey.destinationName,
    manualDestinationArrival: journey.manualArrivalMillis,
  );

  /// Platform/UI payload intentionally excludes the device position and history.
  Map<String, dynamic> toJson() => {
    'statusId': journey.statusId,
    'sessionRevision': journey.sessionRevision,
    'contentRevision': journey.contentRevision,
    'lineName': journey.lineName,
    'destinationName': journey.destinationName,
    'replacementBus': journey.replacementBus,
    'evaluatedAtMillis': evaluatedAtMillis,
    'tripProgress': progressModel.toJson(),
    'nextIndex': progress.nextIndex,
    'nextStopKey': progress.nextStopKey,
    'arrivedAtCurrent': progress.arrivedAtCurrent,
    'gpsEstablished': progress.gpsEstablished,
    'completed': progress.completed,
    'source': update.source.name,
    'destinationReached': update.destinationReached,
    'stop': update.stop?.toJson(),
    'announcement': update.announcement?.toJson(),
    'reacquiring': reacquiring,
    'etaReason': etaReason?.name,
    'geometrySource': geometrySource?.name,
    'eta': eta == null
        ? null
        : {
            'millis': eta!.millis,
            'source': eta!.source.name,
            'sourceLabel': eta!.sourceLabel,
            'plannedMillis': eta!.plannedMillis,
            'delayMinutes': eta!.delayMinutes,
          },
    'gpsTimes': gpsTimes == null
        ? null
        : {
            'updatedAtMillis': gpsTimes!.updatedAtMillis,
            'validUntilMillis': gpsTimes!.validUntilMillis,
            'stopTimes': gpsTimes!.stopTimes
                .map(
                  (t) => {
                    'stopKey': t.stopKey,
                    'stationId': t.stationId,
                    'plannedArrivalMillis': t.plannedArrivalMillis,
                    'plannedDepartureMillis': t.plannedDepartureMillis,
                    'arrivalMillis': t.arrivalMillis,
                    'departureMillis': t.departureMillis,
                    'arrivalObserved': t.arrivalObserved,
                    'departureObserved': t.departureObserved,
                  },
                )
                .toList(),
          },
    'changes': changes
        .map(
          (c) => {
            'key': c.key,
            'kind': c.kind.name,
            'stopKey': c.stopKey,
            'title': c.title,
            'message': c.message,
          },
        )
        .toList(),
  };
}

/// Runtime facade shared by native Android/iOS hosts. All mutations are synchronous
/// so session/generation checks can surround platform and network suspensions.
class TripTracker {
  TrackingJourney? _journey;
  StationTrackingEngine? _engine;
  final GpsJourneyTimeEstimator _estimator = GpsJourneyTimeEstimator();
  TrackingLocationClock _clock = TrackingLocationClock();
  final TripChangeMonitor _changes = TripChangeMonitor();
  LocationFix? _latestFix;
  List<GpsSegmentGeometry> _geometries = [];
  bool _gpsEnabled = true;
  int _radiusMeters = 0;
  TripTrackingSnapshot? _snapshot;
  TripTrackingSnapshot? get snapshot => _snapshot;
  TrackingJourney? get journey => _journey;
  void start(
    TrackingJourney journey, {
    TrackingProgress progress = const TrackingProgress(),
    bool gpsEnabled = true,
    int radiusMeters = 0,
  }) {
    _radiusMeters = radiusMeters;
    _journey = journey;
    _engine = StationTrackingEngine(
      journey.stops,
      progress: progress,
      radiusMeters: radiusMeters,
    );
    _gpsEnabled = gpsEnabled;
    _engine!.setGpsEnabled(gpsEnabled);
    _clock = TrackingLocationClock();
    _estimator.reset();
    _latestFix = null;
    _geometries = [];
    _snapshot = null;
    _changes.reset(journey.statusId);
  }

  void stop() {
    _journey = null;
    _engine = null;
    _latestFix = null;
    _geometries = [];
    _snapshot = null;
    _estimator.reset();
    _clock = TrackingLocationClock();
  }

  bool restore(
    Map<String, dynamic> cache, {
    required String expectedSessionRevision,
    required int expectedStatusId,
    bool gpsEnabled = true,
    int radiusMeters = 0,
  }) {
    try {
      if (cache['version'] != 1) return false;
      final journey = TrackingJourney.fromJson(
        Map<String, dynamic>.from(cache['journey'] as Map),
      );
      if (journey.sessionRevision != expectedSessionRevision ||
          journey.statusId != expectedStatusId) {
        return false;
      }
      start(
        journey,
        progress: TrackingProgress.fromJson(
          Map<String, dynamic>.from(cache['progress'] as Map),
        ),
        gpsEnabled: gpsEnabled,
        radiusMeters: radiusMeters,
      );
      final changeState = cache['changes'];
      if (changeState is Map) {
        _changes.reset(
          journey.statusId,
          state: TripChangeMonitorState.fromJson(
            Map<String, dynamic>.from(changeState),
          ),
        );
      }
      return true;
    } catch (_) {
      return false;
    }
  }

  Map<String, dynamic>? checkpoint() {
    final journey = _journey, engine = _engine;
    if (journey == null || engine == null) return null;
    return {
      'version': 1,
      'journey': journey.toJson(),
      'progress': engine.getProgress().toJson(),
      'changes': _changes.getState()?.toJson(),
    };
  }

  bool updateRoute(TrackingJourney journey) {
    if (_journey == null) {
      start(journey);
      return true;
    }
    if (journey.identity != _journey!.identity) {
      start(journey, gpsEnabled: _gpsEnabled, radiusMeters: _radiusMeters);
      return true;
    }
    final previous = _journey!;
    if (journey.contentRevision < previous.contentRevision ||
        journey.stops.isEmpty) {
      return false;
    }
    final tripChanged =
        previous.tripIdentity.isNotEmpty &&
        journey.tripIdentity.isNotEmpty &&
        previous.tripIdentity != journey.tripIdentity;
    final boundariesChanged =
        tripChanged || !_sameBoundaries(previous.stops, journey.stops);
    if (boundariesChanged) {
      final old = _engine!.getProgress();
      final next = tripChanged
          ? const TrackingProgress()
          : _rebaseProgress(previous.stops, journey.stops, old);
      _engine = StationTrackingEngine(
        journey.stops,
        progress: next,
        radiusMeters: _radiusMeters,
      );
      _engine!.setGpsEnabled(_gpsEnabled);
      _latestFix = null;
      _geometries = [];
      _estimator.reset();
      _snapshot = null;
    } else {
      _engine!.updateRoute(journey.stops);
      if (previous.manualDepartureMillis != journey.manualDepartureMillis ||
          previous.manualArrivalMillis != journey.manualArrivalMillis) {
        _estimator.invalidateLocation();
      }
    }
    _journey = journey;
    return true;
  }

  void setRadiusMeters(int value) {
    _radiusMeters = value;
    _engine?.setRadiusMeters(value);
  }

  void setGpsEnabled(bool value) {
    if (_gpsEnabled == value) return;
    _gpsEnabled = value;
    _engine?.setGpsEnabled(value);
    _latestFix = null;
    _estimator.invalidateLocation();
  }

  void invalidateLocation() {
    _latestFix = null;
    _engine?.invalidateLocation();
    _estimator.invalidateLocation();
  }

  /// A new native monotonic origin keeps the persisted visit and speech identity
  /// while replacing ordering and transient position/forecast evidence.
  void resetNativeClock() {
    _clock = TrackingLocationClock();
    _latestFix = null;
    _engine?.resetLocationClock();
    _estimator.reset();
  }

  void updateSegmentGeometries(List<GpsSegmentGeometry> values) {
    _geometries = List.of(values);
    _engine?.updateSegmentGeometries(values);
  }

  void acknowledgeAnnouncement(String key) =>
      _engine?.acknowledgeAnnouncement(key);
  void releaseAnnouncement(String key) => _engine?.releaseAnnouncement(key);
  bool isOriginAnnouncementRelevant(
    String key,
    TrackingSource source,
    int nowMillis,
  ) => _engine?.isOriginAnnouncementRelevant(key, source, nowMillis) ?? false;
  TripTrackingSnapshot? update({
    required int nowMillis,
    LocationFix? fix,
    TripChangeSnapshot? successfulApiSnapshot,
  }) {
    final journey = _journey, engine = _engine;
    if (journey == null || engine == null) return null;
    if (fix != null &&
        (_latestFix == null || fix.timeMillis > _latestFix!.timeMillis)) {
      _latestFix = fix;
    }
    final update = fix == null
        ? engine.onTimetable(nowMillis)
        : engine.onLocation(fix, nowMillis);
    final progress = engine.getProgress();
    final times = _estimator.update(
      route: journey.stops,
      progress: progress,
      source: update.source,
      fix: _latestFix,
      nowMillis: nowMillis,
      segmentGeometries: _geometries,
      useRoadGeometry: journey.replacementBus,
    );
    final stop = update.stop;
    final manual = stop?.isOrigin == true
        ? journey.manualDepartureMillis
        : stop?.isDestination == true
        ? journey.manualArrivalMillis
        : null;
    final manualTime = manual == null
        ? null
        : DateTime.fromMillisecondsSinceEpoch(
            manual,
            isUtc: true,
          ).toIso8601String();
    final eta = stop == null
        ? null
        : stop.isOrigin
        ? JourneyTimeResolver.departure(
            stop,
            times,
            nowMillis,
            manualTime: manualTime,
          )
        : JourneyTimeResolver.arrival(
            stop,
            times,
            nowMillis,
            manualTime: manualTime,
          );
    var changes = successfulApiSnapshot == null
        ? <TripChangeEvent>[]
        : _changes.observe(successfulApiSnapshot);
    if (journey.replacementBus) {
      changes = changes
          .where((e) => e.kind != TripChangeKind.platform)
          .toList();
    }
    _snapshot = TripTrackingSnapshot(
      journey: journey,
      update: update,
      progress: progress,
      gpsTimes: times,
      eta: eta,
      etaReason: _estimator.unavailableReason(),
      geometrySource: _estimator.geometrySource() ?? engine.geometrySource(),
      reacquiring: engine.isReacquiringLocation(),
      changes: changes,
      evaluatedAtMillis: nowMillis,
    );
    return _snapshot;
  }

  TripTrackingSnapshot? observeNativeLocation({
    required double latitude,
    required double longitude,
    required double accuracyMeters,
    required int monotonicNanos,
    required int nowMonotonicNanos,
    required int nowMillis,
    required bool hasSpeed,
    required double speedMetersPerSecond,
  }) {
    final observation = _clock.observe(
      latitude: latitude,
      longitude: longitude,
      accuracyMeters: accuracyMeters,
      monotonicNanos: monotonicNanos,
      nowMonotonicNanos: nowMonotonicNanos,
      nowMillis: nowMillis,
      hasSpeed: hasSpeed,
      speedMetersPerSecond: speedMetersPerSecond,
    );
    if (observation.clockChanged) {
      _latestFix = null;
      _engine?.resetLocationClock();
      _estimator.reset();
    }
    return update(
      nowMillis: nowMillis,
      fix: observation.isNew ? observation.fix : null,
    );
  }

  static bool _sameBoundaries(
    List<TrackingStop> first,
    List<TrackingStop> second,
  ) {
    final origins = first.where((s) => s.isOrigin).map((s) => s.key).toList();
    final nextOrigins = second
        .where((s) => s.isOrigin)
        .map((s) => s.key)
        .toList();
    final destinations = first
        .where((s) => s.isDestination)
        .map((s) => s.key)
        .toList();
    final nextDestinations = second
        .where((s) => s.isDestination)
        .map((s) => s.key)
        .toList();
    return _sameKeys(origins, nextOrigins) &&
        _sameKeys(destinations, nextDestinations);
  }

  static bool _sameKeys(List<String> first, List<String> second) {
    if (first.length != second.length) return false;
    for (var i = 0; i < first.length; i++) {
      if (first[i] != second[i]) return false;
    }
    return true;
  }

  static TrackingProgress _rebaseProgress(
    List<TrackingStop> previous,
    List<TrackingStop> stops,
    TrackingProgress old,
  ) {
    var index = old.nextStopKey == null
        ? -1
        : stops.indexWhere((s) => s.key == old.nextStopKey);
    if (index < 0) {
      for (final former in previous.skip(old.nextIndex + 1)) {
        final following = stops.indexWhere((s) => s.key == former.key);
        if (following >= 0) {
          index = following;
          break;
        }
      }
    }
    if (index < 0) index = old.nextIndex.clamp(0, stops.length - 1);
    final next = stops[index],
        before = old.nextIndex >= 0 && old.nextIndex < previous.length
            ? previous[old.nextIndex]
            : null;
    return old.copyWith(
      nextIndex: index,
      nextStopKey: next.key,
      completed: false,
      arrivedAtCurrent:
          old.arrivedAtCurrent &&
          old.nextStopKey == next.key &&
          before?.latitude == next.latitude &&
          before?.longitude == next.longitude,
      announcedKeys: old.announcedKeys.intersection(
        stops.map((s) => s.key).toSet(),
      ),
    );
  }
}
