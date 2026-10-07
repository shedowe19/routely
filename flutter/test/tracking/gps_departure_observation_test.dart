import 'package:flutter_test/flutter_test.dart';
import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/station_tracking_engine.dart';
import 'package:routely/tracking/gps_journey_time_estimator.dart';
import 'package:routely/tracking/tracking_route_geometry.dart';

const base = 1800000000000, metersPerDegree = 111195.0;
TrackingStop stop(
  String key,
  double meters,
  int? arrival,
  int? departure, {
  bool origin = false,
  bool destination = false,
}) => TrackingStop(
  key: key,
  stationId: key.hashCode,
  name: key,
  latitude: 0,
  longitude: meters / metersPerDegree,
  plannedArrivalMillis: arrival,
  effectiveArrivalMillis: arrival,
  plannedDepartureMillis: departure,
  effectiveDepartureMillis: departure,
  isOrigin: origin,
  isDestination: destination,
);
List<TrackingStop> route() => [
  stop('origin', 0, null, base, origin: true),
  stop('next', 2000, base + 250000, base + 280000),
  stop('destination', 4000, base + 530000, null, destination: true),
];
GpsJourneyTimes? journey(
  List<TrackingStop> stops, {
  required int dwellUntil,
  required double? speed,
  double dwellPositionMeters = 0,
  List<GpsSegmentGeometry> segments = const [],
}) {
  final engine = StationTrackingEngine(stops)
        ..updateSegmentGeometries(segments),
      estimator = GpsJourneyTimeEstimator();
  GpsJourneyTimes? times;
  void accept(double meters, int elapsed, double? reportedSpeed) {
    final fix = LocationFix(
      latitude: 0,
      longitude: meters / metersPerDegree,
      accuracyMeters: 10,
      timeMillis: base + elapsed,
      speedMetersPerSecond: reportedSpeed,
    );
    final update = engine.onLocation(fix, fix.timeMillis);
    expect(update.destinationReached, isFalse);
    times = estimator.update(
      route: stops,
      progress: engine.getProgress(),
      source: update.source,
      fix: fix,
      nowMillis: fix.timeMillis,
      segmentGeometries: segments,
      useRoadGeometry: segments.any(
        (s) => s.source == GpsGeometrySource.roadModel,
      ),
    );
  }

  accept(dwellPositionMeters, 0, speed == null ? null : 0);
  accept(dwellPositionMeters, 3000, speed == null ? null : 0);
  if (dwellUntil > 3000) {
    accept(dwellPositionMeters, dwellUntil, null);
  }
  expect(engine.getProgress().arrivedAtCurrent, isTrue);
  for (var sample = 1; sample <= 15; sample++) {
    accept(sample * 24.0, dwellUntil + sample * 3000, speed);
  }
  expect(engine.getProgress().nextStopKey, 'next');
  expect(engine.getProgress().completed, isFalse);
  return times;
}

bool observedOrigin(GpsJourneyTimes? times) =>
    times?.stopTimes.any((s) => s.stopKey == 'origin' && s.departureObserved) ==
    true;

void main() {
  test(
    'frequent plausible fixes observe origin departure after confirmed physical dwell',
    () {
      final r = route(),
          engine = StationTrackingEngine(route()),
          estimator = GpsJourneyTimeEstimator();
      GpsJourneyTimes? times;
      void accept(double position, int elapsed, double speed) {
        final fix = LocationFix(
          latitude: 0,
          longitude: position / metersPerDegree,
          accuracyMeters: 10,
          timeMillis: base + elapsed,
          speedMetersPerSecond: speed,
        );
        final update = engine.onLocation(fix, fix.timeMillis);
        times = estimator.update(
          route: r,
          progress: engine.getProgress(),
          source: update.source,
          fix: fix,
          nowMillis: fix.timeMillis,
        );
        expect(update.destinationReached, isFalse);
      }

      accept(0, 0, 0);
      accept(0, 3000, 0);
      expect(engine.getProgress().arrivedAtCurrent, isTrue);
      expect(engine.getProgress().gpsEstablished, isTrue);
      expect(engine.getProgress().nextStopKey, 'origin');
      for (var sample = 1; sample <= 15; sample++) {
        accept(sample * 24.0, 3000 + sample * 3000, 8);
      }
      expect(engine.getProgress().nextStopKey, 'next');
      expect(engine.getProgress().gpsEstablished, isTrue);
      expect(engine.getProgress().completed, isFalse);
      expect(times, isNotNull);
      expect(
        times!.stopTimes.any(
          (s) =>
              s.stopKey == 'next' &&
              s.arrivalMillis != null &&
              !s.arrivalObserved,
        ),
        isTrue,
      );
      expect(observedOrigin(times), isTrue);
    },
  );
  test(
    'early physical departure is not blocked by future planned departure',
    () {
      final r = route();
      r[0] = r[0].copyWith(
        plannedDepartureMillis: base + 60000,
        effectiveDepartureMillis: base + 60000,
      );
      final times = journey(r, dwellUntil: 3000, speed: 8)!;
      final origin = times.stopTimes.singleWhere((s) => s.stopKey == 'origin');
      expect(origin.departureObserved, isTrue);
      expect(origin.departureMillis, lessThan(r.first.plannedDepartureMillis!));
    },
  );
  test(
    'unknown speed requires dwell then supports cumulative physical departure',
    () {
      expect(
        observedOrigin(journey(route(), dwellUntil: 8000, speed: null)),
        isTrue,
      );
    },
  );
  test(
    'confirmed dwell just before origin supports subsequent frequent directed fixes',
    () {
      expect(
        observedOrigin(
          journey(
            route(),
            dwellUntil: 3000,
            speed: 8,
            dwellPositionMeters: -10,
          ),
        ),
        isTrue,
      );
    },
  );
  for (final source in [
    GpsGeometrySource.tripPolyline,
    GpsGeometrySource.roadModel,
  ]) {
    test(
      '${source.name} anchor uses only a supported physical origin before projection start',
      () {
        final points = [
          const RoutePoint(0, 0),
          RoutePoint(0, 1000 / metersPerDegree),
          RoutePoint(.001, 1500 / metersPerDegree),
          RoutePoint(0, 2000 / metersPerDegree),
        ];
        final geometry = GpsSegmentGeometry(
          fromKey: 'origin',
          toKey: 'next',
          source: source,
          geometry: RouteGeometry(
            from: points.first,
            to: points.last,
            alternatives: [points],
            fetchedAtMillis: base,
          ),
        );
        expect(
          observedOrigin(
            journey(
              route(),
              dwellUntil: 3000,
              speed: 8,
              dwellPositionMeters: -10,
              segments: [geometry],
            ),
          ),
          isTrue,
        );
      },
    );
  }
  test('GPS outage cannot turn old dwell into an observed departure', () {
    final r = route(),
        engine = StationTrackingEngine(route()),
        estimator = GpsJourneyTimeEstimator();
    GpsJourneyTimes? accept(double meters, int elapsed, double speed) {
      final fix = LocationFix(
        latitude: 0,
        longitude: meters / metersPerDegree,
        accuracyMeters: 10,
        timeMillis: base + elapsed,
        speedMetersPerSecond: speed,
      );
      final update = engine.onLocation(fix, fix.timeMillis);
      expect(update.destinationReached, isFalse);
      return estimator.update(
        route: r,
        progress: engine.getProgress(),
        source: update.source,
        fix: fix,
        nowMillis: fix.timeMillis,
      );
    }

    accept(0, 0, 0);
    accept(0, 3000, 0);
    for (var sample = 0; sample <= 15; sample++) {
      expect(
        observedOrigin(accept(240 + sample * 24.0, 40000 + sample * 3000, 8)),
        isFalse,
      );
    }
  });
  test(
    'rejected jump clears departure anchor and leaves physical cursor at origin',
    () {
      final r = route(),
          engine = StationTrackingEngine(route()),
          estimator = GpsJourneyTimeEstimator();
      GpsJourneyTimes? accept(double meters, int elapsed, double speed) {
        final fix = LocationFix(
          latitude: 0,
          longitude: meters / metersPerDegree,
          accuracyMeters: 10,
          timeMillis: base + elapsed,
          speedMetersPerSecond: speed,
        );
        final update = engine.onLocation(fix, fix.timeMillis);
        expect(update.destinationReached, isFalse);
        return estimator.update(
          route: r,
          progress: engine.getProgress(),
          source: update.source,
          fix: fix,
          nowMillis: fix.timeMillis,
        );
      }

      accept(0, 0, 0);
      accept(0, 3000, 0);
      expect(accept(800, 3100, 8), isNull);
      expect(engine.getProgress().nextStopKey, 'origin');
      for (var sample = 1; sample <= 15; sample++) {
        expect(
          observedOrigin(accept(sample * 24.0, 3000 + sample * 3000, 8)),
          isFalse,
        );
      }
    },
  );
}
