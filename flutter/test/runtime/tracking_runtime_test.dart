import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:routely/data/app_store.dart';
import 'package:routely/data/models.dart';
import 'package:routely/data/routely_api.dart';
import 'package:routely/platform/native_trip_bridge.dart';
import 'package:routely/runtime/tracking_runtime.dart';
import 'package:routely/runtime/route_geometry_runtime.dart';
import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/sev_enrichment.dart';
import 'package:routely/tracking/tracking_route_geometry.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _Secure implements SecureSessionStorage {
  String? record;
  int writes = 0;
  @override
  Future<String?> read() async => record;
  @override
  Future<void> write(String? value) async {
    writes++;
    record = value;
  }
}

class _Bridge extends NativeTripBridge {
  final controller = StreamController<Json>.broadcast(sync: true);
  Json config = {'running': false};
  final publications = <Json>[];
  final starts = <Json>[], stops = <Json>[];
  bool acceptStart = true;
  bool acceptStop = true;
  Future<Json> Function(Json)? startOverride;
  bool throwOnSnapshot = false;
  @override
  bool get supported => true;
  @override
  Stream<Json> get events => controller.stream;
  @override
  Future<Json> getConfiguration() async => Map.of(config);
  @override
  Future<Json> getSnapshot() async {
    if (throwOnSnapshot) throw PlatformException(code: 'snapshot_unavailable');
    return publications.isEmpty ? {} : publications.last;
  }

  @override
  Future<Json> requestPermissions({bool background = false}) async => {
    'location': true,
    'precise': true,
    'locationServicesEnabled': true,
    'background': true,
  };
  @override
  Future<Json> startTracking(Json payload) async {
    starts.add(payload);
    if (startOverride != null) return startOverride!(payload);
    if (!acceptStart) return {'accepted': false, 'running': false};
    config = {...payload, 'running': true, 'gpsAvailable': true};
    controller.add({'type': 'configuration', ...config});
    return {'accepted': true, 'running': true};
  }

  @override
  Future<Json> stopTracking(
    NativeTripIdentity identity, {
    String reason = 'manual',
  }) async {
    stops.add(identity.toMap());
    if (!acceptStop) return {'accepted': false, 'running': true};
    if (identity.matches(config)) {
      config = {...config, 'running': false};
      controller.add({'type': 'configuration', ...config});
    }
    return {'accepted': true, 'running': false};
  }

  @override
  Future<Json> publish(Json value) async {
    publications.add(value);
    config['runtime'] = value['runtime'];
    controller.add({'type': 'snapshot', 'snapshot': value});
    return {'accepted': true};
  }
}

class _Speech extends TrackingSpeech {
  final started = Completer<void>();
  final completed = Completer<bool>();
  final spoken = <String>[];
  @override
  Future<bool> speak(
    String text,
    AppSettings settings, {
    required Future<bool> Function() stillRelevant,
  }) async {
    if (!await stillRelevant()) return false;
    spoken.add(text);
    if (!started.isCompleted) started.complete();
    return completed.future;
  }

  @override
  Future<void> stop() async {
    if (!completed.isCompleted) completed.complete(false);
  }
}

class _SlowSev extends SevJourneyEnricher {
  _SlowSev() : super(BahnhofSevRepository());
  final started = Completer<void>(), pending = Completer<Map<String, SevMap>>();
  int calls = 0;
  @override
  Future<Map<String, SevMap>> loadMaps(Checkin checkin, List<Stop> fullRoute) {
    calls++;
    if (calls == 1) {
      started.complete();
      return pending.future;
    }
    return Future.value({});
  }
}

class _RecordingGeometry extends RuntimeRouteGeometry {
  _RecordingGeometry(super.store)
    : super(
        anonymousClient: MockClient(
          (_) async => throw StateError('unexpected network'),
        ),
      );
  int calls = 0;
  @override
  Future<List<GpsSegmentGeometry>> load({
    required int statusId,
    required Checkin checkin,
    required List<Stop> fullStops,
    required List<Stop> checkedInStops,
    required List<TrackingStop> trackingStops,
    required int nextIndex,
    Set<String> verifiedReplacementKeys = const {},
  }) async {
    calls++;
    return const [];
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _Secure secure;
  late SharedPreferences prefs;
  late AppStore ui, background;
  late _Bridge bridge;
  late int now;
  int platform = 1;
  final runners = <TrackingBackgroundRunner>[];
  final runtimes = <TrackingRuntime>[];

  List<Stop> stops({int id = 17, int? originTime}) => [
    Stop.fromJson({
      'uuid': 'origin$id',
      'station': {
        'id': 1,
        'name': 'Start',
        'latitude': 52.0,
        'longitude': 13.0,
      },
      'departurePlanned': DateTime.fromMillisecondsSinceEpoch(
        originTime ?? now - 600000,
        isUtc: true,
      ).toIso8601String(),
      'departurePlatformReal': '1',
    }),
    Stop.fromJson({
      'uuid': 'destination$id',
      'station': {
        'id': 2,
        'name': 'Ziel',
        'latitude': 52.01,
        'longitude': 13.0,
      },
      'arrivalPlanned': DateTime.fromMillisecondsSinceEpoch(
        now + 600000,
        isUtc: true,
      ).toIso8601String(),
      'arrivalPlatformReal': '$platform',
    }),
  ];
  Status status({int id = 17, int? originTime}) {
    final route = stops(id: id, originTime: originTime);
    return Status.fromJson({
      'id': id,
      'user': {'id': 1, 'username': 'a'},
      'checkin': {
        'trip': 12,
        'tripUuid': 'trip',
        'lineName': 'RE 1',
        'category': 'regional',
        'mode': 'train',
        'origin': route.first.toJson(),
        'destination': route.last.toJson(),
      },
    });
  }

  Json config({Status? selected, List<Stop>? full, int generation = 1}) {
    final value = selected ?? status(), route = full ?? stops();
    return {
      'running': true,
      'gpsAvailable': true,
      'sessionRevision': ui.sessionRevision,
      'statusId': value.id,
      'generation': generation,
      'route': {
        'status': value.toJson(),
        'providerFullStops': route.map((s) => s.toJson()).toList(),
      },
      'settings': ui.settings.toJson(),
    };
  }

  Future<TrackingBackgroundRunner> runner({TrackingSpeech? speech}) async {
    final result = TrackingBackgroundRunner(
      store: background,
      bridge: bridge,
      speech: speech ?? _Speech(),
      nowMillis: () => now,
      enrichmentEnabled: false,
    );
    runners.add(result);
    await result.start(bridge.config, timers: false);
    return result;
  }

  Json fix(NativeTripIdentity identity, double latitude, int mono) => {
    ...identity.toMap(),
    'type': 'fix',
    'latitude': latitude,
    'longitude': 13.0,
    'accuracy': 5.0,
    'speed': 0.0,
    'timeMillis': now,
    'elapsedRealtimeNanos': mono,
    'nowMonotonicNanos': mono,
    'nowMillis': now,
    'clockId': 'clock',
  };

  setUp(() async {
    now = DateTime.utc(2026, 10, 7, 12).millisecondsSinceEpoch;
    platform = 1;
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    secure = _Secure();
    bridge = _Bridge();
    final client = MockClient((request) async {
      if (request.url.path.endsWith('/auth/logout')) {
        return http.Response('', 204);
      }
      if (request.url.path.endsWith('/auth/user')) {
        return http.Response('{"data":{"id":1,"username":"a"}}', 200);
      }
      if (request.url.path.contains('/stopovers/')) {
        return http.Response(
          jsonEncode({
            'data': {'12': stops().map((s) => s.toJson()).toList()},
          }),
          200,
        );
      }
      return http.Response(jsonEncode({'data': status().toJson()}), 200);
    });
    ui = AppStore(client: client, secureStorage: secure, preferences: prefs);
    await ui.login('https://example.com', 'a');
    await ui.setActiveStatus(17);
    background = AppStore(
      client: client,
      secureStorage: secure,
      preferences: prefs,
    );
    await background.initialize(validate: false, readOnly: true);
    bridge.config = config();
  });
  tearDown(() async {
    for (final value in runtimes) {
      value.dispose();
    }
    runtimes.clear();
    for (final value in runners) {
      await value.dispose();
    }
    runners.clear();
    await bridge.controller.close();
    background.dispose();
    ui.dispose();
  });

  test(
    'UI consumes snapshots only and replacement 17→18 is not stopped by its active write',
    () async {
      final runtime = TrackingRuntime(store: ui, bridge: bridge);
      runtimes.add(runtime);
      await runtime.initialize();
      await runtime.start(status(), stops());
      final first = runtime.identity!;
      await runtime.start(status(id: 18), stops(id: 18));
      expect(runtime.identity!.statusId, 18);
      expect(ui.activeStatusId, 18);
      expect(runtime.identity!.generation, greaterThan(first.generation));
      expect(bridge.stops, isEmpty);
      bridge.controller.add({
        ...first.toMap(),
        'type': 'configuration',
        'running': true,
      });
      expect(runtime.identity!.statusId, 18);
      bridge.controller.add({
        ...first.toMap(),
        'type': 'configuration',
        'running': false,
      });
      expect(runtime.identity!.statusId, 18);
      bridge.controller.add({
        ...first.toMap(),
        'type': 'fix',
        'latitude': 52.0,
        'longitude': 13.0,
      });
      expect(runtime.snapshot.value, isNull);
      bridge.controller.add({
        'type': 'snapshot',
        'snapshot': {...first.toMap(), 'nextStop': 'stale'},
      });
      expect(runtime.snapshot.value, isNull);
      bridge.controller.add({
        'type': 'snapshot',
        'snapshot': {...runtime.identity!.toMap(), 'nextStop': 'Ziel'},
      });
      expect(runtime.snapshot.value!['nextStop'], 'Ziel');
    },
  );

  test(
    'a channel startup error retires the attempt and restores the active selection',
    () async {
      await ui.setActiveStatus(null);
      bridge.config = {'running': false};
      final runtime = TrackingRuntime(store: ui, bridge: bridge);
      runtimes.add(runtime);
      await runtime.initialize();
      bridge.startOverride = (_) async =>
          throw PlatformException(code: 'service_unavailable');

      await expectLater(
        runtime.start(status(), stops()),
        throwsA(isA<PlatformException>()),
      );

      expect(runtime.running, isFalse);
      expect(ui.activeStatusId, isNull);
      expect((jsonDecode(secure.record!) as Map)['activeStatusId'], isNull);
      expect(bridge.stops.single, {
        for (final key in ['sessionRevision', 'statusId', 'generation'])
          key: bridge.starts.single[key],
      });
    },
  );

  test(
    'a delayed accepted start after logout retires its exact native owner',
    () async {
      final runtime = TrackingRuntime(store: ui, bridge: bridge);
      runtimes.add(runtime);
      await runtime.initialize();
      final started = Completer<void>(), release = Completer<void>();
      bridge.startOverride = (payload) async {
        started.complete();
        await release.future;
        bridge.config = {...payload, 'running': true};
        bridge.controller.add({'type': 'configuration', ...bridge.config});
        return {'accepted': true, 'running': true};
      };
      final attempt = runtime.start(status(), stops());
      final rejected = expectLater(
        attempt,
        throwsA(isA<StaleRequestException>()),
      );
      await started.future;
      await ui.logout();
      release.complete();
      await rejected;

      expect(ui.authenticated, isFalse);
      expect(secure.record, isNull);
      expect(bridge.config['running'], isFalse);
      expect(runtime.running, isFalse);
      expect(
        bridge.stops.last['generation'],
        bridge.starts.single['generation'],
      );
    },
  );

  test('initial snapshot failure preserves a confirmed native start', () async {
    final runtime = TrackingRuntime(store: ui, bridge: bridge);
    runtimes.add(runtime);
    await runtime.initialize();
    bridge.throwOnSnapshot = true;

    await runtime.start(status(), stops());

    expect(runtime.running, isTrue);
    expect(ui.activeStatusId, 17);
    expect(bridge.config['running'], isTrue);
    expect(runtime.error, contains('ist aktiv'));
    expect(bridge.stops, isEmpty);
  });

  test(
    'native permission schema reports denial and reduced accuracy without inventing a grant',
    () {
      expect(
        trackingLocationPermissionMessage({
          'location': true,
          'precise': true,
          'locationServicesEnabled': true,
        }, gpsEnabled: true),
        isNull,
      );
      expect(
        trackingLocationPermissionMessage({
          'location': false,
          'precise': false,
          'locationServicesEnabled': true,
        }, gpsEnabled: true),
        contains('Kein Standortzugriff'),
      );
      expect(
        trackingLocationPermissionMessage({
          'location': true,
          'precise': false,
          'locationServicesEnabled': true,
        }, gpsEnabled: true),
        contains('ungefährer Standort'),
      );
      expect(
        trackingLocationPermissionMessage({
          'location': true,
          'precise': true,
          'locationServicesEnabled': false,
        }, gpsEnabled: true),
        contains('ausgeschaltet'),
      );
      expect(
        trackingLocationPermissionMessage({
          'locationGranted': true,
        }, gpsEnabled: true),
        contains('Kein Standortzugriff'),
      );
      expect(trackingLocationPermissionMessage({}, gpsEnabled: false), isNull);
    },
  );

  test(
    'manual stop clears active id even when running:false event precedes acknowledgement',
    () async {
      final runtime = TrackingRuntime(store: ui, bridge: bridge);
      runtimes.add(runtime);
      await runtime.initialize();
      await runtime.start(status(), stops());
      await runtime.stop();
      expect(runtime.running, isFalse);
      expect(ui.activeStatusId, isNull);
    },
  );

  test(
    'UI resumes and reconciles a native notification stop while it was closed',
    () async {
      final previous = NativeTripIdentity.fromMap(bridge.config);
      bridge.config = {
        'running': false,
        'generation': previous.generation,
        'lastStopped': {...previous.toMap(), 'reason': 'notification'},
      };
      final runtime = TrackingRuntime(store: ui, bridge: bridge);
      runtimes.add(runtime);
      await runtime.initialize();
      expect(ui.activeStatusId, isNull);
      expect(runtime.running, isFalse);
      expect((jsonDecode(secure.record!) as Map)['activeStatusId'], isNull);
    },
  );

  test(
    'a native timeout does not erase the resumable secure route target',
    () async {
      final previous = NativeTripIdentity.fromMap(bridge.config);
      bridge.config = {
        'running': false,
        'generation': previous.generation,
        'lastStopped': {...previous.toMap(), 'reason': 'timeout'},
      };
      final runtime = TrackingRuntime(store: ui, bridge: bridge);
      runtimes.add(runtime);
      await runtime.initialize();
      expect(ui.activeStatusId, 17);
      expect(runtime.running, isFalse);
    },
  );

  test(
    'a rejected native stop retains the active trip for a real retry',
    () async {
      final runtime = TrackingRuntime(store: ui, bridge: bridge);
      runtimes.add(runtime);
      await runtime.initialize();
      await runtime.start(status(), stops());
      bridge.acceptStop = false;
      await expectLater(runtime.stop(), throwsA(isA<ApiException>()));
      expect(runtime.running, isTrue);
      expect(ui.activeStatusId, 17);
      bridge.acceptStop = true;
      await runtime.stop();
      expect(runtime.running, isFalse);
      expect(ui.activeStatusId, isNull);
    },
  );

  test(
    'headless publishes bound safe checkpoints without secure mutation or device fixes',
    () async {
      final writeCount = secure.writes;
      final value = await runner();
      await value.handleEvent(fix(value.identity!, 52.0, 10000000000));
      expect(secure.writes, writeCount);
      final published = bridge.publications.last;
      expect(value.identity!.matches(published), isTrue);
      expect(published.containsKey('latitude'), isFalse);
      expect(published.containsKey('elapsedRealtimeNanos'), isFalse);
      expect((published['runtime'] as Map).containsKey('gpsTimes'), isFalse);
      expect((published['runtime'] as Map).containsKey('fix'), isFalse);
      expect(published['tripProgress'], isA<Map>());
    },
  );

  test(
    'native outage followed by fresh fix recovers observation without enabling user preference',
    () async {
      final value = await runner();
      await value.handleEvent({
        ...value.identity!.toMap(),
        'type': 'error',
        'message': 'GPS aus',
      });
      final unavailable = bridge.publications.last;
      expect(unavailable['locationError'], 'GPS aus');
      now += 6000;
      await value.handleEvent(fix(value.identity!, 52.0, 16000000000));
      expect(bridge.publications.last['locationError'], isNull);
      expect(
        value.tracker.snapshot!.gpsTimes?.updatedAtMillis,
        anyOf(isNull, now),
      );
      expect(background.settings.gpsTrackingEnabled, isTrue);
    },
  );

  test(
    'TTS off retains provider change alerts across subsequent timetable ticks',
    () async {
      final value = await runner();
      await value.refresh();
      platform = 2;
      now += 55000;
      await value.refresh();
      expect((bridge.publications.last['changeAlerts'] as List), isNotEmpty);
      expect(
        (bridge.publications.last['changes'] as List).single['kind'],
        'platform',
      );
      await value.tick();
      expect(
        (bridge.publications.last['changeAlerts'] as List).single['message'],
        contains('Gleis 2'),
      );
      expect((bridge.publications.last['changes'] as List), isNotEmpty);
    },
  );

  test('speech claims persist only after completed acknowledgement', () async {
    await ui.setSetting('tts_enabled', true);
    await background.synchronizeSession();
    final route = stops(originTime: now + 60000),
        selected = status(originTime: now + 60000);
    bridge.config = config(selected: selected, full: route);
    final speech = _Speech();
    final value = await runner(speech: speech);
    await speech.started.future;
    final pending = bridge.publications.last['runtime'] as Map;
    expect(
      (pending['progress'] as Map)['announcedKeys'],
      isNot(contains('origin17')),
    );
    expect(bridge.publications.last['completed'], isFalse);
    speech.completed.complete(true);
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    await value.tick();
    expect(
      (bridge.publications.last['runtime']['progress'] as Map)['announcedKeys'],
      contains('origin17'),
    );
  });

  test(
    'external logout retires background runner without writing credentials',
    () async {
      final value = await runner();
      final count = secure.writes;
      secure.record = null;
      await value.tick();
      await value.done;
      expect(secure.writes, count);
      expect(value.tracker.journey, isNull);
    },
  );

  test(
    'likes leave a running route current, but its own edit retires old completion work',
    () async {
      final value = await runner();
      await ui.setLiked(18, true);
      await value.tick();
      expect(value.tracker.journey, isNotNull);
      expect(bridge.publications.last['completionReason'], isNull);
      await ui.updateStatus(17, UpdateStatusRequest(body: 'new'));
      await value.tick();
      await value.done;
      expect(value.tracker.journey, isNull);
      expect(bridge.publications.last['completed'], isTrue);
      expect(
        bridge.publications.last['completionReason'],
        'runtime_unavailable',
      );
      expect(ui.activeStatusId, 17);
    },
  );

  test(
    'invalid initial route releases only its exact native generation',
    () async {
      bridge.config = {...config(), 'route': {}};
      final value = TrackingBackgroundRunner(
        store: background,
        bridge: bridge,
        speech: _Speech(),
        enrichmentEnabled: false,
      );
      runners.add(value);
      await expectLater(
        value.start(bridge.config, timers: false),
        throwsA(isA<TypeError>()),
      );
      await value.done;
      expect(bridge.publications.last['completed'], isTrue);
      expect(
        bridge.publications.last['completionReason'],
        'runtime_unavailable',
      );
      expect(ui.activeStatusId, 17);
    },
  );

  test(
    'a delayed old configuration cannot dispose the current background owner',
    () async {
      bridge.config = config(generation: 2);
      final value = await runner();
      await value.handleEvent({
        ...config(generation: 1),
        'type': 'configuration',
        'running': false,
      });
      expect(value.tracker.journey, isNotNull);
      expect(bridge.publications.last['completed'], isFalse);
      expect(bridge.config['generation'], 2);
    },
  );

  test('dispose from an old engine cannot retire a new generation', () async {
    final value = await runner();
    final calls = bridge.publications.length;
    bridge.config = config(generation: 2);
    await value.dispose();
    expect(bridge.publications.length, calls);
    expect(bridge.config['running'], isTrue);
    expect(bridge.config['generation'], 2);
  });

  test('unresolvable route never starts native tracking', () async {
    final runtime = TrackingRuntime(store: ui, bridge: bridge);
    runtimes.add(runtime);
    await runtime.initialize();
    await expectLater(
      runtime.start(status(), [stops().first, stops().first]),
      throwsA(isA<ApiException>()),
    );
    expect(bridge.starts, isEmpty);
  });

  test(
    'unconfirmed SEV shows fallback time without a future GPS forecast',
    () async {
      final original = status();
      final bus = Status.fromJson({
        ...original.toJson(),
        'checkin': {
          ...original.checkin!.toJson(),
          'category': 'bus',
          'mode': 'bus',
          'lineName': 'Bus RE 1',
        },
      });
      bridge.config = config(selected: bus);
      await runner();
      final published = bridge.publications.last;
      expect(published['etaReason'], 'replacementStopUnconfirmed');
      expect(published['eta']?['source'], isNot('gpsPredicted'));
      expect(published['geometrySource'], isNull);
    },
  );

  test(
    'slow enrichment survives equivalent refreshed response objects and schedules pending work',
    () async {
      final original = status(), full = stops();
      final fixed = MockClient((request) async {
        if (request.url.path.contains('/stopovers/')) {
          return http.Response(
            jsonEncode({
              'data': {'12': full.map((s) => s.toJson()).toList()},
            }),
            200,
          );
        }
        return http.Response(
          jsonEncode({
            'data': {...original.toJson(), 'body': 'new text'},
          }),
          200,
        );
      });
      background.dispose();
      background = AppStore(
        client: fixed,
        secureStorage: secure,
        preferences: prefs,
      );
      await background.initialize(validate: false, readOnly: true);
      bridge.config = config(selected: original, full: full);
      final sev = _SlowSev(), geometry = _RecordingGeometry(background);
      final value = TrackingBackgroundRunner(
        store: background,
        bridge: bridge,
        speech: _Speech(),
        nowMillis: () => now,
        geometry: geometry,
        sevEnricher: sev,
      );
      runners.add(value);
      await value.start(bridge.config, timers: false);
      final job = value.refreshEnrichment();
      await sev.started.future;
      now += 50000;
      await value.refresh();
      sev.pending.complete({});
      await job;
      expect(geometry.calls, greaterThanOrEqualTo(1));
      expect(
        trackingEnrichmentBasis(original, full),
        trackingEnrichmentBasis(
          Status.fromJson({...original.toJson(), 'body': 'new text'}),
          full,
        ),
      );
      sev.repository.close();
    },
  );
}
