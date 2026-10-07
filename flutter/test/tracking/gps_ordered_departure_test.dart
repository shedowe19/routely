import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/station_tracking_engine.dart';
import 'package:routely/tracking/gps_journey_time_estimator.dart';
import 'package:routely/tracking/tracking_route_geometry.dart';
import 'package:routely/tracking/route_enrichment.dart';

const base = 1800000000000, metersPerDegree = 111195.0;
TrackingStop stop(String key, double lat, double lon, {bool origin = false}) =>
    TrackingStop(
      key: key,
      stationId: key.hashCode,
      name: key,
      latitude: lat,
      longitude: lon,
      plannedArrivalMillis: base + 300000,
      effectiveArrivalMillis: base + 300000,
      plannedDepartureMillis: base + 330000,
      effectiveDepartureMillis: base + 330000,
      isOrigin: origin,
    );
List<TrackingStop> closeStopRoute() => [
  stop('origin', 0, 0, origin: true).copyWith(
    plannedArrivalMillis: null,
    effectiveArrivalMillis: null,
    plannedDepartureMillis: base,
    effectiveDepartureMillis: base,
  ),
  stop('next', 0, 180 / metersPerDegree).copyWith(
    plannedArrivalMillis: base + 25000,
    plannedDepartureMillis: base + 55000,
  ),
  stop('destination', 0, 2000 / metersPerDegree).copyWith(isDestination: true),
];
TransitRouteRequest nativeRequest(List<TrackingStop> route) =>
    TransitRouteRequest(
      statusId: 42,
      tripIdentity: 'trip',
      visits: route
          .map(
            (s) => TransitRouteVisit(
              key: s.key,
              stationId: s.stationId,
              arrivalPlannedMillis: s.plannedArrivalMillis,
              departurePlannedMillis: s.plannedDepartureMillis,
              point: RoutePoint(s.latitude!, s.longitude!),
            ),
          )
          .toList(),
    );
String shapeJson(List<RoutePoint> points) => jsonEncode({
  'data': {
    'type': 'FeatureCollection',
    'features': [
      {
        'type': 'Feature',
        'properties': {'statusId': 42},
        'geometry': {
          'type': 'LineString',
          'coordinates': points.map((p) => [p.longitude, p.latitude]).toList(),
        },
      },
    ],
  },
});
TransitRouteGeometry nativeShape(List<TrackingStop> route) =>
    TransitRouteParser.parse(
      shapeJson([
        const RoutePoint(50, 0),
        const RoutePoint(50.005, 0),
        const RoutePoint(50.005, .02),
        const RoutePoint(50, .02),
      ]),
      nativeRequest(route),
      base,
    )!;
GpsSegmentGeometry copyGeometry(
  GpsSegmentGeometry s, {
  List<List<RoutePoint>>? paths,
  int? fetched,
}) => GpsSegmentGeometry(
  fromKey: s.fromKey,
  toKey: s.toKey,
  source: s.source,
  geometry: RouteGeometry(
    from: s.geometry.from,
    to: s.geometry.to,
    alternatives: paths ?? s.geometry.alternatives,
    fetchedAtMillis: fetched ?? s.geometry.fetchedAtMillis,
  ),
);
bool observedDeparture(GpsJourneyTimes? times) =>
    times?.stopTimes.any((s) => s.stopKey == 'origin' && s.departureObserved) ==
    true;

void main() {
  test(
    'frequent 24m pairs cumulatively bootstrap departed origin on parsed native curve',
    () {
      final route = [
            stop('origin', 50, 0, origin: true),
            stop('next', 50, .02),
          ],
          engine = StationTrackingEngine([
            stop('origin', 50, 0, origin: true),
            stop('next', 50, .02),
          ]);
      engine.updateSegmentGeometries(nativeShape(route).segments);
      for (var sample = 0; sample <= 2; sample++) {
        final fix = LocationFix(
          latitude: 50 + (222 + sample * 24) / metersPerDegree,
          longitude: 0,
          accuracyMeters: 10,
          timeMillis: base + sample * 3000,
          speedMetersPerSecond: 8,
        );
        expect(
          engine.onLocation(fix, fix.timeMillis).destinationReached,
          isFalse,
        );
        expect(
          engine.getProgress().nextStopKey,
          sample < 2 ? 'origin' : 'next',
        );
      }
    },
  );
  test(
    'frequent parsed native movement recovers previously approached visit after tunnel gap',
    () {
      final route = [stop('old', 50, 0), stop('next', 50, .02)],
          engine = StationTrackingEngine([
            stop('old', 50, 0),
            stop('next', 50, .02),
          ]);
      engine.updateSegmentGeometries(nativeShape(route).segments);
      for (final observation in [(-350.0, 0), (-250.0, 12500)]) {
        final fix = LocationFix(
          latitude: 50 + observation.$1 / metersPerDegree,
          longitude: 0,
          accuracyMeters: 10,
          timeMillis: base + observation.$2,
          speedMetersPerSecond: 8,
        );
        engine.onLocation(fix, fix.timeMillis);
      }
      for (var sample = 0; sample <= 2; sample++) {
        final fix = LocationFix(
          latitude: 50 + (222 + sample * 24) / metersPerDegree,
          longitude: 0,
          accuracyMeters: 10,
          timeMillis: base + 71500 + sample * 3000,
          speedMetersPerSecond: 8,
        );
        expect(
          engine.onLocation(fix, fix.timeMillis).destinationReached,
          isFalse,
        );
        expect(engine.getProgress().nextStopKey, sample < 2 ? 'old' : 'next');
      }
    },
  );
  test(
    'early close-stop handover preserves pending physical departure observation',
    () {
      final route = closeStopRoute(),
          engine = StationTrackingEngine(closeStopRoute()),
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
        expect(update.destinationReached, isFalse);
        times = estimator.update(
          route: route,
          progress: engine.getProgress(),
          source: update.source,
          fix: fix,
          nowMillis: fix.timeMillis,
        );
      }

      accept(0, 0, 0);
      accept(0, 3000, 0);
      for (var sample = 1; sample <= 7; sample++) {
        accept(sample * 24.0, 3000 + sample * 3000, 8);
      }
      accept(180, 27000, 0);
      accept(180, 30000, 0);
      expect(engine.getProgress().nextStopKey, 'next');
      expect(
        times!.stopTimes
            .singleWhere((s) => s.stopKey == 'next')
            .arrivalObserved,
        isTrue,
      );
      final departure = times!.stopTimes.singleWhere(
        (s) => s.stopKey == 'origin',
      );
      expect(departure.departureObserved, isTrue);
      expect(departure.departureMillis, base + 21000);
    },
  );
  test(
    '90-minute forecast limit preserves supported observed departure on long native section',
    () {
      final origin = stop('origin', 50, 0, origin: true).copyWith(
        plannedArrivalMillis: null,
        effectiveArrivalMillis: null,
        plannedDepartureMillis: base,
        effectiveDepartureMillis: base,
      );
      final next = stop('next', 50, 2.5).copyWith(
        plannedArrivalMillis: base + 6000000,
        effectiveArrivalMillis: base + 6000000,
        plannedDepartureMillis: null,
        effectiveDepartureMillis: null,
        isDestination: true,
      );
      final route = [origin, next],
          points = [
            const RoutePoint(50, 0),
            const RoutePoint(50.02, 0),
            for (var i = 1; i <= 25; i++) RoutePoint(50.02, i / 10),
            const RoutePoint(50, 2.5),
          ];
      final geometry = TransitRouteParser.parse(
        shapeJson(points),
        nativeRequest(route),
        base,
      )!;
      final engine = StationTrackingEngine(route)
            ..updateSegmentGeometries(geometry.segments),
          estimator = GpsJourneyTimeEstimator();
      GpsJourneyTimes? times;
      void accept(double meters, int elapsed, double speed) {
        final fix = LocationFix(
          latitude: 50 + meters / metersPerDegree,
          longitude: 0,
          accuracyMeters: 10,
          timeMillis: base + elapsed,
          speedMetersPerSecond: speed,
        );
        final update = engine.onLocation(fix, fix.timeMillis);
        expect(update.destinationReached, isFalse);
        times = estimator.update(
          route: route,
          progress: engine.getProgress(),
          source: update.source,
          fix: fix,
          nowMillis: fix.timeMillis,
          segmentGeometries: geometry.segments,
        );
      }

      accept(0, 0, 0);
      accept(0, 3000, 0);
      for (var sample = 1; sample <= 15; sample++) {
        accept(sample * 24.0, 3000 + sample * 3000, 8);
      }
      expect(engine.getProgress().nextStopKey, 'next');
      expect(
        estimator.unavailableReason(),
        GpsTimeUnavailableReason.routeUnsupported,
      );
      expect(observedDeparture(times), isTrue);
    },
  );
  test(
    'short curved section retains pending departure after native cursor handover',
    () {
      final route = closeStopRoute();
      const from = RoutePoint(0, 0);
      final middle = RoutePoint(.0002, 90 / metersPerDegree),
          to = RoutePoint(0, 180 / metersPerDegree);
      final segment = GpsSegmentGeometry(
        fromKey: 'origin',
        toKey: 'next',
        source: GpsGeometrySource.tripPolyline,
        geometry: RouteGeometry(
          from: from,
          to: to,
          alternatives: [
            [from, middle, to],
          ],
          fetchedAtMillis: base,
        ),
      );
      final engine = StationTrackingEngine(route)
            ..updateSegmentGeometries([segment]),
          estimator = GpsJourneyTimeEstimator();
      final legLength = TrackingRouteGeometry.distance(from, middle);
      GpsJourneyTimes? times;
      void accept(double chainage, int elapsed, double speed) {
        final firstLeg = chainage <= legLength,
            fraction = chainage <= legLength
                ? chainage / legLength
                : (chainage - legLength) / legLength;
        final start = firstLeg ? from : middle, end = firstLeg ? middle : to;
        final fix = LocationFix(
          latitude: start.latitude + (end.latitude - start.latitude) * fraction,
          longitude:
              start.longitude + (end.longitude - start.longitude) * fraction,
          accuracyMeters: 10,
          timeMillis: base + elapsed,
          speedMetersPerSecond: speed,
        );
        final update = engine.onLocation(fix, fix.timeMillis);
        expect(update.destinationReached, isFalse);
        times = estimator.update(
          route: route,
          progress: engine.getProgress(),
          source: update.source,
          fix: fix,
          nowMillis: fix.timeMillis,
          segmentGeometries: [segment],
        );
      }

      accept(0, 0, 0);
      accept(0, 3000, 0);
      for (var sample = 1; sample <= 7; sample++) {
        accept(sample * 24.0, 3000 + sample * 3000, 8);
      }
      expect(engine.getProgress().nextStopKey, 'next');
      final departure = times!.stopTimes.singleWhere(
        (s) => s.stopKey == 'origin',
      );
      expect(departure.departureObserved, isTrue);
      expect(departure.departureMillis, base + 21000);
    },
  );
  test(
    'missing next planned arrival cannot hide physically supported departure',
    () {
      final origin = stop('origin', 0, 0, origin: true).copyWith(
        plannedArrivalMillis: null,
        effectiveArrivalMillis: null,
        plannedDepartureMillis: base,
        effectiveDepartureMillis: base,
      );
      final next = stop('next', 0, 2000 / metersPerDegree).copyWith(
        plannedArrivalMillis: null,
        effectiveArrivalMillis: null,
        plannedDepartureMillis: base + 300000,
        effectiveDepartureMillis: base + 300000,
      );
      final route = [origin, next],
          engine = StationTrackingEngine([origin, next]),
          estimator = GpsJourneyTimeEstimator();
      GpsJourneyTimes? times;
      void accept(double meters, int elapsed, double speed) {
        final fix = LocationFix(
          latitude: 0,
          longitude: meters / metersPerDegree,
          accuracyMeters: 10,
          timeMillis: base + elapsed,
          speedMetersPerSecond: speed,
        );
        final update = engine.onLocation(fix, fix.timeMillis);
        expect(update.destinationReached, isFalse);
        times = estimator.update(
          route: route,
          progress: engine.getProgress(),
          source: update.source,
          fix: fix,
          nowMillis: fix.timeMillis,
        );
      }

      accept(0, 0, 0);
      accept(0, 3000, 0);
      for (var sample = 1; sample <= 15; sample++) {
        accept(sample * 24.0, 3000 + sample * 3000, 8);
      }
      expect(engine.getProgress().nextStopKey, 'next');
      expect(
        estimator.unavailableReason(),
        GpsTimeUnavailableReason.routeUnsupported,
      );
      expect(observedDeparture(times), isTrue);
    },
  );
  for (final interruption in ['accuracy', 'backwards', 'gap', 'form']) {
    test(
      '$interruption interruption requires new cumulative native movement evidence',
      () {
        final route = [
              stop('origin', 50, 0, origin: true),
              stop('next', 50, .02),
            ],
            engine = StationTrackingEngine([
              stop('origin', 50, 0, origin: true),
              stop('next', 50, .02),
            ]);
        final segments = nativeShape(route).segments;
        engine.updateSegmentGeometries(segments);
        void accept(double meters, int elapsed, {double accuracy = 10}) {
          final fix = LocationFix(
            latitude: 50 + meters / metersPerDegree,
            longitude: 0,
            accuracyMeters: accuracy,
            timeMillis: base + elapsed,
            speedMetersPerSecond: 8,
          );
          expect(
            engine.onLocation(fix, fix.timeMillis).destinationReached,
            isFalse,
          );
        }

        accept(222, 0);
        accept(246, 3000);
        if (interruption == 'accuracy') accept(258, 4500, accuracy: 150);
        if (interruption == 'backwards') accept(230, 6000);
        if (interruption == 'form') {
          engine.updateSegmentGeometries(
            segments
                .map(
                  (s) => copyGeometry(
                    s,
                    paths: s.geometry.alternatives
                        .map(
                          (path) => path
                              .map(
                                (p) => p.latitude == 50.005
                                    ? RoutePoint(50.0051, p.longitude)
                                    : p,
                              )
                              .toList(),
                        )
                        .toList(),
                  ),
                )
                .toList(),
          );
        }
        final start = interruption == 'backwards' ? 254.0 : 270.0,
            elapsed = interruption == 'gap'
                ? 40000
                : interruption == 'backwards'
                ? 9000
                : 6000;
        accept(start, elapsed);
        expect(engine.getProgress().nextStopKey, 'origin');
        accept(start + 24, elapsed + 3000);
        if (interruption != 'backwards') {
          expect(engine.getProgress().nextStopKey, 'origin');
          accept(start + 48, elapsed + 6000);
        }
        expect(engine.getProgress().nextStopKey, 'next');
      },
    );
  }
  test(
    'timestamp-only native refresh preserves cumulative evidence and replay supplies none',
    () {
      final route = [
            stop('origin', 50, 0, origin: true),
            stop('next', 50, .02),
          ],
          engine = StationTrackingEngine([
            stop('origin', 50, 0, origin: true),
            stop('next', 50, .02),
          ]);
      final segments = nativeShape(route).segments;
      engine.updateSegmentGeometries(segments);
      final first = LocationFix(
        latitude: 50 + 222 / metersPerDegree,
        longitude: 0,
        accuracyMeters: 10,
        timeMillis: base,
        speedMetersPerSecond: 8,
      );
      engine.onLocation(first, first.timeMillis);
      final second = first.copyWith(
        latitude: 50 + 246 / metersPerDegree,
        timeMillis: base + 3000,
      );
      engine.onLocation(second, second.timeMillis);
      engine.updateSegmentGeometries(
        segments.map((s) => copyGeometry(s, fetched: base + 3000)).toList(),
      );
      for (var i = 0; i < 4; i++) {
        engine.onLocation(second, base + 4000);
      }
      expect(engine.getProgress().nextStopKey, 'origin');
      final third = second.copyWith(
        latitude: 50 + 270 / metersPerDegree,
        timeMillis: base + 6000,
      );
      engine.onLocation(third, third.timeMillis);
      expect(engine.getProgress().nextStopKey, 'next');
    },
  );
  for (final interruption in ['accuracy', 'gap', 'form', 'backwards']) {
    test(
      '$interruption cannot borrow the previous pending physical exit proof',
      () {
        final route = closeStopRoute(), estimator = GpsJourneyTimeEstimator();
        var geometry = <GpsSegmentGeometry>[];
        GpsJourneyTimes? accept(
          double position,
          int elapsed,
          int index,
          double speed, {
          double accuracy = 10,
        }) {
          final fix = LocationFix(
            latitude: 0,
            longitude: position / metersPerDegree,
            accuracyMeters: accuracy,
            timeMillis: base + elapsed,
            speedMetersPerSecond: speed,
          );
          return estimator.update(
            route: route,
            progress: TrackingProgress(
              nextIndex: index,
              nextStopKey: route[index].key,
              arrivedAtCurrent: index == 0,
              gpsEstablished: true,
            ),
            source: TrackingSource.gps,
            fix: fix,
            nowMillis: fix.timeMillis,
            segmentGeometries: geometry,
          );
        }

        accept(0, 0, 0, 0);
        accept(0, 3000, 0, 0);
        for (var sample = 1; sample <= 4; sample++) {
          accept(sample * 24.0, 3000 + sample * 3000, 0, 8);
        }
        expect(observedDeparture(accept(120, 18000, 1, 8)), isFalse);
        if (interruption == 'accuracy') accept(125, 19000, 1, 8, accuracy: 150);
        if (interruption == 'backwards') accept(96, 19000, 1, 8);
        if (interruption == 'form') {
          geometry = [
            GpsSegmentGeometry(
              fromKey: 'origin',
              toKey: 'next',
              source: GpsGeometrySource.tripPolyline,
              geometry: RouteGeometry(
                from: const RoutePoint(0, 0),
                to: RoutePoint(0, 180 / metersPerDegree),
                fetchedAtMillis: base,
                alternatives: [
                  [
                    const RoutePoint(0, 0),
                    RoutePoint(.0002, 90 / metersPerDegree),
                    RoutePoint(0, 180 / metersPerDegree),
                  ],
                ],
              ),
            ),
          ];
        }
        expect(
          observedDeparture(
            accept(144, interruption == 'gap' ? 50000 : 21000, 1, 8),
          ),
          isFalse,
        );
      },
    );
  }
  test(
    'missing forecast input cannot create departure without observed dwell',
    () {
      final route = closeStopRoute()
          .map(
            (s) => s.key == 'next' ? s.copyWith(plannedArrivalMillis: null) : s,
          )
          .toList();
      final estimator = GpsJourneyTimeEstimator();
      for (var sample = 0; sample <= 7; sample++) {
        final index = sample < 5 ? 0 : 1,
            fix = LocationFix(
              latitude: 0,
              longitude: sample * 24 / metersPerDegree,
              accuracyMeters: 10,
              timeMillis: base + sample * 3000,
              speedMetersPerSecond: 8,
            );
        final times = estimator.update(
          route: route,
          progress: TrackingProgress(
            nextIndex: index,
            nextStopKey: route[index].key,
            gpsEstablished: true,
          ),
          source: TrackingSource.gps,
          fix: fix,
          nowMillis: fix.timeMillis,
        );
        expect(
          times?.stopTimes.any((s) => s.departureObserved) == true,
          isFalse,
        );
      }
    },
  );
  test(
    'SEV departure still requires physical road projection with missing forecast plan',
    () {
      final route = closeStopRoute()
          .map(
            (s) => s.key == 'next' ? s.copyWith(plannedArrivalMillis: null) : s,
          )
          .toList();
      final estimator = GpsJourneyTimeEstimator();
      GpsJourneyTimes? accept(
        double position,
        int elapsed,
        int index,
        double speed,
      ) {
        final fix = LocationFix(
          latitude: 0,
          longitude: position / metersPerDegree,
          accuracyMeters: 10,
          timeMillis: base + elapsed,
          speedMetersPerSecond: speed,
        );
        return estimator.update(
          route: route,
          progress: TrackingProgress(
            nextIndex: index,
            nextStopKey: route[index].key,
            arrivedAtCurrent: index == 0,
            gpsEstablished: true,
          ),
          source: TrackingSource.gps,
          fix: fix,
          nowMillis: fix.timeMillis,
          useRoadGeometry: true,
        );
      }

      accept(0, 0, 0, 0);
      accept(0, 3000, 0, 0);
      for (var sample = 1; sample <= 7; sample++) {
        expect(
          observedDeparture(
            accept(sample * 24.0, 3000 + sample * 3000, sample < 5 ? 0 : 1, 8),
          ),
          isFalse,
        );
      }
      expect(
        estimator.unavailableReason(),
        GpsTimeUnavailableReason.routeGeometryUnavailable,
      );
    },
  );
}
