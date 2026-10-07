import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';

import 'package:routely/data/models.dart' as api;
import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/route_enrichment.dart';
import 'package:routely/tracking/tracking_route_geometry.dart';

RoutePoint point(double x, double y) =>
    RoutePoint(y / 111194.92664455874, x / 111194.92664455874);
TransitRouteRequest request(
  List<RoutePoint> points, {
  Set<int> cancelled = const {},
  int statusId = 42,
}) => TransitRouteRequest(
  statusId: statusId,
  tripIdentity: 'trip:identity',
  visits: [
    for (var i = 0; i < points.length; i++)
      TransitRouteVisit(
        key: 'visit-$i',
        stationId: i + 1,
        stationUuid: 'station-$i',
        arrivalPlannedMillis: i == 0 ? null : 1000000 + i * 60000,
        departurePlannedMillis: 1000000 + i * 60000 + 10000,
        point: points[i],
        cancelled: cancelled.contains(i),
      ),
  ],
);
String transitJson(List<RoutePoint> points, {Object statusId = 42}) =>
    jsonEncode({
      'data': {
        'type': 'FeatureCollection',
        'features': [
          {
            'type': 'Feature',
            'properties': {'statusId': statusId},
            'geometry': {
              'type': 'LineString',
              'coordinates': [
                for (final p in points) [p.longitude, p.latitude],
              ],
            },
          },
        ],
      },
    });
Map<String, Object> roadCandidate(
  List<RoutePoint> points, {
  double? declared,
  double? duration,
}) {
  var length = 0.0;
  for (var i = 1; i < points.length; i++) {
    length += RoadRouteParser.distance(points[i - 1], points[i]);
  }
  return {
    'distance': declared ?? length,
    'duration': duration ?? length / 12,
    'geometry': {
      'type': 'LineString',
      'coordinates': [
        for (final p in points) [p.longitude, p.latitude],
      ],
    },
  };
}

String roadJson(
  List<RoutePoint> points, {
  List<Map<String, Object>>? candidates,
  Object snap = 0.0,
}) => jsonEncode({
  'code': 'Ok',
  'routes': candidates ?? [roadCandidate(points)],
  'waypoints': [
    {
      'distance': snap,
      'location': [points.first.longitude, points.first.latitude],
    },
    {
      'distance': 0.0,
      'location': [points.last.longitude, points.last.latitude],
    },
  ],
});
TransitRouteRequest fixtureRequest(String name, int statusId) {
  final rows =
      (jsonDecode(
                File(
                  'test/fixtures/routing/transit/$name-stops.json',
                ).readAsStringSync(),
              )
              as Map)['data']
          as List;
  return TransitRouteRequest(
    statusId: statusId,
    tripIdentity: name,
    visits: rows.map((entry) {
      final s = Map<String, dynamic>.from(entry as Map),
          station = Map<String, dynamic>.from(s['station'] as Map);
      return TransitRouteVisit(
        key: s['uuid'] as String,
        stationId: station['id'] as int,
        stationUuid: station['uuid'] as String,
        arrivalPlannedMillis: parseMillis(s['arrivalPlanned'] as String?),
        departurePlannedMillis: parseMillis(s['departurePlanned'] as String?),
        point: RoutePoint(
          (station['latitude'] as num).toDouble(),
          (station['longitude'] as num).toDouble(),
        ),
        cancelled: s['cancelled'] == true,
      );
    }).toList(),
  );
}

void main() {
  final start = point(0, 0), middle = point(1000, 0), end = point(2000, 0);
  final curved = [start, point(500, 200), middle, point(1500, -250), end];
  final basis = request([start, middle, end]);
  TransitRouteGeometry? parse(
    List<RoutePoint> points, {
    TransitRouteRequest? route,
  }) => TransitRouteParser.parse(transitJson(points), route ?? basis, 1234);
  test(
    'road parser retains lon/lat, bounded alternatives, geometric distance and endpoints',
    () {
      final path = [
        const RoutePoint(51.43, 6.88),
        const RoutePoint(51.445, 6.94),
        const RoutePoint(51.45, 7.01),
      ];
      final parsed = RoadRouteParser.parse(
        roadJson(path),
        path.first,
        path.last,
        1234,
      )!;
      expect(parsed.alternatives.single, path);
      expect(parsed.from, path.first);
      expect(parsed.to, path.last);
      expect(parsed.fetchedAtMillis, 1234);
      expect(
        RoadRouteParser.parse(
          roadJson(
            path,
            candidates: List.generate(5, (_) => roadCandidate(path)),
          ),
          path.first,
          path.last,
          1234,
        )!.alternatives.length,
        3,
      );
      expect(
        RoadRouteParser.parse(
          roadJson(
            path,
            candidates: [
              roadCandidate(path, declared: 999),
              roadCandidate(path),
            ],
          ),
          path.first,
          path.last,
          1234,
        )!.alternatives.length,
        1,
      );
      for (final bad in [
        roadJson(path, snap: 151.0),
        roadJson(path, snap: '0'),
        roadJson(path, candidates: [roadCandidate(path.reversed.toList())]),
        roadJson(path, candidates: [roadCandidate(path, duration: 1)]),
        roadJson(path, candidates: [roadCandidate(path, declared: 100)]),
        roadJson(path).replaceFirst('LineString', 'MultiLineString'),
        '{}',
        'not-json',
      ]) {
        expect(RoadRouteParser.parse(bad, path.first, path.last, 1234), isNull);
      }
    },
  );
  test(
    'published OSRM alternatives share future road despite unequal past distances',
    () {
      final json = File(
        'test/fixtures/routing/muelheim-essen-osrm.json',
      ).readAsStringSync();
      const from = RoutePoint(51.43175246, 6.88538831),
          to = RoutePoint(51.45018831, 7.0101172);
      final parsed = RoadRouteParser.parse(json, from, to, 1000)!;
      expect(parsed.alternatives.map((p) => p.length), [299, 309]);
      final segment = TrackingRouteGeometry.prepare(
        parsed,
        const TrackingStop(
          key: 'a',
          name: 'a',
          latitude: 51.43175246,
          longitude: 6.88538831,
        ),
        const TrackingStop(
          key: 'b',
          name: 'b',
          latitude: 51.45018831,
          longitude: 7.0101172,
        ),
        GpsGeometrySource.roadModel,
        1000,
      )!;
      final vertex = parsed.alternatives.first[205];
      final fix = LocationFix(
        latitude: vertex.latitude,
        longitude: vertex.longitude,
        accuracyMeters: 10,
        timeMillis: 1000,
      );
      final projections = segment.paths
          .map((p) => TrackingRouteGeometry.project(p, fix).projection!)
          .toList();
      expect(
        (projections[0].length - projections[1].length).abs(),
        greaterThan(800),
      );
      expect(TrackingRouteGeometry.sharedRemainingPath(projections), isTrue);
    },
  );
  test('native curved line binds exact ordered public visits', () {
    final result = parse(curved)!;
    expect(result.request, basis);
    expect(result.segments.map((s) => (s.fromKey, s.toKey)), [
      ('visit-0', 'visit-1'),
      ('visit-1', 'visit-2'),
    ]);
    expect(
      result.segments.every((s) => s.source == GpsGeometrySource.tripPolyline),
      isTrue,
    );
    expect(result.segments.first.geometry.from, start);
    expect(result.segments.first.geometry.to, middle);
  });
  for (final fixture in [
    ('public-ice-929', 9258278, 4),
    ('public-tram-7', 9258322, 11),
  ]) {
    test('${fixture.$1} public response passes full ordered curve parser', () {
      final r = fixtureRequest(fixture.$1, fixture.$2);
      final json = File(
        'test/fixtures/routing/transit/${fixture.$1}-polyline.json',
      ).readAsStringSync();
      final parsed = TransitRouteParser.parse(json, r, 1234)!;
      expect(parsed.segments.length, fixture.$3);
      if (fixture.$1.contains('ice')) {
        final lengths = parsed.segments.map((s) {
          final p = s.geometry.alternatives.single;
          var sum = 0.0;
          for (var i = 1; i < p.length; i++) {
            sum += RoadRouteParser.distance(p[i - 1], p[i]);
          }
          return sum;
        }).toList();
        expect(lengths.where((v) => v > 50000).length, greaterThanOrEqualTo(2));
        expect(
          lengths.reduce((a, b) => a + b),
          inInclusiveRange(226000, 227000),
        );
      }
    });
  }
  test(
    'wrong type/status/duplicate features/string coordinates cannot supply native shape',
    () {
      final root = jsonDecode(transitJson(curved)) as Map<String, dynamic>;
      final features = (root['data'] as Map)['features'] as List;
      features.add(features.first);
      for (final json in [
        jsonEncode(root),
        transitJson(curved, statusId: 43),
        transitJson(curved, statusId: '42'),
        transitJson(curved, statusId: 42.0),
        transitJson(curved).replaceAll('LineString', 'MultiLineString'),
        transitJson(curved).replaceFirst('[0.0,0.0]', '["0",0.0]'),
        'not-json',
      ]) {
        expect(TransitRouteParser.parse(json, basis, 1234), isNull);
      }
    },
  );
  test(
    'reversed and missing endpoints or reversed intermediate visits are rejected',
    () {
      expect(parse(curved.reversed.toList()), isNull);
      expect(parse(curved.sublist(1)), isNull);
      expect(
        parse(curved, route: request([start, point(1000, 600), end])),
        isNull,
      );
      final a = point(800, 0), b = point(1600, 0), last = point(2400, 0);
      expect(
        parse([
          start,
          point(400, 200),
          a,
          point(1200, -200),
          b,
          point(2000, 200),
          last,
        ], route: request([start, b, a, last])),
        isNull,
      );
    },
  );
  test(
    'densified station chords and spherical chords never invent curve evidence',
    () {
      expect(
        parse([start, point(500, 0), middle, point(1500, 0), end]),
        isNull,
      );
      const first = RoutePoint(52, 8), last = RoutePoint(52, 10);
      final angle = RoadRouteParser.distance(first, last) / 6371000,
          rad = math.pi / 180;
      final path = List.generate(41, (i) {
        final f = i / 40,
            a = math.sin((1 - i / 40) * angle) / math.sin(angle),
            b = math.sin(f * angle) / math.sin(angle);
        final x =
            a *
                math.cos(first.latitude * rad) *
                math.cos(first.longitude * rad) +
            b * math.cos(last.latitude * rad) * math.cos(last.longitude * rad);
        final y =
            a *
                math.cos(first.latitude * rad) *
                math.sin(first.longitude * rad) +
            b * math.cos(last.latitude * rad) * math.sin(last.longitude * rad);
        final z =
            a * math.sin(first.latitude * rad) +
            b * math.sin(last.latitude * rad);
        return RoutePoint(
          math.atan2(z, math.sqrt(x * x + y * y)) / rad,
          math.atan2(y, x) / rad,
        );
      });
      expect(parse(path, route: request([first, last])), isNull);
    },
  );
  test(
    'each section through cancelled visits needs its own curve evidence',
    () {
      final cancelled = point(1000, 500),
          r = request([start, point(1000, 500), end], cancelled: {1});
      expect(
        parse([
          start,
          point(500, 250),
          cancelled,
          point(1500, 250),
          end,
        ], route: r),
        isNull,
      );
      expect(
        parse([
          start,
          point(500, 500),
          cancelled,
          point(1500, 250),
          end,
        ], route: r),
        isNull,
      );
      final result = parse([
        start,
        point(500, 500),
        cancelled,
        point(1500, 500),
        end,
      ], route: r)!;
      expect(result.segments.single.fromKey, 'visit-0');
      expect(result.segments.single.toKey, 'visit-2');
      expect(
        parse([
          start,
          point(500, 200),
          middle,
          point(1500, 0),
          end,
        ])!.segments.length,
        1,
      );
    },
  );
  test(
    'equally near loops remain ambiguous until global visit order proves assignment',
    () {
      final upper = point(1000, 500), last = point(3000, 0);
      final loop = [
        start,
        middle,
        upper,
        point(1500, 500),
        middle,
        point(2000, -500),
        last,
      ];
      expect(parse(loop, route: request([start, middle, last])), isNull);
      final result = parse(
        loop,
        route: request([start, middle, upper, middle, last]),
      )!;
      expect(
        result.segments.any(
          (s) => s.fromKey == 'visit-2' && s.toKey == 'visit-3',
        ),
        isTrue,
      );
    },
  );
  test(
    'body bytes, point count, long uncovered edge and invalid request stay bounded',
    () {
      expect(
        TransitRouteParser.parse(
          transitJson(curved) + ' ' * TransitRouteParser.maxBodyBytes,
          basis,
          1234,
        ),
        isNull,
      );
      final padded = jsonDecode(transitJson(curved)) as Map<String, dynamic>;
      padded['padding'] = 'ä' * (TransitRouteParser.maxBodyBytes ~/ 2 + 1);
      expect(TransitRouteParser.parse(jsonEncode(padded), basis, 1234), isNull);
      expect(
        parse([...curved, ...List.filled(TransitRouteParser.maxPoints, end)]),
        isNull,
      );
      expect(
        parse([
          start,
          point(500, 200),
          point(20000, 0),
        ], route: request([start, point(20000, 0)])),
        isNull,
      );
      expect(
        parse(curved, route: request([start, middle, end], statusId: 0)),
        isNull,
      );
    },
  );
  test(
    'native selection excludes bus and preserves nullable planned/public identity',
    () {
      final stops = [
        api.Stop.fromJson({
          'uuid': 'a',
          'station': {'id': 1, 'latitude': 0.0, 'longitude': 0.0},
          'departurePlanned': '2026-10-05T18:00:00Z',
        }),
        api.Stop.fromJson({
          'uuid': 'b',
          'station': {'id': 2, 'latitude': 0.0, 'longitude': .02},
          'arrivalPlanned': '2026-10-05T18:02:00Z',
        }),
      ];
      api.Checkin checkin(String mode) => api.Checkin.fromJson({
        'trip': 7,
        'category': 'regional',
        'mode': mode,
        'origin': stops.first.toJson(),
        'destination': stops.last.toJson(),
      });
      expect(
        TransitRouteSelection.request(
          statusId: 42,
          checkin: checkin('bus'),
          fullStops: stops,
          checkedInStops: stops,
          trackingKeys: ['a', 'b'],
        ),
        isNull,
      );
      expect(
        TransitRouteSelection.request(
          statusId: 42,
          checkin: checkin('train'),
          fullStops: stops,
          checkedInStops: stops,
          trackingKeys: ['a', 'b'],
        ),
        isNotNull,
      );
    },
  );
  test(
    'tracking cache rejects stale generation/session and cannot extend hard expiry',
    () {
      final loaded = parse(curved)!, cache = TransitRouteTrackingCache();
      final old = TransitRouteLease(
        statusId: 42,
        generation: 1,
        sessionRevision: 'a',
        request: basis,
      );
      final current = TransitRouteLease(
        statusId: 42,
        generation: 2,
        sessionRevision: 'b',
        request: basis,
      );
      cache.bind(old);
      cache.bind(current);
      expect(cache.adopt(old, loaded, 1234), isFalse);
      expect(cache.adopt(current, loaded, 1234), isTrue);
      expect(cache.segments(1234).length, 2);
      expect(cache.needsRefresh(1234 + 840000), isTrue);
      expect(cache.segments(1234 + 900001), isEmpty);
    },
  );
  test(
    'road singleflight uses transient failure TTL and validates requested endpoints',
    () async {
      var now = 0, wall = 1234, calls = 0;
      final pending = Completer<RoadRouteFetchResult>();
      final limiter = RoadRouteRateLimiter(
        nowMillis: () => now,
        waitMillis: (ms) async {
          now += ms;
        },
      );
      final store = RoadRouteStore(
        fetch: (a, b) {
          calls++;
          return pending.future;
        },
        limiter: limiter,
        nowMillis: () => now,
        nowWallMillis: () => wall,
      );
      final first = store.getRoute(start, end),
          second = store.getRoute(start, end);
      await Future<void>.delayed(Duration.zero);
      expect(calls, 1);
      pending.complete(
        const RoadRouteFetchResult(null, transientFailure: true),
      );
      expect(await first, isNull);
      expect(await second, isNull);
      now = 59999;
      expect(await store.getRoute(start, end), isNull);
      expect(calls, 1);
      now = 60000;
      await store.getRoute(start, end);
      expect(calls, 2);
      wall++;
    },
  );
  test(
    'road limiter serializes starts and provider postponement affects all public pairs',
    () async {
      var now = 0;
      final starts = <int>[],
          limiter = RoadRouteRateLimiter(
            nowMillis: () => now,
            waitMillis: (ms) async {
              now += ms;
            },
          );
      await limiter.awaitTurn();
      starts.add(now);
      limiter.postpone(60000);
      await Future.wait([
        () async {
          await limiter.awaitTurn();
          starts.add(now);
        }(),
        () async {
          await limiter.awaitTurn();
          starts.add(now);
        }(),
      ]);
      expect(starts, [0, 60000, 61000]);
    },
  );
  test(
    'transit failed soft refresh retains original hard expiry and retry TTL',
    () async {
      var now = 0, calls = 0;
      final loaded = parse(curved)!;
      final store = TransitRouteStore(
        nowMillis: () => now,
        fetch: (r) async {
          calls++;
          return TransitRouteFetchResult(calls == 1 ? loaded : null);
        },
      );
      expect(await store.getRoute(basis), same(loaded));
      now = 839999;
      expect(await store.getRoute(basis), same(loaded));
      expect(calls, 1);
      now = 840000;
      expect(await store.getRoute(basis), isNull);
      expect(calls, 2);
      now = 899999;
      expect(await store.getRoute(basis), same(loaded));
      now = 900000;
      expect(await store.getRoute(basis), isNull);
      expect(calls, 2);
      now = 960000;
      expect(await store.getRoute(basis), isNull);
      expect(calls, 3);
    },
  );
  test(
    'closing transit store prevents late geometry reuse and generation writes',
    () async {
      final pending = Completer<TransitRouteFetchResult>(),
          loaded = parse(curved)!;
      final store = TransitRouteStore(fetch: (_) => pending.future);
      final first = store.getRoute(basis), second = store.getRoute(basis);
      store.close();
      pending.complete(TransitRouteFetchResult(loaded));
      expect(await first, isNull);
      expect(await second, isNull);
      expect(await store.getRoute(basis), isNull);
    },
  );
  test(
    'request keys snapshot caller lists and cache rejects another planned basis',
    () async {
      final visits = List<TransitRouteVisit>.of(basis.visits);
      final owned = TransitRouteRequest(
        statusId: 42,
        tripIdentity: 'trip:identity',
        visits: visits,
      );
      visits.clear();
      expect(owned.visits.length, 3);
      final other = request([start, end]);
      final store = TransitRouteStore(
        fetch: (_) async => TransitRouteFetchResult(parse(curved)!),
      );
      expect(await store.getRoute(other), isNull);
    },
  );
}
