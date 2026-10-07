import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:routely/data/routely_api.dart';
import 'package:routely/recognition/ride_discovery.dart';
import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/ride_recognition_engine.dart';

const epoch = 1791352800000;
String instant(int time) =>
    DateTime.fromMillisecondsSinceEpoch(time, isUtc: true).toIso8601String();
Map<String, dynamic> departure(
  String id, {
  bool cancelled = false,
  int offset = -60000,
}) => {
  'tripId': id,
  'line': {'name': 'RE1'},
  'plannedWhen': instant(epoch + offset),
  'cancelled': cancelled,
  'station': {'id': 1, 'name': 'A'},
};
Map<String, dynamic> trip() => {
  'stopovers': [
    {
      'station': {'id': 1, 'name': 'A', 'latitude': 51.0, 'longitude': 7.0},
      'departurePlanned': instant(epoch - 60000),
    },
    {
      'station': {'id': 3, 'name': 'C', 'latitude': 51.02, 'longitude': 7.0},
      'arrivalPlanned': instant(epoch + 300000),
    },
  ],
};
http.Response response(Object data, [int code = 200]) =>
    http.Response(jsonEncode({'data': data}), code);
RoutelyApi api(Future<http.Response> Function(http.Request) handler) =>
    RoutelyApi(
      session: const AuthSession(
        serverUrl: 'https://example.test',
        accessToken: 'test-only',
        revision: 'a',
      ),
      client: MockClient(handler),
    );
LocationFix fix(int now) => LocationFix(
  latitude: 51.0,
  longitude: 7.0,
  accuracyMeters: 10,
  timeMillis: now,
);
List<Map<String, dynamic>> stations({bool two = false}) => [
  {'id': 1, 'name': 'A', 'latitude': 51.0, 'longitude': 7.0},
  if (two) {'id': 2, 'name': 'B', 'latitude': 51.001, 'longitude': 7.0},
];
RecognizableRide oldRide(String id) => RecognizableRide(
  tripId: id,
  lineName: 'RE1',
  stops: const [],
  originIndex: -1,
  fetchedAtMillis: epoch,
);

void main() {
  test(
    'lookback includes the departed train and filters before a global six-trip limit',
    () async {
      final queries = <Uri>[];
      final details = <String>[];
      final client = api((request) async {
        queries.add(request.url);
        if (request.url.path.endsWith('/stations')) return response(stations());
        if (request.url.path.endsWith('/departures')) {
          return response([
            for (var i = 0; i < 6; i++) departure('future$i', offset: 3600000),
            departure('departed'),
            departure('departed'),
          ]);
        }
        details.add(request.url.queryParameters['hafasTripId']!);
        return response(trip());
      });
      final discovery = RideDiscovery(
        api: client,
        engine: RideRecognitionEngine(),
        isCurrent: () => true,
        nowMillis: () => epoch,
      );
      final rides = await discovery.discover(fix(epoch));
      expect(rides.map((r) => r.tripId), ['departed']);
      expect(details, ['departed']);
      expect(
        queries
            .singleWhere((q) => q.path.endsWith('/departures'))
            .queryParameters['when'],
        instant(epoch - 300000),
      );
      expect(
        queries.first.queryParameters.keys,
        containsAll(['min_lat', 'max_lat', 'min_lon', 'max_lon']),
      );
      expect(
        queries
            .where((q) => q.path.endsWith('/trip'))
            .single
            .queryParameters
            .keys,
        isNot(contains('latitude')),
      );
    },
  );

  test(
    'one successful departures response survives a neighboring station failure',
    () async {
      final client = api((request) async {
        if (request.url.path.endsWith('/stations')) {
          return response(stations(two: true));
        }
        if (request.url.path.contains('/station/2/')) return response({}, 500);
        if (request.url.path.endsWith('/departures')) {
          return response([departure('working')]);
        }
        return response(trip());
      });
      final discovery = RideDiscovery(
        api: client,
        engine: RideRecognitionEngine(),
        isCurrent: () => true,
        nowMillis: () => epoch,
      );
      expect((await discovery.discover(fix(epoch))).single.tripId, 'working');
    },
  );

  test(
    'all failed departures is an error; successful empty departures is a valid empty scan',
    () async {
      var fail = true;
      final client = api(
        (request) async => request.url.path.endsWith('/stations')
            ? response(stations())
            : fail
            ? response({}, 503)
            : response([]),
      );
      final discovery = RideDiscovery(
        api: client,
        engine: RideRecognitionEngine(),
        isCurrent: () => true,
        nowMillis: () => epoch,
      );
      await expectLater(
        discovery.discover(fix(epoch)),
        throwsA(isA<ApiException>()),
      );
      fail = false;
      expect(await discovery.discover(fix(epoch)), isEmpty);
    },
  );

  test(
    'authoritative cancellation removes an earlier match even when remaining details fail',
    () async {
      final engine = RideRecognitionEngine()
        ..updateRides([oldRide('cancelled'), oldRide('unaffected')], epoch);
      final client = api((request) async {
        if (request.url.path.endsWith('/stations')) return response(stations());
        if (request.url.path.endsWith('/departures')) {
          return response([
            departure('cancelled', cancelled: true),
            departure('new'),
          ]);
        }
        return response({}, 503);
      });
      final discovery = RideDiscovery(
        api: client,
        engine: engine,
        isCurrent: () => true,
        nowMillis: () => epoch,
      );
      await expectLater(
        discovery.discover(fix(epoch)),
        throwsA(isA<ApiException>()),
      );
      expect(engine.storedRouteCount, 1);
    },
  );

  test(
    'cached trip age is preserved and an expired trip is fetched again',
    () async {
      var now = epoch, detailCalls = 0;
      final client = api((request) async {
        if (request.url.path.endsWith('/stations')) return response(stations());
        if (request.url.path.endsWith('/departures')) {
          return response([departure('ride')]);
        }
        detailCalls++;
        return response(trip());
      });
      final discovery = RideDiscovery(
        api: client,
        engine: RideRecognitionEngine(),
        isCurrent: () => true,
        nowMillis: () => now,
      );
      expect(
        (await discovery.discover(fix(now))).single.fetchedAtMillis,
        epoch,
      );
      now += 180000;
      expect(
        (await discovery.discover(fix(now))).single.fetchedAtMillis,
        epoch,
      );
      expect(detailCalls, 1);
      now = epoch + 300001;
      expect((await discovery.discover(fix(now))).single.fetchedAtMillis, now);
      expect(detailCalls, 2);
    },
  );

  test(
    'timeout retires cancellations but a late detail cannot fill a cleared cache',
    () async {
      final detail = Completer<http.Response>();
      final started = Completer<void>();
      var detailCalls = 0;
      final engine = RideRecognitionEngine()
        ..updateRides([oldRide('cancelled')], epoch);
      final client = api((request) async {
        if (request.url.path.endsWith('/stations')) return response(stations());
        if (request.url.path.endsWith('/departures')) {
          return response([
            departure('cancelled', cancelled: true),
            departure('new'),
          ]);
        }
        detailCalls++;
        if (detailCalls == 1) {
          started.complete();
          return detail.future;
        }
        return response(trip());
      });
      final discovery = RideDiscovery(
        api: client,
        engine: engine,
        isCurrent: () => true,
        nowMillis: () => epoch,
        timeout: const Duration(milliseconds: 25),
      );
      final scan = discovery.discover(fix(epoch));
      final failure = expectLater(scan, throwsA(isA<ApiException>()));
      await started.future;
      expect(engine.storedRouteCount, 0);
      await failure;
      detail.complete(response(trip()));
      await Future<void>.delayed(Duration.zero);
      final rides = await discovery.discover(fix(epoch));
      expect(rides.single.tripId, 'new');
      expect(detailCalls, 2);
    },
  );

  test(
    'clock or owner reset rejects a late nearby response before requesting departures',
    () async {
      final nearby = Completer<http.Response>();
      final started = Completer<void>();
      var calls = 0;
      final client = api((request) {
        calls++;
        started.complete();
        return nearby.future;
      });
      final discovery = RideDiscovery(
        api: client,
        engine: RideRecognitionEngine(),
        isCurrent: () => true,
        nowMillis: () => epoch,
      );
      final scan = discovery.discover(fix(epoch));
      final stale = expectLater(scan, throwsA(isA<StaleRequestException>()));
      await started.future;
      discovery.clear();
      nearby.complete(response(stations()));
      await stale;
      expect(calls, 1);
    },
  );
}
