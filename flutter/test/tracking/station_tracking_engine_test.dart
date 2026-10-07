import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/station_tracking_engine.dart';
import 'package:routely/tracking/tracking_location_clock.dart';

const now = 1791187200000, minute = 60000;
TrackingStop stop(
  String key, {
  double x = 0,
  double y = 0,
  int id = 1,
  int arrival = now + 10 * minute,
  bool origin = false,
  bool destination = false,
  bool cancelled = false,
}) => TrackingStop(
  key: key,
  stationId: id,
  name: key,
  latitude: y / 111195,
  longitude: x / 111195,
  plannedArrivalMillis: arrival,
  effectiveArrivalMillis: arrival,
  effectiveDepartureMillis: arrival + minute,
  isOrigin: origin,
  isDestination: destination,
  cancelled: cancelled,
);
LocationFix fix(
  double x,
  int time, {
  double y = 0,
  double accuracy = 10,
  double? speed = 0,
}) => LocationFix(
  latitude: y / 111195,
  longitude: x / 111195,
  accuracyMeters: accuracy,
  timeMillis: time,
  speedMetersPerSecond: speed,
);
List<TrackingStop> tunnelStops() => [
  stop('origin', origin: true),
  stop('bismarck', x: 2000, id: 2),
  stop('missed', x: 4000, y: 2000, id: 3),
  stop('savigny', x: 6000, y: 2000, id: 4),
  stop('destination', x: 8000, y: 2000, id: 5, destination: true),
];
StationTrackingEngine tunnelEngine([List<TrackingStop>? route]) =>
    StationTrackingEngine(
      route ?? tunnelStops(),
      progress: const TrackingProgress(
        nextIndex: 1,
        nextStopKey: 'bismarck',
        announcedKeys: {'origin'},
        gpsEstablished: true,
      ),
    );
void main() {
  test('delayed destination remains selected after its timetable', () {
    final engine = StationTrackingEngine([
      stop('destination', arrival: now - 30 * minute, destination: true),
    ]);
    engine.onLocation(fix(-1000, now), now);
    final update = engine.onLocation(fix(-250, now + 8000), now + 8000);
    expect(update.source, TrackingSource.gps);
    expect(update.announcement?.key, 'destination');
    expect(update.destinationReached, isFalse);
  });
  test(
    'approach announces but only inner accuracy circle confirms arrival',
    () {
      final engine = StationTrackingEngine([stop('next')]);
      engine.onLocation(fix(-1000, now), now);
      expect(
        engine.onLocation(fix(-250, now + 8000), now + 8000).announcement?.key,
        'next',
      );
      engine.onLocation(fix(-180, now + 9000), now + 9000);
      expect(engine.getProgress().arrivedAtCurrent, isFalse);
      engine.onLocation(fix(-80, now + 10000), now + 10000);
      expect(engine.getProgress().arrivedAtCurrent, isTrue);
    },
  );
  test(
    'first near destination fix never completes and replay cannot add arrival',
    () {
      final engine = StationTrackingEngine([
        stop('destination', destination: true),
      ]);
      final first = engine.onLocation(fix(-80, now), now);
      expect(first.destinationReached, isFalse);
      expect(
        engine.onLocation(fix(-80, now), now + 10000).destinationReached,
        isFalse,
      );
      expect(
        engine
            .onLocation(fix(-80, now + 11000), now + 11000)
            .destinationReached,
        isTrue,
      );
    },
  );
  test('destination completes after two fresh slow inner observations', () {
    final engine = StationTrackingEngine([
      stop('destination', destination: true),
    ]);
    engine.onLocation(fix(-1000, now), now);
    engine.onLocation(fix(-250, now + 8000), now + 8000);
    expect(
      engine.onLocation(fix(-80, now + 10000), now + 10000).destinationReached,
      isTrue,
    );
  });
  test('fast destination pass stays active and clock cannot complete', () {
    final engine = StationTrackingEngine([
      stop('destination', destination: true),
    ]);
    for (final (x, t) in [
      (-1000.0, 0),
      (-250.0, 8000),
      (-80.0, 10000),
      (200.0, 13000),
    ]) {
      expect(
        engine
            .onLocation(fix(x, now + t, speed: 25), now + t)
            .destinationReached,
        isFalse,
      );
    }
    expect(engine.onTimetable(now + 30 * minute).destinationReached, isFalse);
  });
  test(
    'unavailable speed requires stationary ten second dwell at destination',
    () {
      final engine = StationTrackingEngine([
        stop('destination', destination: true),
      ]);
      engine.onLocation(fix(0, now, speed: null), now);
      expect(
        engine
            .onLocation(fix(0, now + 9000, speed: null), now + 9000)
            .destinationReached,
        isFalse,
      );
      expect(
        engine
            .onLocation(fix(0, now + 10000, speed: null), now + 10000)
            .destinationReached,
        isTrue,
      );
    },
  );
  for (final accuracy in [250.0, double.nan, double.infinity]) {
    test('unusable accuracy $accuracy is not arrival proof', () {
      final engine = StationTrackingEngine([
        stop('next', arrival: now + 2 * minute),
      ]);
      final update = engine.onLocation(fix(0, now, accuracy: accuracy), now);
      expect(update.source, TrackingSource.timetable);
      expect(engine.getProgress().arrivedAtCurrent, isFalse);
    });
  }
  test('stale fix and invalid coordinates cannot establish GPS', () {
    final engine = StationTrackingEngine([stop('next')]);
    engine.onLocation(fix(0, now - 31000), now);
    expect(engine.hasReliableLocation(now), isFalse);
    engine.onLocation(
      const LocationFix(
        latitude: 91,
        longitude: 0,
        accuracyMeters: 10,
        timeMillis: now,
      ),
      now,
    );
    expect(engine.hasReliableLocation(now), isFalse);
  });
  test('one announcement persists over update, outage and JSON restart', () {
    final stops = [stop('next', arrival: now + 2 * minute)],
        engine = StationTrackingEngine([
          stop('next', arrival: now + 2 * minute),
        ]);
    engine.onLocation(fix(-1000, now), now);
    expect(
      engine.onLocation(fix(-250, now + 8000), now + 8000).announcement,
      isNotNull,
    );
    expect(engine.onTimetable(now + 40000).announcement, isNull);
    final progress = TrackingProgress.fromJson(
      jsonDecode(jsonEncode(engine.getProgress().toJson()))
          as Map<String, dynamic>,
    );
    final restarted = StationTrackingEngine(stops, progress: progress);
    restarted.onLocation(fix(-400, now + 41000), now + 41000);
    expect(
      restarted.onLocation(fix(-250, now + 43000), now + 43000).announcement,
      isNull,
    );
  });
  test(
    'failed speech is released for retry, acknowledgement prevents duplicate',
    () {
      final engine = StationTrackingEngine([stop('next')]);
      engine.onLocation(fix(-1000, now), now);
      engine.onLocation(fix(-250, now + 8000), now + 8000);
      engine.releaseAnnouncement('next');
      expect(
        engine.onLocation(fix(-200, now + 9000), now + 9000).announcement?.key,
        'next',
      );
      engine.acknowledgeAnnouncement('next');
      expect(
        engine.onLocation(fix(-150, now + 10000), now + 10000).announcement,
        isNull,
      );
    },
  );
  test(
    'GPS outage protects established visit; explicit clock mode can advance',
    () {
      final stops = [
        stop('origin', origin: true, arrival: now - minute),
        stop('next', x: 2000, id: 2, arrival: now + minute),
      ];
      final engine = StationTrackingEngine(stops);
      engine.onLocation(fix(0, now), now);
      engine.invalidateLocation();
      expect(engine.onTimetable(now + 5 * minute).stop?.key, 'origin');
      engine.setGpsEnabled(false);
      expect(engine.onTimetable(now + minute).stop?.key, 'next');
    },
  );
  test(
    'provisional clock cursor can reanchor after far first GPS and restoration',
    () {
      final stops = [
        stop('origin', origin: true, arrival: now - 20 * minute),
        stop('next', x: 2000, id: 2, arrival: now - 10 * minute),
        stop(
          'destination',
          x: 4000,
          id: 3,
          arrival: now - 5 * minute,
          destination: true,
        ),
      ];
      final clock = StationTrackingEngine(stops);
      clock.onTimetable(now);
      expect(clock.getProgress().nextStopKey, 'destination');
      final restarted = StationTrackingEngine(
        stops,
        progress: clock.getProgress(),
      );
      restarted.onLocation(fix(10000, now), now);
      expect(restarted.getProgress().gpsEstablished, isFalse);
      restarted.onLocation(fix(0, now + 120000), now + 120000);
      expect(restarted.getProgress().nextStopKey, 'origin');
    },
  );
  test(
    'route refresh retains exact visit, insertion and coordinate changes clear arrival only',
    () {
      final engine = StationTrackingEngine(
        [stop('a'), stop('b', x: 2000, id: 2)],
        progress: const TrackingProgress(
          nextIndex: 1,
          nextStopKey: 'b',
          arrivedAtCurrent: true,
          gpsEstablished: true,
          announcedKeys: {'b'},
        ),
      );
      engine.updateRoute([
        stop('inserted', x: -1000),
        stop('a'),
        stop('b', x: 2000, id: 2),
      ]);
      expect(engine.getProgress().nextIndex, 2);
      expect(engine.getProgress().arrivedAtCurrent, isTrue);
      engine.updateRoute([
        stop('inserted', x: -1000),
        stop('a'),
        stop('b', x: 2200, id: 2),
      ]);
      expect(engine.getProgress().nextStopKey, 'b');
      expect(engine.getProgress().arrivedAtCurrent, isFalse);
      expect(engine.getProgress().announcedKeys, {'b'});
    },
  );
  test(
    'removed visit selects next surviving visit, never another station-id occurrence',
    () {
      final engine = StationTrackingEngine(
        [stop('a'), stop('a-return'), stop('b', id: 2)],
        progress: const TrackingProgress(
          nextIndex: 1,
          nextStopKey: 'a-return',
          gpsEstablished: true,
        ),
      );
      engine.updateRoute([stop('a'), stop('b', id: 2)]);
      expect(engine.getProgress().nextStopKey, 'b');
    },
  );
  test('cancelled visits skip and never produce speech', () {
    final engine = StationTrackingEngine([
      stop('cancelled', cancelled: true),
      stop('next', id: 2),
    ]);
    expect(engine.getProgress().nextStopKey, 'next');
    expect(engine.getProgress().announcedKeys, isEmpty);
  });
  test('first GPS beyond origin needs coherent directed departure', () {
    final engine = StationTrackingEngine([
      stop('origin', origin: true),
      stop('next', x: 2000, id: 2),
    ]);
    engine.onLocation(fix(500, now), now);
    expect(engine.getProgress().nextIndex, 0);
    engine.onLocation(fix(800, now + 4000), now + 4000);
    expect(engine.getProgress().nextStopKey, 'next');
  });
  test('backward movement cannot bootstrap origin', () {
    final engine = StationTrackingEngine([
      stop('origin', origin: true),
      stop('next', x: 2000, id: 2),
    ]);
    engine.onLocation(fix(800, now), now);
    engine.onLocation(fix(500, now + 4000), now + 4000);
    expect(engine.getProgress().nextIndex, 0);
  });
  test('GPS jump cannot become departure proof or successor reference', () {
    final engine = StationTrackingEngine([
      stop('origin', origin: true),
      stop('next', x: 2000, id: 2),
    ]);
    engine.onLocation(fix(0, now), now);
    engine.onLocation(fix(1500, now + 1000), now + 1000);
    expect(engine.getProgress().nextIndex, 0);
    engine.onLocation(fix(0, now + 2000), now + 2000);
    expect(engine.getProgress().nextIndex, 0);
  });
  test('short bus interval advances and announces successor in same fix', () {
    final engine = StationTrackingEngine([
      stop('origin', origin: true),
      stop('rathaus', x: 180, id: 2),
    ]);
    engine.onLocation(fix(0, now), now);
    engine.onLocation(fix(0, now + 3000), now + 3000);
    final update = engine.onLocation(
      fix(130, now + 6000, speed: 10),
      now + 6000,
    );
    expect(update.stop?.key, 'rathaus');
    expect(update.announcement?.key, 'rathaus');
  });
  test(
    'three fresh unique fixes reacquire a later tunnel visit without arrival',
    () {
      final engine = tunnelEngine();
      engine.onLocation(fix(6000, now, y: 2000), now);
      expect(engine.isReacquiringLocation(), isTrue);
      expect(engine.onTimetable(now + 2000).announcement, isNull);
      engine.onLocation(fix(6000, now + 3000, y: 2000), now + 3000);
      engine.onLocation(fix(6000, now + 6000, y: 2000), now + 6000);
      expect(engine.getProgress().nextStopKey, 'savigny');
      expect(engine.getProgress().arrivedAtCurrent, isFalse);
      engine.onLocation(fix(6000, now + 9000, y: 2000), now + 9000);
      expect(engine.getProgress().arrivedAtCurrent, isTrue);
    },
  );
  test('replayed fix neither counts nor erases reacquisition evidence', () {
    final engine = tunnelEngine();
    final first = fix(6000, now, y: 2000);
    engine.onLocation(first, now);
    engine.onLocation(first, now + 3000);
    engine.onLocation(fix(6000, now + 3000, y: 2000), now + 3000);
    expect(engine.getProgress().nextStopKey, 'bismarck');
    engine.onLocation(fix(6000, now + 6000, y: 2000), now + 6000);
    expect(engine.getProgress().nextStopKey, 'savigny');
  });
  for (final accuracy in [150.0, double.nan, double.infinity]) {
    test('inaccurate new sample breaks future proof $accuracy', () {
      final engine = tunnelEngine();
      engine.onLocation(fix(6000, now, y: 2000), now);
      engine.onLocation(
        fix(6000, now + 3000, y: 2000, accuracy: accuracy),
        now + 3000,
      );
      engine.onLocation(fix(6000, now + 6000, y: 2000), now + 6000);
      engine.onLocation(fix(6000, now + 9000, y: 2000), now + 9000);
      expect(engine.getProgress().nextStopKey, 'bismarck');
      engine.onLocation(fix(6000, now + 12000, y: 2000), now + 12000);
      expect(engine.getProgress().nextStopKey, 'savigny');
    });
  }
  test(
    'past physical loop and duplicate station identity block later recovery',
    () {
      for (final route in [
        [...tunnelStops(), stop('savigny-return', x: 6000, y: 2000, id: 6)],
        tunnelStops()
            .map((s) => s.key == 'savigny' ? s.copyWith(stationId: 2) : s)
            .toList(),
      ]) {
        final engine = tunnelEngine(route);
        for (final t in [0, 3000, 6000, 9000]) {
          engine.onLocation(fix(6000, now + t, y: 2000), now + t);
        }
        expect(engine.getProgress().nextStopKey, 'bismarck');
      }
    },
  );
  test('reacquired destination needs independent subsequent arrival', () {
    final engine = tunnelEngine();
    for (final t in [0, 3000, 6000]) {
      expect(
        engine
            .onLocation(fix(8000, now + t, y: 2000), now + t)
            .destinationReached,
        isFalse,
      );
    }
    expect(engine.getProgress().nextStopKey, 'destination');
    expect(engine.getProgress().arrivedAtCurrent, isFalse);
    expect(
      engine
          .onLocation(fix(8000, now + 9000, y: 2000), now + 9000)
          .destinationReached,
      isTrue,
    );
  });
  for (final speed in [double.nan, double.infinity, -1.0]) {
    test(
      'invalid reported speed is distinct from absent speed at origin $speed',
      () {
        final engine = StationTrackingEngine([
          stop('origin', origin: true, arrival: now - minute),
        ]);
        for (final t in [0, 3000, 10000, 11000]) {
          expect(
            engine
                .onLocation(fix(0, now + t, speed: speed), now + t)
                .announcement,
            isNull,
          );
        }
      },
    );
  }
  test(
    'valid waiting origin advice is invalidated by movement and departure time',
    () {
      final origin = stop('origin', origin: true, arrival: now),
          engine = StationTrackingEngine([
            stop('origin', origin: true, arrival: now),
            stop('next', x: 2000, id: 2),
          ]);
      engine.onLocation(fix(0, now), now);
      expect(
        engine.onLocation(fix(0, now + 3000), now + 3000).announcement?.key,
        'origin',
      );
      expect(
        engine.isOriginAnnouncementRelevant(
          origin.key,
          TrackingSource.gps,
          now + 4000,
        ),
        isTrue,
      );
      engine.onLocation(fix(50, now + 6000, speed: 10), now + 6000);
      expect(
        engine.isOriginAnnouncementRelevant(
          origin.key,
          TrackingSource.gps,
          now + 6000,
        ),
        isFalse,
      );
      expect(
        engine.isOriginAnnouncementRelevant(
          origin.key,
          TrackingSource.gps,
          now + minute + 1,
        ),
        isFalse,
      );
    },
  );
  test(
    'monotonic adapter retains event time, ordering and watermark through wall clock changes',
    () {
      final clock = TrackingLocationClock();
      TrackingLocationObservation sample(
        int mono,
        int current,
        int wall, {
        double speed = 0,
        bool hasSpeed = true,
      }) => clock.observe(
        latitude: 0,
        longitude: 0,
        accuracyMeters: 10,
        monotonicNanos: mono,
        nowMonotonicNanos: current,
        nowMillis: wall,
        hasSpeed: hasSpeed,
        speedMetersPerSecond: speed,
      );
      final first = sample(10000000000, 11000000000, now);
      expect(first.isNew, isTrue);
      expect(first.fix!.timeMillis, now - 1000);
      final replay = sample(10000000000, 12000000000, now + 1000);
      expect(replay.isNew, isFalse);
      expect(replay.fix!.timeMillis, now - 1000);
      final jumped = sample(10000000000, 13000000000, now + 60000);
      expect(jumped.clockChanged, isTrue);
      expect(jumped.isNew, isFalse);
      expect(jumped.fix, isNull);
      expect(sample(9000000000, 13000000000, now + 60000).isNew, isFalse);
      expect(sample(14000000000, 14000000000, now + 61000).isNew, isTrue);
    },
  );
  test(
    'monotonic adapter rejects future and old observations without freezing next valid fix',
    () {
      final clock = TrackingLocationClock();
      TrackingLocationObservation sample(int mono) => clock.observe(
        latitude: 0,
        longitude: 0,
        accuracyMeters: 10,
        monotonicNanos: mono,
        nowMonotonicNanos: 50000000000,
        nowMillis: now,
        hasSpeed: false,
        speedMetersPerSecond: 0,
      );
      expect(sample(51000000000).isNew, isFalse);
      expect(sample(1000000000).isNew, isFalse);
      final valid = sample(50000000000);
      expect(valid.isNew, isTrue);
      expect(valid.fix!.speedMetersPerSecond, isNull);
    },
  );
}
