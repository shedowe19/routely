import 'package:flutter_test/flutter_test.dart';

import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/gps_journey_time_estimator.dart';
import 'package:routely/tracking/tracking_route_geometry.dart';

final base = DateTime.parse('2026-10-05T18:00:00Z').millisecondsSinceEpoch;
TrackingStop stop(
  String key,
  int id,
  double lon,
  int? arrival,
  int? departure, {
  bool origin = false,
  bool destination = false,
}) => TrackingStop(
  key: key,
  stationId: id,
  name: key,
  latitude: 50,
  longitude: lon,
  plannedArrivalMillis: arrival,
  effectiveArrivalMillis: arrival,
  plannedDepartureMillis: departure,
  effectiveDepartureMillis: departure,
  isOrigin: origin,
  isDestination: destination,
);
List<TrackingStop> route() => [
  stop('origin', 1, 0, null, base, origin: true),
  stop('middle', 2, .02, base + 120000, base + 150000),
  stop('destination', 3, .04, base + 270000, base + 300000, destination: true),
];
TrackingProgress progress({
  int index = 1,
  bool arrived = false,
  List<TrackingStop>? stops,
}) => TrackingProgress(
  nextIndex: index,
  nextStopKey: (stops ?? route())[index].key,
  arrivedAtCurrent: arrived,
  gpsEstablished: true,
);
LocationFix fractionFix(
  double fraction, {
  int offset = 0,
  int index = 1,
  List<TrackingStop>? stops,
}) {
  final r = stops ?? route();
  var previous = index - 1;
  while (r[previous].cancelled) {
    previous--;
  }
  final from = r[previous], to = r[index];
  final departure = from.plannedDepartureMillis ?? base,
      arrival = to.plannedArrivalMillis ?? base + 120000;
  return LocationFix(
    latitude: 50,
    longitude:
        (from.longitude ?? 0) +
        ((to.longitude ?? .02) - (from.longitude ?? 0)) * fraction,
    accuracyMeters: 10,
    timeMillis: departure + ((arrival - departure) * fraction).toInt() + offset,
    speedMetersPerSecond: 12,
  );
}

LocationFix stationFix(int index, int time) => LocationFix(
  latitude: 50,
  longitude: route()[index].longitude!,
  accuracyMeters: 10,
  timeMillis: time,
  speedMetersPerSecond: 1,
);
GpsJourneyTimes? update(
  GpsJourneyTimeEstimator estimator,
  LocationFix fix, {
  TrackingProgress? cursor,
  int? now,
  List<TrackingStop>? stops,
  List<GpsSegmentGeometry> shapes = const [],
  bool road = false,
}) => estimator.update(
  route: stops ?? route(),
  progress: cursor ?? progress(),
  source: TrackingSource.gps,
  fix: fix,
  nowMillis: now ?? fix.timeMillis,
  segmentGeometries: shapes,
  useRoadGeometry: road,
);
GpsJourneyTimes? travel(
  GpsJourneyTimeEstimator estimator, {
  int offset = 0,
  List<TrackingStop>? stops,
}) {
  GpsJourneyTimes? result;
  for (final fraction in [.25, .30, .35]) {
    result = update(
      estimator,
      fractionFix(fraction, offset: offset, stops: stops),
      stops: stops,
    );
  }
  return result;
}

GpsSegmentGeometry geometry(List<List<RoutePoint>> paths) => GpsSegmentGeometry(
  fromKey: 'origin',
  toKey: 'middle',
  geometry: RouteGeometry(
    from: const RoutePoint(50, 0),
    to: const RoutePoint(50, .02),
    alternatives: paths,
    fetchedAtMillis: base,
  ),
);
LocationFix pathFix(
  List<RoutePoint> path,
  RoutePoint point, {
  int offset = 60000,
}) {
  var along = 0.0, total = 0.0;
  var found = false;
  for (var i = 1; i < path.length; i++) {
    final edge = TrackingRouteGeometry.distance(path[i - 1], path[i]);
    if (!found &&
        TrackingRouteGeometry.distance(path[i - 1], point) +
                TrackingRouteGeometry.distance(point, path[i]) -
                edge <
            .01) {
      along += TrackingRouteGeometry.distance(path[i - 1], point);
      found = true;
    } else if (!found) {
      along += edge;
    }
    total += edge;
  }
  expect(found, isTrue);
  return LocationFix(
    latitude: point.latitude,
    longitude: point.longitude,
    accuracyMeters: 10,
    timeMillis: base + (120000 * along / total).round() + offset,
    speedMetersPerSecond: 12,
  );
}

void main() {
  test(
    'directed planned interval supports early and late forecasts without API mutation',
    () {
      for (final offset in [-120000, 180000]) {
        final r = route(), e = GpsJourneyTimeEstimator();
        final times = travel(e, offset: offset)!;
        expect(times.stopTimes.first.arrivalMillis, base + 120000 + offset);
        expect(times.stopTimes.last.arrivalMillis, base + 270000 + offset);
        expect(times.stopTimes.first.arrivalObserved, isFalse);
        expect(r[1].effectiveArrivalMillis, base + 120000);
        expect(e.unavailableReason(), isNull);
      }
    },
  );
  test(
    'single fix, pair and three quick fixes cannot establish a forecast',
    () {
      final e = GpsJourneyTimeEstimator();
      for (var i = 0; i < 3; i++) {
        expect(
          update(
            e,
            fractionFix(
              .25 + i * .05,
            ).copyWith(timeMillis: base + 50000 + i * 2000),
          ),
          isNull,
        );
      }
      expect(
        e.unavailableReason(),
        GpsTimeUnavailableReason.insufficientMovement,
      );
    },
  );
  for (final interval in [1000, 200]) {
    test(
      '$interval ms updates preserve a full eight-second movement window',
      () {
        final e = GpsJourneyTimeEstimator();
        for (var tick = 0; tick <= 13000 ~/ interval; tick++) {
          final fix = fractionFix(
            .10 + tick * interval / 50000,
          ).copyWith(timeMillis: base + 20000 + tick * interval);
          expect(update(e, fix), tick * interval < 8000 ? isNull : isNotNull);
        }
      },
    );
  }
  test(
    'duplicates and clock ticks cannot create evidence or extend expiry',
    () {
      final e = GpsJourneyTimeEstimator(), f = fractionFix(.25);
      for (var i = 0; i < 10; i++) {
        expect(update(e, f, now: f.timeMillis + i * 1000), isNull);
      }
      final times = travel(e)!;
      final last = fractionFix(.35);
      expect(update(e, last, now: times.validUntilMillis), same(times));
      expect(update(e, last, now: times.validUntilMillis + 1), isNull);
      expect(e.unavailableReason(), GpsTimeUnavailableReason.noFreshLocation);
    },
  );
  test(
    'accepted accuracy variation retains original expiry during braking',
    () {
      final e = GpsJourneyTimeEstimator();
      final times = travel(e)!;
      final noisy = fractionFix(
        .36,
      ).copyWith(timeMillis: times.updatedAtMillis + 3000, accuracyMeters: 70);
      expect(update(e, noisy)!.validUntilMillis, times.validUntilMillis);
      expect(
        update(
          e,
          fractionFix(.4).copyWith(timeMillis: times.updatedAtMillis + 6000),
        ),
        isNotNull,
      );
    },
  );
  test('stationary mid-section fixes eventually lose unsupported forecast', () {
    final e = GpsJourneyTimeEstimator();
    final initial = travel(e)!;
    var latest = initial;
    for (var seconds = 5; seconds <= 50; seconds += 5) {
      final fix = fractionFix(.35).copyWith(
        timeMillis: initial.updatedAtMillis + seconds * 1000,
        speedMetersPerSecond: 0.0,
      );
      latest = update(e, fix)!;
    }
    expect(
      update(
        e,
        fractionFix(.35).copyWith(
          timeMillis: latest.validUntilMillis + 1,
          speedMetersPerSecond: 0.0,
        ),
      ),
      isNull,
    );
  });
  test('backwards and off-corridor fixes immediately discard forecast', () {
    for (final offRoad in [false, true]) {
      final e = GpsJourneyTimeEstimator();
      final times = travel(e)!;
      final fix = fractionFix(offRoad ? .36 : .30).copyWith(
        timeMillis: times.updatedAtMillis + 6000,
        latitude: offRoad ? 50.002 : 50,
      );
      expect(update(e, fix), isNull);
      expect(
        e.unavailableReason(),
        offRoad
            ? GpsTimeUnavailableReason.outsideCorridor
            : GpsTimeUnavailableReason.unplausibleMovement,
      );
    }
  });
  test(
    'two slow fixes freeze first arrival while late dwell pushes future times',
    () {
      final e = GpsJourneyTimeEstimator();
      update(e, stationFix(1, base + 120000), cursor: progress(arrived: true));
      var times = update(
        e,
        stationFix(1, base + 125000),
        cursor: progress(arrived: true),
      )!;
      for (var s = 10; s <= 70; s += 5) {
        times = update(
          e,
          stationFix(1, base + 120000 + s * 1000),
          cursor: progress(arrived: true),
        )!;
      }
      expect(times.stopTimes.first.arrivalMillis, base + 120000);
      expect(times.stopTimes.first.arrivalObserved, isTrue);
      expect(times.stopTimes.first.departureMillis, base + 190000);
      expect(times.stopTimes.first.departureObserved, isFalse);
      expect(times.stopTimes.last.arrivalMillis, base + 310000);
    },
  );
  test('early arrival never proves early onward departure', () {
    final e = GpsJourneyTimeEstimator();
    update(e, stationFix(1, base), cursor: progress(arrived: true));
    final times = update(
      e,
      stationFix(1, base + 5000),
      cursor: progress(arrived: true),
    )!;
    expect(times.stopTimes.first.arrivalMillis, base);
    expect(times.stopTimes.first.departureMillis, base + 150000);
    expect(times.stopTimes.last.arrivalMillis, base + 270000);
  });
  test('origin waiting may observe arrival but cannot invent an offset', () {
    final r = route();
    r[0] = r[0].copyWith(plannedArrivalMillis: base - 60000);
    final e = GpsJourneyTimeEstimator();
    update(
      e,
      stationFix(0, base - 60000),
      stops: r,
      cursor: progress(index: 0, arrived: true),
    );
    final times = update(
      e,
      stationFix(0, base - 55000),
      stops: r,
      cursor: progress(index: 0, arrived: true),
    )!;
    expect(times.stopTimes.single.arrivalObserved, isTrue);
    expect(times.stopTimes.single.departureMillis, isNull);
    expect(e.unavailableReason(), GpsTimeUnavailableReason.waitingAtOrigin);
  });
  test(
    'unknown speed needs stable dwell and duplicate fix supplies no dwell',
    () {
      final e = GpsJourneyTimeEstimator(),
          first = stationFix(
            1,
            base + 120000,
          ).copyWith(speedMetersPerSecond: null);
      expect(update(e, first, cursor: progress(arrived: true)), isNull);
      expect(
        update(
          e,
          first,
          cursor: progress(arrived: true),
          now: first.timeMillis + 8000,
        ),
        isNull,
      );
      expect(
        update(
          e,
          first.copyWith(timeMillis: first.timeMillis + 5000),
          cursor: progress(arrived: true),
        ),
        isNull,
      );
      final times = update(
        e,
        first.copyWith(timeMillis: first.timeMillis + 8000),
        cursor: progress(arrived: true),
      )!;
      expect(times.stopTimes.first.arrivalMillis, first.timeMillis);
    },
  );
  test(
    'departure after confirmed dwell is observed before any movement forecast',
    () {
      final e = GpsJourneyTimeEstimator();
      update(e, stationFix(0, base), cursor: progress(index: 0, arrived: true));
      expect(
        update(
          e,
          stationFix(0, base + 5000),
          cursor: progress(index: 0, arrived: true),
        ),
        isNull,
      );
      final times = update(
        e,
        fractionFix(.20).copyWith(timeMillis: base + 20000),
      )!;
      expect(times.stopTimes.single.stopKey, 'origin');
      expect(times.stopTimes.single.departureMillis, base + 20000);
      expect(times.stopTimes.single.departureObserved, isTrue);
      expect(
        e.unavailableReason(),
        GpsTimeUnavailableReason.insufficientMovement,
      );
    },
  );
  test(
    'observed arrival survives off-corridor transition without guessed departure',
    () {
      final e = GpsJourneyTimeEstimator();
      update(e, stationFix(1, base + 120000), cursor: progress(arrived: true));
      update(e, stationFix(1, base + 123000), cursor: progress(arrived: true));
      final times = update(
        e,
        fractionFix(
          .10,
          index: 2,
        ).copyWith(latitude: 50.002, timeMillis: base + 130000),
        cursor: progress(index: 2),
      )!;
      expect(times.stopTimes.single.stopKey, 'middle');
      expect(times.stopTimes.single.arrivalObserved, isTrue);
      expect(times.stopTimes.single.departureMillis, isNull);
      expect(e.unavailableReason(), GpsTimeUnavailableReason.outsideCorridor);
    },
  );
  test('handovers merge actual events without renewing forecast TTL', () {
    final e = GpsJourneyTimeEstimator();
    update(e, stationFix(1, base + 120000), cursor: progress(arrived: true));
    final forecast = update(
      e,
      stationFix(1, base + 123000),
      cursor: progress(arrived: true),
    )!;
    final merged = update(
      e,
      fractionFix(.10, index: 2).copyWith(timeMillis: base + 135000),
      cursor: progress(index: 2),
    )!;
    expect(merged.updatedAtMillis, forecast.updatedAtMillis);
    expect(merged.validUntilMillis, forecast.validUntilMillis);
    expect(merged.stopTimes.first.departureObserved, isTrue);
    final braking = fractionFix(
      .1001,
      index: 2,
    ).copyWith(timeMillis: base + 150000, speedMetersPerSecond: 0.0);
    update(e, braking, cursor: progress(index: 2));
    final actual = update(
      e,
      braking,
      cursor: progress(index: 2),
      now: base + 154000,
    )!;
    expect(actual.stopTimes.single.stopKey, 'middle');
    expect(
      actual.stopTimes.single.arrivalObserved &&
          actual.stopTimes.single.departureObserved,
      isTrue,
    );
    expect(actual.updatedAtMillis, base + 150000);
    expect(actual.validUntilMillis, base + 180000);
  });
  test(
    'stale, future, inaccurate, invalid-speed and non-GPS sources clear state',
    () {
      final f = fractionFix(.4);
      final cases = [
        (
          f,
          f.timeMillis + 30001,
          TrackingSource.gps,
          GpsTimeUnavailableReason.noFreshLocation,
        ),
        (
          f,
          f.timeMillis - 1,
          TrackingSource.gps,
          GpsTimeUnavailableReason.noFreshLocation,
        ),
        (
          f.copyWith(accuracyMeters: 76),
          f.timeMillis,
          TrackingSource.gps,
          GpsTimeUnavailableReason.inaccurateLocation,
        ),
        (
          f.copyWith(speedMetersPerSecond: 101.0),
          f.timeMillis,
          TrackingSource.gps,
          GpsTimeUnavailableReason.unplausibleMovement,
        ),
        (
          f,
          f.timeMillis,
          TrackingSource.timetable,
          GpsTimeUnavailableReason.visitUnconfirmed,
        ),
      ];
      for (final c in cases) {
        final e = GpsJourneyTimeEstimator();
        travel(e);
        expect(
          e.update(
            route: route(),
            progress: progress(),
            source: c.$3,
            fix: c.$1,
            nowMillis: c.$2,
          ),
          isNull,
        );
        expect(e.unavailableReason(), c.$4);
      }
    },
  );
  test(
    'location invalidation retains consumed watermark while reset starts new trip',
    () {
      final e = GpsJourneyTimeEstimator();
      travel(e);
      e.invalidateLocation();
      expect(update(e, fractionFix(.35)), isNull);
      expect(update(e, fractionFix(.30)), isNull);
      e.reset();
      expect(travel(e), isNotNull);
    },
  );
  test(
    'API changes preserve ETA but planned markers and coordinates change the basis',
    () {
      final e = GpsJourneyTimeEstimator();
      final forecast = travel(e)!;
      final r = route();
      r[1] = r[1].copyWith(effectiveArrivalMillis: base + 900000);
      expect(update(e, fractionFix(.35), stops: r), same(forecast));
      r[1] = r[1].copyWith(plannedArrivalMillis: base + 130000);
      expect(update(e, fractionFix(.35), stops: r), isNull);
    },
  );
  test(
    'unconfirmed cursor, duplicate visits and backwards timetable reject ETA',
    () {
      final f = fractionFix(.25);
      for (final cursor in [
        progress().copyWith(gpsEstablished: false),
        progress().copyWith(nextIndex: 2),
      ]) {
        final e = GpsJourneyTimeEstimator();
        expect(update(e, f, cursor: cursor), isNull);
        expect(
          e.unavailableReason(),
          GpsTimeUnavailableReason.visitUnconfirmed,
        );
      }
      final r = route();
      r[1] = r[1].copyWith(plannedDepartureMillis: base + 100000);
      final e = GpsJourneyTimeEstimator();
      expect(update(e, f, stops: r), isNull);
      expect(e.unavailableReason(), GpsTimeUnavailableReason.routeUnsupported);
    },
  );
  test(
    'SEV requires validated road geometry while ordinary trips retain chord fallback',
    () {
      final e = GpsJourneyTimeEstimator();
      expect(update(e, fractionFix(.25), road: true), isNull);
      expect(
        e.unavailableReason(),
        GpsTimeUnavailableReason.routeGeometryUnavailable,
      );
      expect(travel(GpsJourneyTimeEstimator()), isNotNull);
    },
  );
  test(
    'curved public path supports planned chainage rather than station chord',
    () {
      final path = [
        const RoutePoint(50, 0),
        const RoutePoint(50.005, 0),
        const RoutePoint(50.005, .02),
        const RoutePoint(50, .02),
      ];
      final e = GpsJourneyTimeEstimator();
      GpsJourneyTimes? result;
      for (final lon in [.004, .006, .008]) {
        result = update(
          e,
          pathFix(path, RoutePoint(50.005, lon)),
          road: true,
          shapes: [
            geometry([path]),
          ],
        );
      }
      expect(result, isNotNull);
      expect(result!.stopTimes.first.arrivalMillis, closeTo(base + 180000, 2));
      expect(e.geometrySource(), GpsGeometrySource.roadModel);
    },
  );
  test('crossing nonadjacent branches cannot select arbitrary chainage', () {
    final path = [
      const RoutePoint(50, 0),
      const RoutePoint(50.004, .02),
      const RoutePoint(50.004, 0),
      const RoutePoint(50, .02),
    ];
    final e = GpsJourneyTimeEstimator();
    final fix = LocationFix(
      latitude: 50.002,
      longitude: .01,
      accuracyMeters: 10,
      timeMillis: base + 60000,
      speedMetersPerSecond: 12,
    );
    expect(
      update(
        e,
        fix,
        road: true,
        shapes: [
          geometry([path]),
        ],
      ),
      isNull,
    );
    expect(e.unavailableReason(), GpsTimeUnavailableReason.ambiguousRoute);
  });
  test(
    'proven road remains stable when unequal historical alternatives rejoin',
    () {
      final north = [
        const RoutePoint(50, 0),
        const RoutePoint(50.005, 0),
        const RoutePoint(50.005, .012),
        const RoutePoint(50, .012),
        const RoutePoint(50, .02),
      ];
      final south = [
        const RoutePoint(50, 0),
        const RoutePoint(49.988, 0),
        const RoutePoint(49.988, .012),
        const RoutePoint(50, .012),
        const RoutePoint(50, .02),
      ];
      final shape = geometry([north, south]), e = GpsJourneyTimeEstimator();
      final points = [
        const RoutePoint(50.005, .004),
        const RoutePoint(50.005, .006),
        const RoutePoint(50.005, .008),
        const RoutePoint(50.005, .010),
        const RoutePoint(50.005, .012),
        const RoutePoint(50.003, .012),
        const RoutePoint(50.001, .012),
        const RoutePoint(50, .012),
        const RoutePoint(50, .014),
        const RoutePoint(50, .016),
      ];
      for (var i = 0; i < points.length; i++) {
        final times = update(
          e,
          pathFix(north, points[i]),
          road: true,
          shapes: [shape],
        );
        if (i >= 2) {
          expect(times, isNotNull);
          expect(e.unavailableReason(), isNull);
        }
      }
      final fresh = GpsJourneyTimeEstimator();
      expect(
        update(
          fresh,
          pathFix(north, const RoutePoint(50, .014)),
          road: true,
          shapes: [shape],
        ),
        isNull,
      );
      expect(
        fresh.unavailableReason(),
        GpsTimeUnavailableReason.insufficientMovement,
      );
    },
  );
  test(
    'snapshot matches exact nullable planned basis and rejects ambiguity',
    () {
      final stop = route()[1];
      final time = GpsStopTime(
        stopKey: stop.key,
        stationId: stop.stationId,
        plannedArrivalMillis: stop.plannedArrivalMillis,
        plannedDepartureMillis: stop.plannedDepartureMillis,
        arrivalMillis: base + 130000,
      );
      final times = GpsJourneyTimes(
        updatedAtMillis: base,
        validUntilMillis: base + 30000,
        stopTimes: [time],
      );
      expect(times.timeFor(stop, base), same(time));
      expect(
        times.timeFor(stop.copyWith(plannedDepartureMillis: null), base),
        isNull,
      );
      expect(times.timeFor(stop.copyWith(key: 'return'), base), isNull);
      expect(times.timeFor(stop, base + 30001), isNull);
      expect(
        GpsJourneyTimes(
          updatedAtMillis: base,
          validUntilMillis: base + 999999,
          stopTimes: [time],
        ).timeFor(stop, base + 30001),
        isNull,
      );
      expect(
        GpsJourneyTimes(
          updatedAtMillis: base,
          validUntilMillis: base + 30000,
          stopTimes: [time, time],
        ).timeFor(stop, base),
        isNull,
      );
    },
  );
}
