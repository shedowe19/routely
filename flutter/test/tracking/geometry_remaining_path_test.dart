import 'package:flutter_test/flutter_test.dart';

import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/tracking_route_geometry.dart';

RoutePoint point(double lat, double lon) => RoutePoint(50 + lat, lon);
TrackingGeometryPath path(List<RoutePoint> points) {
  final lengths = [
    for (var i = 1; i < points.length; i++)
      TrackingRouteGeometry.distance(points[i - 1], points[i]),
  ];
  return TrackingGeometryPath(
    points: points,
    lengths: lengths,
    length: lengths.fold(0, (a, b) => a + b),
  );
}

TrackingGeometryProjection atStart(TrackingGeometryPath p) =>
    TrackingGeometryProjection(fraction: 0, length: p.length, path: p);
void main() {
  test('collinear densification preserves the future ordered road', () {
    final sparse = path([
      point(0, 0),
      point(0, .01),
      point(.005, .01),
      point(.005, .02),
    ]);
    final dense = path([
      point(0, 0),
      point(0, .005),
      point(0, .01),
      point(.002, .01),
      point(.005, .01),
      point(.005, .013),
      point(.005, .019),
      point(.005, .02),
    ]);
    final fix = LocationFix(
      latitude: 50,
      longitude: .006,
      accuracyMeters: 10,
      timeMillis: 1000,
    );
    final projections = [
      sparse,
      dense,
    ].map((p) => TrackingRouteGeometry.project(p, fix).projection!).toList();
    expect(TrackingRouteGeometry.sharedRemainingPath(projections), isTrue);
    expect(
      TrackingRouteGeometry.sharedRemainingPath(projections.reversed.toList()),
      isTrue,
    );
  });
  test('comparison starts inside current edge and ignores past detours', () {
    final common = point(.004, .004), end = point(.004, .02);
    final north = path([point(0, 0), point(.004, 0), common, end]);
    final south = path([
      point(0, 0),
      point(-.006, 0),
      point(-.006, .004),
      common,
      end,
    ]);
    final fix = LocationFix(
      latitude: 50.004,
      longitude: .011,
      accuracyMeters: 10,
      timeMillis: 1000,
    );
    final projections = [
      north,
      south,
    ].map((p) => TrackingRouteGeometry.project(p, fix).projection!).toList();
    expect(TrackingRouteGeometry.sharedRemainingPath(projections), isTrue);
    expect(
      TrackingRouteGeometry.sharedRemainingPath([
        atStart(north),
        atStart(south),
      ]),
      isFalse,
    );
  });
  test(
    'equal remaining length and endpoint cannot prove different future roads',
    () {
      final start = point(0, 0), split = point(0, .005), end = point(0, .015);
      final north = path([start, split, point(.002, .01), end]);
      final south = path([start, split, point(-.002, .01), end]);
      expect(north.length, closeTo(south.length, .1));
      expect(
        TrackingRouteGeometry.sharedRemainingPath([
          atStart(north),
          atStart(south),
        ]),
        isFalse,
      );
    },
  );
  test('same vertices in a different order cannot prove a shared future', () {
    final start = point(0, 0),
        north = point(.002, .004),
        south = point(-.002, .006),
        end = point(0, .01);
    expect(
      TrackingRouteGeometry.sharedRemainingPath([
        atStart(path([start, north, south, end])),
        atStart(path([start, south, north, end])),
      ]),
      isFalse,
    );
  });
  test('future loops and near-parallel roads remain different', () {
    final start = point(0, 0),
        end = point(0, .02),
        direct = path([point(0, 0), point(0, .02)]);
    for (final other in [
      path([start, point(.003, 0), point(.003, .005), start, end]),
      path([start, point(.00003, .004), point(.00003, .016), end]),
    ]) {
      expect(
        TrackingRouteGeometry.sharedRemainingPath([
          atStart(direct),
          atStart(other),
        ]),
        isFalse,
      );
    }
  });
  test('every pair must agree rather than only the first reference', () {
    final direct = path([point(0, 0), point(0, .02)]);
    final north = path([point(.000008, 0), point(.000008, .02)]);
    final south = path([point(-.000008, 0), point(-.000008, .02)]);
    expect(
      TrackingRouteGeometry.sharedRemainingPath([
        atStart(direct),
        atStart(north),
      ]),
      isTrue,
    );
    expect(
      TrackingRouteGeometry.sharedRemainingPath([
        atStart(direct),
        atStart(south),
      ]),
      isTrue,
    );
    expect(
      TrackingRouteGeometry.sharedRemainingPath([
        atStart(direct),
        atStart(north),
        atStart(south),
      ]),
      isFalse,
    );
  });
  test('endpoint has no remaining shape to prove', () {
    final p = path([point(0, 0), point(0, .02)]);
    final endpoint = TrackingGeometryProjection(
      fraction: 1,
      length: p.length,
      path: p,
    );
    expect(
      TrackingRouteGeometry.sharedRemainingPath([endpoint, endpoint]),
      isFalse,
    );
  });
  test(
    'nearest unsupported final extrapolation cannot fall back to inner vertex',
    () {
      final p = path([point(0, 0), point(0, .0199), point(0, .02)]);
      final fix = LocationFix(
        latitude: 50,
        longitude: .0204,
        accuracyMeters: 10,
        timeMillis: 1000,
      );
      expect(TrackingRouteGeometry.project(p, fix).projection, isNull);
      expect(
        TrackingRouteGeometry.project(p, fix, arrivalEndpoint: true).projection,
        isNotNull,
      );
    },
  );
  test(
    'shape preparation binds physical endpoints and source-specific expiry',
    () {
      final from = TrackingStop(
        key: 'a',
        name: 'a',
        latitude: 50,
        longitude: 0,
      );
      final to = TrackingStop(
        key: 'b',
        name: 'b',
        latitude: 50,
        longitude: .02,
      );
      final geometry = RouteGeometry(
        from: point(0, 0),
        to: point(0, .02),
        alternatives: [
          [point(0, 0), point(.002, .01), point(0, .02)],
        ],
        fetchedAtMillis: 1000,
      );
      expect(
        TrackingRouteGeometry.prepare(
          geometry,
          from,
          to,
          GpsGeometrySource.tripPolyline,
          901001,
        ),
        isNull,
      );
      expect(
        TrackingRouteGeometry.prepare(
          geometry,
          from,
          to,
          GpsGeometrySource.roadModel,
          901001,
        ),
        isNotNull,
      );
      expect(
        TrackingRouteGeometry.prepare(
          geometry,
          from,
          to.copyWith(longitude: .021),
          GpsGeometrySource.roadModel,
          1000,
        ),
        isNull,
      );
    },
  );
}
