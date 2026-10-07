import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:routely/data/app_store.dart';
import 'package:routely/data/models.dart';
import 'package:routely/data/routely_api.dart';
import 'package:routely/platform/native_trip_bridge.dart';
import 'package:routely/recognition/ride_recognition_runtime.dart';
import 'package:shared_preferences/shared_preferences.dart';

const now = 1791352800000;
String instant(int value) =>
    DateTime.fromMillisecondsSinceEpoch(value, isUtc: true).toIso8601String();
Json dep = {
  'tripId': 'journey',
  'line': {'name': 'RE1'},
  'plannedWhen': instant(now - 60000),
  'station': {'id': 1, 'name': 'A'},
};
Json candidate = {
  'id': 'journey|RE1|boarding-visit',
  'latestFixMillis': now,
  'station': {'id': 1, 'name': 'A'},
  'departure': dep,
};
Json trip = {
  'stopovers': [
    {
      'station': {'id': 1, 'name': 'A', 'latitude': 51.0, 'longitude': 7.0},
      'departurePlanned': instant(now - 60000),
    },
    {
      'station': {'id': 2, 'name': 'B', 'latitude': 51.02, 'longitude': 7.0},
      'arrivalPlanned': instant(now + 300000),
    },
  ],
};

class MemorySecure implements SecureSessionStorage {
  String? value;
  @override
  Future<String?> read() async => value;
  @override
  Future<void> write(String? next) async => value = next;
}

class FakeBridge extends NativeTripBridge {
  final controller = StreamController<Json>.broadcast(sync: true);
  Json config = {'generation': 0, 'running': false};
  Json latest = {};
  final publications = <Json>[];
  final stopped = <Json>[];
  Future<Json> Function(Json)? onStart;
  Future<Json> Function()? onConfiguration;
  Json permissions = {
    "location": true,
    "precise": true,
    "background": true,
    "locationServicesEnabled": true,
  };
  Future<Json> Function(NativeTripIdentity)? onStop;
  @override
  bool get supported => true;
  @override
  Stream<Json> get events => controller.stream;
  @override
  Future<Json> getConfiguration() async =>
      onConfiguration == null ? Map.of(config) : await onConfiguration!();
  @override
  Future<Json> getSnapshot() async => Map.of(latest);
  @override
  Future<Json> requestPermissions({bool background = false}) async =>
      Map.of(permissions);
  @override
  Future<Json> startRecognition(Json payload) async {
    if (onStart != null) return onStart!(payload);
    config = {...payload, 'running': true};
    return {'running': true, 'accepted': true};
  }

  @override
  Future<Json> stopTracking(
    NativeTripIdentity identity, {
    String reason = 'manual',
  }) async {
    stopped.add({...identity.toMap(), 'reason': reason});
    if (onStop != null) return onStop!(identity);
    if (identity.matches(config)) {
      config = {'running': false, 'generation': identity.generation};
    }
    return {'accepted': true};
  }

  @override
  Future<Json> publish(Json value) async {
    publications.add(Map.of(value));
    latest = Map.of(value);
    if (value['completed'] == true &&
        NativeTripIdentity.fromMap(config).matches(value)) {
      config = {...config, 'running': false};
      controller.add({...config, 'type': 'configuration'});
    }
    return {'accepted': true};
  }

  void emitCandidates(List<Json> values, {int? generation, String? revision}) {
    latest = {
      ...config,
      'generation': generation ?? config['generation'],
      'sessionRevision': revision ?? config['sessionRevision'],
      'recognition': {'message': 'Gefunden', 'candidates': values},
    };
    controller.add({...latest, 'type': 'snapshot'});
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late AppStore store;
  late FakeBridge bridge;
  late RideRecognitionRuntime runtime;
  Future<http.Response> Function(http.Request)? network;
  final requests = <http.Request>[];
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    store = AppStore(
      client: MockClient((request) async {
        requests.add(request);
        if (request.url.path.endsWith('/auth/user')) {
          return http.Response(
            jsonEncode({
              'data': {
                'id': 1,
                'username': request.headers['Authorization'] == 'Bearer b'
                    ? 'b'
                    : 'a',
              },
            }),
            200,
          );
        }
        if (network != null) return network!(request);
        if (request.url.path.endsWith('/departures')) {
          return http.Response(
            jsonEncode({
              'data': [dep],
            }),
            200,
          );
        }
        return http.Response(jsonEncode({'data': trip}), 200);
      }),
      preferences: prefs,
      secureStorage: MemorySecure(),
    );
    await store.login('https://example.test', 'a');
    bridge = FakeBridge();
    runtime = RideRecognitionRuntime(
      store,
      bridge: bridge,
      nowMillis: () => now,
    );
    await runtime.initialize();
    requests.clear();
  });
  tearDown(() async {
    network = null;
    requests.clear();
    runtime.dispose();
    store.dispose();
    await bridge.controller.close();
    debugDefaultTargetPlatformOverride = null;
  });

  test(
    'snapshots need an adopted exact owner and retired generations cannot replace it',
    () async {
      bridge.emitCandidates(
        [candidate],
        generation: 99,
        revision: store.sessionRevision,
      );
      expect(runtime.snapshot.value, isNull);
      await runtime.start();
      final generation = bridge.config['generation'] as int;
      bridge.emitCandidates([candidate]);
      expect(runtime.snapshot.value!['candidates'], hasLength(1));
      bridge.emitCandidates([], generation: generation - 1);
      expect(runtime.snapshot.value!['candidates'], hasLength(1));
      bridge.controller.add({
        'type': 'configuration',
        'running': false,
        'generation': generation - 1,
      });
      expect(runtime.snapshot.value!['candidates'], hasLength(1));
      await store.login('https://example.test', 'b');
      expect(runtime.snapshot.value, isNull);
      bridge.emitCandidates([candidate], revision: store.sessionRevision);
      expect(runtime.snapshot.value, isNull);
      expect(
        bridge.stopped.single['sessionRevision'],
        isNot(store.sessionRevision),
      );
    },
  );

  test('an old account stop cannot revoke a new account consent', () async {
    await runtime.start();
    final pending = Completer<Json>();
    bridge.onStop = (_) => pending.future;
    final stop = runtime.stop();
    await store.login('https://example.test', 'b');
    await store.setSetting('ride_recognition_enabled', true);
    pending.complete({'accepted': true});
    await stop;
    expect(store.settings.rideRecognitionEnabled, isTrue);
  });

  test(
    'native stop failure still revokes consent for the owning account',
    () async {
      await runtime.start();
      bridge.onStop = (_) async => throw StateError('native unavailable');
      await expectLater(runtime.stop(), throwsA(isA<StateError>()));
      expect(store.settings.rideRecognitionEnabled, isFalse);
      expect(runtime.snapshot.value, isNull);
    },
  );

  test(
    'retired native start completion is cleaned up without reviving UI',
    () async {
      final pending = Completer<Json>(), begun = Completer<void>();
      bridge.onStart = (payload) {
        bridge.config = {...payload, 'running': true};
        begun.complete();
        return pending.future;
      };
      final start = runtime.start();
      final stale = expectLater(start, throwsA(isA<StaleRequestException>()));
      await begun.future;
      await runtime.stop();
      pending.complete({'running': true, 'accepted': true});
      await stale;
      expect(runtime.snapshot.value, isNull);
      expect(store.settings.rideRecognitionEnabled, isFalse);
      expect(bridge.config['running'], isFalse);
    },
  );

  test(
    'confirmation rejects removed and future-fix candidates before making requests',
    () async {
      await runtime.start();
      bridge.emitCandidates([candidate]);
      await expectLater(
        runtime.confirm({...candidate, 'id': 'old'}),
        throwsA(isA<ApiException>()),
      );
      bridge.emitCandidates([
        {...candidate, 'latestFixMillis': now + 1},
      ]);
      await expectLater(
        runtime.confirm({...candidate, 'latestFixMillis': now + 1}),
        throwsA(isA<ApiException>()),
      );
      expect(requests, isEmpty);
    },
  );

  test(
    'confirmation uses canonical station and permits fresh fixes of the same visit',
    () async {
      await runtime.start();
      bridge.emitCandidates([candidate]);
      final departureResponse = Completer<http.Response>(),
          begun = Completer<void>();
      network = (request) {
        if (request.url.path.endsWith('/departures')) {
          begun.complete();
          return departureResponse.future;
        }
        return Future.value(http.Response(jsonEncode({'data': trip}), 200));
      };
      final selection = runtime.confirm({
        ...candidate,
        'station': {'id': 999},
      });
      await begun.future;
      bridge.emitCandidates([
        {...candidate, 'latestFixMillis': now - 1},
      ]);
      departureResponse.complete(
        http.Response(
          jsonEncode({
            'data': [dep],
          }),
          200,
        ),
      );
      final confirmed = await selection;
      expect(confirmed.station.id, 1);
      expect(requests.first.url.path, endsWith('/station/1/departures'));
      expect(requests.where((r) => r.method == 'POST'), isEmpty);
    },
  );

  test(
    'stopping during departure validation prevents the trip request and confirmation',
    () async {
      await runtime.start();
      bridge.emitCandidates([candidate]);
      final pending = Completer<http.Response>(), begun = Completer<void>();
      network = (request) {
        begun.complete();
        return pending.future;
      };
      final confirming = runtime.confirm(candidate);
      final stale = expectLater(
        confirming,
        throwsA(isA<StaleRequestException>()),
      );
      await begun.future;
      await runtime.stop();
      pending.complete(
        http.Response(
          jsonEncode({
            'data': [dep],
          }),
          200,
        ),
      );
      await stale;
      expect(requests, hasLength(1));
    },
  );
  test(
    'notification opens only the exact running recognition generation',
    () async {
      var opens = 0;
      final subscription = runtime.openRequests.listen((_) => opens++);
      await runtime.start();
      bridge.controller.add({...bridge.config, 'type': 'openStatus'});
      await Future<void>.delayed(Duration.zero);
      expect(opens, 1);
      bridge.controller.add({
        ...bridge.config,
        'type': 'openStatus',
        'generation': (bridge.config['generation'] as int) - 1,
      });
      bridge.controller.add({
        ...bridge.config,
        'type': 'openStatus',
        'statusId': 77,
      });
      await Future<void>.delayed(Duration.zero);
      expect(opens, 1);
      await runtime.stop();
      bridge.controller.add({
        ...bridge.config,
        'type': 'openStatus',
        'statusId': 0,
      });
      await Future<void>.delayed(Duration.zero);
      expect(opens, 1);
      await subscription.cancel();
    },
  );

  test(
    'startup notification is buffered until configuration and shell listener are ready',
    () async {
      runtime.dispose();
      await store.setSetting('ride_recognition_enabled', true);
      bridge.config = {
        'sessionRevision': store.sessionRevision,
        'statusId': 0,
        'generation': 100,
        'running': true,
        'mode': 'recognition',
      };
      final response = Completer<Json>();
      bridge.onConfiguration = () {
        bridge.controller.add({...bridge.config, 'type': 'openStatus'});
        return response.future;
      };
      runtime = RideRecognitionRuntime(
        store,
        bridge: bridge,
        nowMillis: () => now,
      );
      final initializing = runtime.initialize();
      response.complete(Map.of(bridge.config));
      await initializing;
      var opens = 0;
      final subscription = runtime.openRequests.listen((_) => opens++);
      await Future<void>.delayed(Duration.zero);
      expect(opens, 1);
      await subscription.cancel();
      var laterOpens = 0;
      final later = runtime.openRequests.listen((_) => laterOpens++);
      await Future<void>.delayed(Duration.zero);
      expect(laterOpens, 0);
      await later.cancel();
    },
  );

  test(
    'coarse or disabled location never starts recognition or persists consent',
    () async {
      bridge.permissions['precise'] = false;
      await expectLater(runtime.start(), throwsA(isA<ApiException>()));
      expect(store.settings.rideRecognitionEnabled, isFalse);
      expect(bridge.config['running'], isFalse);
    },
  );
  test(
    'headless startup without consent releases only its own native generation',
    () async {
      bridge.config = {
        'sessionRevision': store.sessionRevision,
        'statusId': 0,
        'generation': 100,
        'running': true,
        'mode': 'recognition',
      };
      final retired = Map<String, dynamic>.of(bridge.config);
      // Consent was removed before the background engine finished bootstrap.
      await runRecognitionBackground(store, bridge, retired);
      expect(bridge.config['running'], isFalse);
      expect(
        bridge.publications.single['completionReason'],
        'recognitionOwnerEnded',
      );

      bridge.config = {...retired, 'generation': 101, 'running': true};
      await runRecognitionBackground(store, bridge, retired);
      expect(bridge.config['running'], isTrue);
      expect(bridge.publications, hasLength(1));
      expect(bridge.stopped, isEmpty);
    },
  );

  test(
    'headless owner ignores queued stops from an older native generation',
    () async {
      await store.setSetting('ride_recognition_enabled', true);
      bridge.config = {
        'sessionRevision': store.sessionRevision,
        'statusId': 0,
        'generation': 100,
        'running': true,
        'mode': 'recognition',
      };
      final identity = Map<String, dynamic>.of(bridge.config);
      var ended = false;
      final running = runRecognitionBackground(
        store,
        bridge,
        identity,
      ).whenComplete(() => ended = true);
      await Future<void>.delayed(Duration.zero);
      bridge.controller.add({
        ...identity,
        'type': 'configuration',
        'generation': 99,
        'running': false,
      });
      await Future<void>.delayed(Duration.zero);
      await Future<void>.delayed(Duration.zero);
      expect(ended, isFalse);
      expect(bridge.config['running'], isTrue);
      expect(
        bridge.publications.where((value) => value['completed'] == true),
        isEmpty,
      );

      await store.setSetting('ride_recognition_enabled', false);
      bridge.controller.add({
        ...identity,
        'type': 'configuration',
        'running': false,
      });
      await running;
      expect(bridge.config['running'], isFalse);
      expect(bridge.stopped, isEmpty);
    },
  );

  test(
    'headless owner with no GPS fix can clean up after credentials disappear',
    () async {
      await store.setSetting('ride_recognition_enabled', true);
      bridge.config = {
        'sessionRevision': store.sessionRevision,
        'statusId': 0,
        'generation': 100,
        'running': true,
        'mode': 'recognition',
      };
      final identity = Map<String, dynamic>.of(bridge.config);
      final running = runRecognitionBackground(store, bridge, identity);
      await Future<void>.delayed(Duration.zero);
      await store.logout();
      bridge.controller.add({
        ...identity,
        'type': 'configuration',
        'running': false,
      });
      await running;
      expect(
        bridge.publications.any(
          (value) =>
              value['completed'] == true &&
              value['completionReason'] == 'recognitionOwnerEnded',
        ),
        isTrue,
      );
      expect(bridge.config['running'], isFalse);
      expect(bridge.stopped, isEmpty);
      expect(store.authenticated, isFalse);
    },
  );
}
