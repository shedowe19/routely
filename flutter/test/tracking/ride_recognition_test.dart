import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/ride_recognition_engine.dart';

const now = 1800000000000;
TrackingStop stop(
  String key,
  double x, {
  int? departure,
  int? arrival,
  bool cancelled = false,
}) => TrackingStop(
  key: key,
  stationId: key == 'origin' ? 1 : 2,
  name: key == 'origin' ? 'Start' : 'Ziel',
  latitude: 52,
  longitude: 13 + x / (111320 * math.cos(52 * math.pi / 180)),
  plannedDepartureMillis: departure,
  effectiveDepartureMillis: departure,
  effectiveArrivalMillis: arrival,
  cancelled: cancelled,
);
RecognizableRide ride({
  String line = 'A',
  int departure = now - 20000,
  int? real,
  bool cancelled = false,
  bool originCancelled = false,
}) => RecognizableRide(
  tripId: 'trip-$line',
  lineName: line,
  stops: [
    stop('origin', 0, departure: departure, cancelled: originCancelled),
    stop('target', 2000, arrival: now + 180000),
  ],
  originIndex: 0,
  fetchedAtMillis: now - 20000,
  departurePlannedMillis: departure,
  departureRealMillis: real,
  cancelled: cancelled,
);
LocationFix fix(double x, int time, {double north = 0, double accuracy = 10}) =>
    LocationFix(
      latitude: 52 + north / 111320,
      longitude: 13 + x / (111320 * math.cos(52 * math.pi / 180)),
      accuracyMeters: accuracy,
      timeMillis: time,
    );
RideRecognitionEngine prepared([List<RecognizableRide>? rides]) =>
    RideRecognitionEngine()..updateRides(rides ?? [ride()], now - 20000);
List<RecognizedRide> movement(RideRecognitionEngine engine) {
  engine.onLocation(fix(0, now - 20000), now - 20000);
  engine.onLocation(fix(120, now - 10000), now - 10000);
  return engine.onLocation(fix(240, now), now);
}

void main() {
  test(
    'fresh directed movement from exact boarding visit produces suggestion',
    () {
      final candidates = movement(prepared());
      expect(candidates.length, 1);
      expect(candidates.single.ride.origin?.key, 'origin');
      expect(candidates.single.nextStationName, 'Ziel');
    },
  );
  test('indistinguishable services remain separate suggestions', () {
    expect(
      movement(
        prepared([ride(line: 'A'), ride(line: 'B')]),
      ).map((r) => r.ride.lineName).toSet(),
      {'A', 'B'},
    );
  });
  test(
    'walking,stationary,jitter,opposite and lateral movement do not match',
    () {
      for (final coordinates in [
        [(0.0, 0.0), (8.0, 0.0), (-5.0, 0.0)],
        [(0.0, 0.0), (-120.0, 0.0), (-240.0, 0.0)],
        [(0.0, 0.0), (0.0, 200.0), (0.0, 400.0)],
        [(0.0, 0.0), (8.0, 0.0), (16.0, 0.0)],
      ]) {
        final engine = prepared();
        List<RecognizedRide> candidates = [];
        for (var i = 0; i < coordinates.length; i++) {
          final time = now - 20000 + i * 10000;
          candidates = engine.onLocation(
            fix(coordinates[i].$1, time, north: coordinates[i].$2),
            time,
          );
        }
        expect(candidates, isEmpty);
      }
    },
  );
  test(
    'without observed origin, cancelled service or late plan no candidate',
    () {
      final engine = prepared();
      for (final (x, t) in [
        (800.0, now - 20000),
        (1000.0, now - 10000),
        (1200.0, now),
      ]) {
        expect(engine.onLocation(fix(x, t), t), isEmpty);
      }
      expect(movement(prepared([ride(cancelled: true)])), isEmpty);
      expect(movement(prepared([ride(originCancelled: true)])), isEmpty);
      expect(movement(prepared([ride(departure: now + 1200000)])), isEmpty);
    },
  );
  test('fresh departure realtime supersedes route plan', () {
    expect(
      movement(
        prepared([ride(departure: now - 1800000, real: now - 20000)]),
      ).length,
      1,
    );
  });
  test(
    'new inaccurate fix hides candidates and older precise fix cannot undo it',
    () {
      final engine = prepared();
      expect(movement(engine).length, 1);
      expect(
        engine.onLocation(fix(360, now + 10000, accuracy: 100), now + 10000),
        isEmpty,
      );
      expect(engine.onLocation(fix(300, now + 5000), now + 10000), isEmpty);
      expect(engine.matches(now + 10000), isEmpty);
      expect(engine.onLocation(fix(400, now + 20000), now + 20000).length, 1);
    },
  );
  test(
    'a short-pair GPS jump is rejected despite plausible overall average',
    () {
      final engine = prepared();
      engine.onLocation(fix(0, now - 20000), now - 20000);
      engine.onLocation(fix(0, now - 1000), now - 1000);
      expect(engine.onLocation(fix(1000, now), now), isEmpty);
    },
  );
  test('GPS gap erases old boarding history and stale routes expire', () {
    final engine = prepared();
    movement(engine);
    expect(engine.onLocation(fix(500, now + 31000), now + 31000), isEmpty);
    engine.matches(now + 301000);
    expect(engine.storedRouteCount, 0);
  });
  test(
    'origin resolution requires matching concrete planned time on loops',
    () {
      final visits = [
        stop('origin', 0, departure: now),
        stop('target', 1000),
        stop('origin', 0, departure: now + 120000),
      ];
      expect(
        RideRecognitionEngine.resolveOriginIndex(visits, stationId: 1),
        -1,
      );
      expect(
        RideRecognitionEngine.resolveOriginIndex(
          visits,
          stationId: 1,
          plannedDepartureMillis: now + 120000,
        ),
        2,
      );
    },
  );
}
