import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:routely/data/app_store.dart';
import 'package:routely/data/models.dart';
import 'package:routely/runtime/route_geometry_runtime.dart';
import 'package:routely/tracking/tracking_api_mapper.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'road retry-after accepts seconds and HTTP dates with bounded backoff',
    () {
      final now = DateTime.utc(2026, 10, 7, 10).millisecondsSinceEpoch;
      expect(roadRetryAfterMillis('0', now), 60000);
      expect(roadRetryAfterMillis('120', now), 120000);
      expect(roadRetryAfterMillis('99999999999999999999999999', now), 900000);
      expect(
        roadRetryAfterMillis('Wed, 07 Oct 2026 10:02:00 GMT', now),
        120000,
      );
      expect(roadRetryAfterMillis('Wed, 07 Oct 2026 09:00:00 GMT', now), 60000);
      expect(
        roadRetryAfterMillis('Wed, 07 Oct 2026 11:00:00 GMT', now),
        900000,
      );
      expect(
        roadRetryAfterMillis('Tue, 07 Oct 2026 10:02:00 GMT', now),
        isNull,
      );
      expect(
        roadRetryAfterMillis('Wed, 32 Oct 2026 10:02:00 GMT', now),
        isNull,
      );
      expect(roadRetryAfterMillis('-10', now), isNull);
      expect(roadRetryAfterMillis('invalid', now), isNull);
    },
  );
  test(
    'unconfirmed replacement stops never send road requests using station-center fallback',
    () async {
      final calls = <http.Request>[];
      final store = AppStore();
      final geometry = RuntimeRouteGeometry(
        store,
        anonymousClient: MockClient((request) async {
          calls.add(request);
          return http.Response('{}', 200);
        }),
      );
      final stops = [
        Stop.fromJson({
          'uuid': 'a',
          'station': {
            'id': 1,
            'name': 'A',
            'latitude': 52.0,
            'longitude': 13.0,
          },
          'departurePlanned': '2026-10-07T10:00:00Z',
        }),
        Stop.fromJson({
          'uuid': 'b',
          'station': {
            'id': 2,
            'name': 'B',
            'latitude': 52.1,
            'longitude': 13.0,
          },
          'arrivalPlanned': '2026-10-07T10:20:00Z',
        }),
      ];
      final checkin = Checkin.fromJson({
        'category': 'bus',
        'mode': 'bus',
        'lineName': 'Bus RE 1',
        'origin': stops.first.toJson(),
        'destination': stops.last.toJson(),
      });
      final result = await geometry.load(
        statusId: 17,
        checkin: checkin,
        fullStops: stops,
        checkedInStops: stops,
        trackingStops: toTrackingStops(stops, checkin),
        nextIndex: 1,
      );
      expect(result, isEmpty);
      expect(calls, isEmpty);
      geometry.close();
      store.dispose();
    },
  );

  test(
    'verified replacement pairs use anonymous HTTPS public endpoints only',
    () async {
      final fixture = File(
        '../app/src/test/resources/routing/duisburg-muelheim-osrm.json',
      ).readAsStringSync();
      final json = jsonDecode(fixture) as Map;
      final waypoints = json['waypoints'] as List;
      final stops = List<Stop>.generate(2, (index) {
        final point = waypoints[index]['location'] as List;
        return Stop.fromJson({
          'uuid': index == 0 ? 'a' : 'b',
          'station': {
            'id': index + 1,
            'name': 'Halt $index',
            'latitude': point[1],
            'longitude': point[0],
          },
          if (index == 0) 'departurePlanned': '2026-10-07T10:00:00Z',
          if (index == 1) 'arrivalPlanned': '2026-10-07T10:20:00Z',
        });
      });
      final checkin = Checkin.fromJson({
        'category': 'bus',
        'mode': 'bus',
        'lineName': 'Bus RE 1',
        'origin': stops.first.toJson(),
        'destination': stops.last.toJson(),
      });
      final calls = <http.Request>[];
      final store = AppStore();
      final geometry = RuntimeRouteGeometry(
        store,
        anonymousClient: MockClient((request) async {
          calls.add(request);
          return http.Response.bytes(
            utf8.encode(fixture),
            200,
            headers: {'content-type': 'application/json'},
          );
        }),
      );
      final result = await geometry.load(
        statusId: 17,
        checkin: checkin,
        fullStops: stops,
        checkedInStops: stops,
        trackingStops: toTrackingStops(stops, checkin),
        nextIndex: 1,
        verifiedReplacementKeys: {'a', 'b'},
      );
      expect(calls, hasLength(1));
      expect(calls.single.headers.containsKey('Authorization'), isFalse);
      expect(calls.single.followRedirects, isFalse);
      expect(calls.single.url.scheme, 'https');
      expect(calls.single.url.host, 'routing.openstreetmap.de');
      expect(calls.single.url.queryParameters['steps'], 'false');
      expect(result, hasLength(1));
      expect(result.single.fromKey, 'a');
      expect(result.single.toKey, 'b');
      geometry.close();
      store.dispose();
    },
  );
}
