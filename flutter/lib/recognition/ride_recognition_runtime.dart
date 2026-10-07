import 'dart:async';

import 'package:flutter/foundation.dart';

import '../data/app_store.dart';
import '../data/models.dart';
import '../data/routely_api.dart';
import '../platform/native_trip_bridge.dart';
import '../tracking/models.dart';
import '../tracking/ride_recognition_engine.dart';
import '../tracking/tracking_api_mapper.dart';
import '../tracking/tracking_location_clock.dart';
import 'ride_discovery.dart';

class RecognitionSelection {
  const RecognitionSelection(this.station, this.departure, this.trip);
  final Station station;
  final Departure departure;
  final Trip trip;
}

/// Visible controls observe native results; only the background engine sees GPS.
class RideRecognitionRuntime {
  RideRecognitionRuntime(
    this.store, {
    NativeTripBridge? bridge,
    int Function()? nowMillis,
  }) : bridge = bridge ?? NativeTripBridge(),
       _nowMillis = nowMillis ?? (() => DateTime.now().millisecondsSinceEpoch);
  final AppStore store;
  final NativeTripBridge bridge;
  final int Function() _nowMillis;
  final snapshot = ValueNotifier<Json?>(null);
  StreamSubscription<Json>? _subscription;
  late final _openRequests = StreamController<void>.broadcast(
    onListen: () => unawaited(Future<void>.microtask(_deliverOpen)),
  );
  Stream<void> get openRequests => _openRequests.stream;
  Json? _pendingOpen;
  bool _adoptingConfiguration = false;
  NativeTripIdentity? _identity;
  int _operation = 0;
  bool _initialized = false, _disposed = false;

  Future<void> initialize() async {
    if (!bridge.supported || _initialized || _disposed) return;
    _initialized = true;
    _adoptingConfiguration = true;
    store.addListener(_onStoreChanged);
    final operation = _operation, revision = store.sessionRevision;
    _subscription = bridge.events.listen(
      (event) {
        if (_disposed) return;
        if (event['type'] == 'snapshot') {
          _accept(
            event['snapshot'] is Map
                ? Map<String, dynamic>.from(event['snapshot'] as Map)
                : event,
          );
        } else if (event['type'] == 'openStatus') {
          _queueOpen(event);
        } else if (event['type'] == 'configuration') {
          final config = event['configuration'] is Map
              ? Map<String, dynamic>.from(event['configuration'] as Map)
              : event;
          // A delayed stop for a retired owner cannot clear a newer scan.
          if (config['running'] == false &&
              (config['generation'] is num &&
                  (config['generation'] as num).toInt() >=
                      (_identity?.generation ?? 0))) {
            _identity = null;
            _pendingOpen = null;
            snapshot.value = null;
          }
        }
      },
      onError: (_) {
        if (!_disposed && _identity?.sessionRevision == store.sessionRevision) {
          snapshot.value = {
            'message': 'Fahrterkennung ist derzeit nicht erreichbar.',
            'candidates': [],
          };
        }
      },
    );
    try {
      final config = await bridge.getConfiguration();
      if (_disposed ||
          operation != _operation ||
          revision != store.sessionRevision) {
        return;
      }
      if (config['mode'] == 'recognition' &&
          config['sessionRevision'] == revision &&
          config['running'] == true &&
          store.settings.rideRecognitionEnabled &&
          store.activeStatusId == null) {
        _identity = NativeTripIdentity.fromMap(config);
      }
      _accept(await bridge.getSnapshot());
    } finally {
      _adoptingConfiguration = false;
      _deliverOpen();
    }
  }

  void _queueOpen(Json event) {
    if (_disposed ||
        event['statusId'] != 0 ||
        event['sessionRevision'] != store.sessionRevision ||
        event['generation'] is! num ||
        !store.settings.rideRecognitionEnabled ||
        store.activeStatusId != null) {
      return;
    }
    if (_identity == null && !_adoptingConfiguration) return;
    if (_identity != null && !_identity!.matches(event)) return;
    _pendingOpen = Map.of(event);
    _deliverOpen();
  }

  void _deliverOpen() {
    if (_disposed || _adoptingConfiguration) return;
    final pending = _pendingOpen, identity = _identity;
    if (pending == null) return;
    if (identity == null ||
        !identity.matches(pending) ||
        identity.sessionRevision != store.sessionRevision ||
        !store.settings.rideRecognitionEnabled ||
        store.activeStatusId != null) {
      _pendingOpen = null;
      return;
    }
    if (_openRequests.hasListener) {
      _pendingOpen = null;
      _openRequests.add(null);
    }
  }

  void _onStoreChanged() {
    final identity = _identity;
    if (identity == null ||
        identity.sessionRevision == store.sessionRevision &&
            store.activeStatusId == null &&
            store.settings.rideRecognitionEnabled) {
      return;
    }
    ++_operation;
    _identity = null;
    _pendingOpen = null;
    if (!_disposed) snapshot.value = null;
    unawaited(
      bridge
          .stopTracking(identity, reason: 'recognitionInvalidated')
          .catchError((_) => <String, dynamic>{}),
    );
  }

  void _accept(Json value) {
    final identity = _identity;
    if (_disposed ||
        identity == null ||
        !identity.matches(value) ||
        value['statusId'] != 0 ||
        value['sessionRevision'] != store.sessionRevision ||
        !store.settings.rideRecognitionEnabled ||
        store.activeStatusId != null) {
      return;
    }
    if (value['recognition'] is Map) {
      snapshot.value = Map<String, dynamic>.unmodifiable(
        Map<String, dynamic>.from(value['recognition'] as Map),
      );
    }
  }

  Future<void> start() async {
    if (_disposed) throw const StaleRequestException();
    if (!bridge.supported) {
      throw const ApiException(
        'Fahrterkennung im Hintergrund ist auf Android und iOS verfügbar.',
      );
    }
    if (!store.authenticated || store.activeStatusId != null) {
      throw const ApiException('Bitte zuerst die aktive Reise beenden.');
    }
    final op = ++_operation, session = store.session!;
    final permission = await bridge.requestPermissions(background: true);
    if (permission['location'] != true ||
        permission['precise'] != true ||
        permission['locationServicesEnabled'] != true ||
        permission['background'] != true) {
      throw const ApiException(
        'Für die Fahrterkennung werden präziser Standort und die Hintergrundfreigabe benötigt.',
      );
    }
    _requireOperation(op, session);
    final previous = await bridge.getConfiguration();
    _requireOperation(op, session);
    final previousGeneration = (previous['generation'] as num?)?.toInt() ?? 0;
    final generation = _nowMillis() * 1000;
    final next = NativeTripIdentity(
      sessionRevision: session.revision,
      statusId: 0,
      generation: generation > previousGeneration
          ? generation
          : previousGeneration + 1,
    );
    await store.setSetting('ride_recognition_enabled', true);
    _requireOperation(op, session);
    _identity = next;
    try {
      final result = await bridge.startRecognition({
        ...next.toMap(),
        'mode': 'recognition',
        'settings': store.settings.toJson(),
        'route': <String, dynamic>{},
        'runtime': <String, dynamic>{},
      });
      _requireOperation(op, session);
      if (result['running'] != true || result['accepted'] == false) {
        throw const ApiException(
          'Die Fahrterkennung konnte nicht gestartet werden.',
        );
      }
      snapshot.value = {
        'message': 'Warte auf einen frischen, präzisen Standort.',
        'phase': 'waitingForLocation',
        'candidates': [],
      };
      _accept(await bridge.getSnapshot());
    } catch (_) {
      // The exact retired generation is safe to stop even after an account switch.
      try {
        await bridge.stopTracking(next, reason: 'recognitionStartFailed');
      } catch (_) {}
      if (!_disposed && op == _operation && store.isCurrent(session)) {
        _identity = null;
        snapshot.value = null;
        await store.setSetting('ride_recognition_enabled', false);
      }
      rethrow;
    }
  }

  void _requireOperation(int operation, AuthSession session) {
    if (_disposed ||
        operation != _operation ||
        !store.isCurrent(session) ||
        store.activeStatusId != null) {
      throw const StaleRequestException();
    }
  }

  Future<void> stop({bool clearPreference = true}) async {
    final operation = ++_operation,
        identity = _identity,
        session = store.session;
    _identity = null;
    _pendingOpen = null;
    if (!_disposed) snapshot.value = null;
    try {
      if (identity != null) {
        await bridge.stopTracking(identity, reason: 'recognitionStopped');
      }
    } finally {
      if (clearPreference &&
          !_disposed &&
          operation == _operation &&
          session != null &&
          store.isCurrent(session)) {
        // Revoke consent even when native shutdown reports an error. The headless
        // owner also checks this secure flag before making each request.
        await store.setSetting('ride_recognition_enabled', false);
      }
    }
  }

  Json _currentCandidate(
    Json candidate,
    NativeTripIdentity identity,
    int operation,
    AuthSession session,
  ) {
    _requireOperation(operation, session);
    if (_identity != identity || !store.settings.rideRecognitionEnabled) {
      throw const StaleRequestException();
    }
    final candidates = snapshot.value?['candidates'];
    final matches = candidates is List
        ? candidates
              .whereType<Map>()
              .where((value) => value['id'] == candidate['id'])
              .toList()
        : <Map>[];
    if (matches.length != 1 || candidate['id'] is! String) {
      throw const ApiException(
        'Dieser Vorschlag ist nicht mehr aktuell. Bitte erneut erkennen lassen.',
      );
    }
    final canonical = Map<String, dynamic>.from(matches.single);
    final latest = (canonical['latestFixMillis'] as num?)?.toInt();
    final age = latest == null ? -1 : _nowMillis() - latest;
    final selectedFix = (candidate['latestFixMillis'] as num?)?.toInt();
    final selectedAge = selectedFix == null ? -1 : _nowMillis() - selectedFix;
    if (age < 0 ||
        age > RideRecognitionEngine.maxFixAgeMillis ||
        selectedAge < 0 ||
        selectedAge > RideRecognitionEngine.maxFixAgeMillis ||
        canonical['station'] is! Map ||
        canonical['departure'] is! Map) {
      throw const ApiException(
        'Dieser Vorschlag ist nicht mehr aktuell. Bitte erneut erkennen lassen.',
      );
    }
    return canonical;
  }

  Future<RecognitionSelection> confirm(Json candidate) async {
    final identity = _identity, session = store.session, operation = _operation;
    if (identity == null || session == null) {
      throw const StaleRequestException();
    }
    final canonical = _currentCandidate(
      candidate,
      identity,
      operation,
      session,
    );
    final station = Station.fromJson(
      Map<String, dynamic>.from(canonical['station'] as Map),
    );
    final departure = Departure.fromJson(
      Map<String, dynamic>.from(canonical['departure'] as Map),
    );
    final planned = parseMillis(departure.plannedWhen);
    if (station.id == null ||
        station.id! <= 0 ||
        departure.tripId.isEmpty ||
        planned == null) {
      throw const ApiException(
        'Der Einstiegshalt konnte nicht eindeutig zugeordnet werden.',
      );
    }
    final departures = await store.api.departures(
      station.id!,
      when: departure.plannedWhen,
    );
    store.requireCurrent(session);
    _currentCandidate(candidate, identity, operation, session);
    final matching = departures
        .where(
          (d) =>
              d.tripId == departure.tripId &&
              d.lineName == departure.lineName &&
              parseMillis(d.plannedWhen) == planned &&
              !d.cancelled,
        )
        .toList();
    if (matching.length != 1) {
      throw const ApiException(
        'Die vorgeschlagene Fahrt hat sich geändert. Bitte neu auswählen.',
      );
    }
    final trip = await store.api.trip(departure.tripId, departure.lineName);
    store.requireCurrent(session);
    _currentCandidate(candidate, identity, operation, session);
    final ride = recognizableRide(
      departure: matching.single,
      trip: trip,
      fallbackStation: station,
      fetchedAtMillis: _nowMillis(),
    );
    if (ride.originIndex < 0 ||
        ride.stops.skip(ride.originIndex + 1).every((s) => s.cancelled)) {
      throw const ApiException(
        'Der Einstiegshalt dieser Fahrt ist nicht eindeutig verfügbar.',
      );
    }
    return RecognitionSelection(station, matching.single, trip);
  }

  void dispose() {
    _disposed = true;
    ++_operation;
    if (_initialized) store.removeListener(_onStoreChanged);
    unawaited(_subscription?.cancel());
    _pendingOpen = null;
    unawaited(_openRequests.close());
    snapshot.dispose();
  }
}

/// One native background engine owns recognition. Nearby lookups are opt-in;
/// no GPS history is saved or sent with trip/routing requests.
Future<void> runRecognitionBackground(
  AppStore store,
  NativeTripBridge bridge,
  Json configuration,
) async {
  final identity = NativeTripIdentity.fromMap(configuration),
      session = store.session;
  if (session == null ||
      session.revision != identity.sessionRevision ||
      !store.settings.rideRecognitionEnabled ||
      store.activeStatusId != null) {
    await _releaseRecognitionOwner(bridge, identity);
    return;
  }
  final worker = _RecognitionWorker(store, bridge, identity, session);
  await worker.run();
}

Future<void> _releaseRecognitionOwner(
  NativeTripBridge bridge,
  NativeTripIdentity identity,
) async {
  try {
    final config = await bridge.getConfiguration().timeout(
      const Duration(seconds: 3),
    );
    if (config['running'] == true && identity.matches(config)) {
      // Native stopTracking is a UI command. A background engine is allowed
      // to release only its exact owner through a terminal publication.
      await bridge
          .publish({
            ...identity.toMap(),
            'completed': true,
            'completionReason': 'recognitionOwnerEnded',
          })
          .timeout(const Duration(seconds: 3));
    }
  } catch (_) {
    // Native timeout remains the fallback when its channel is unavailable.
  }
}

class _RecognitionWorker {
  _RecognitionWorker(this.store, this.bridge, this.identity, this.session) {
    // Capture the authorized API while constructing the owner. Cleanup must not
    // lazily request a new API after another engine has removed the credentials.
    discovery = RideDiscovery(
      api: store.api,
      engine: engine,
      isCurrent: () => current,
    );
  }
  final AppStore store;
  final NativeTripBridge bridge;
  final NativeTripIdentity identity;
  final AuthSession session;
  final engine = RideRecognitionEngine();
  var clock = TrackingLocationClock();
  String? clockId;
  late final RideDiscovery discovery;
  final finished = Completer<void>();
  StreamSubscription<Json>? subscription;
  Timer? tick;
  bool closed = false, searching = false;
  int lastSearch = 0, observation = 0, searchOperation = 0;
  String? error;
  bool get current =>
      !closed &&
      store.isCurrent(session) &&
      store.activeStatusId == null &&
      store.settings.rideRecognitionEnabled;
  Future<void> run() async {
    subscription = bridge.events.listen((event) {
      if (event['type'] == 'fix' && identity.matches(event)) {
        unawaited(_fix(event));
      }
      if (event['type'] == 'configuration' &&
          (!identity.matches(event) || event['running'] == false)) {
        // A queued stop from an older engine does not retire this owner. Verify
        // the current native configuration and secure consent before closing.
        unawaited(_tick());
      }
    }, onError: (_) => close());
    tick = Timer.periodic(
      const Duration(seconds: 10),
      (_) => unawaited(_tick()),
    );
    try {
      await _publish();
      await finished.future;
    } finally {
      close();
      await finished.future;
    }
  }

  Future<void> _tick() async {
    try {
      await store.synchronizeSession();
      if (!current) {
        close();
        return;
      }
      final config = await bridge.getConfiguration();
      if (!identity.matches(config) || config['running'] != true) {
        close();
        return;
      }
      await _publish();
    } catch (_) {
      close();
    }
  }

  Future<void> _fix(Json raw) async {
    if (!current) {
      close();
      return;
    }
    try {
      final fix = NativeLocationFix.fromMap(raw),
          now = DateTime.now().millisecondsSinceEpoch;
      if (clockId != null && clockId != fix.clockId) {
        clock = TrackingLocationClock();
        engine.clear();
        discovery.clear();
        lastSearch = 0;
        searching = false;
        observation++;
      }
      clockId = fix.clockId;
      final sample = clock.observe(
        latitude: fix.latitude,
        longitude: fix.longitude,
        accuracyMeters: fix.accuracy,
        monotonicNanos: fix.elapsedRealtimeNanos,
        nowMonotonicNanos:
            (raw['nowMonotonicNanos'] as num?)?.toInt() ??
            fix.elapsedRealtimeNanos,
        nowMillis: (raw['nowMillis'] as num?)?.toInt() ?? now,
        hasSpeed: fix.speed != null,
        speedMetersPerSecond: fix.speed ?? 0,
      );
      if (sample.clockChanged) {
        engine.clear();
        discovery.clear();
        lastSearch = 0;
        searching = false;
        observation++;
      }
      if (sample.isNew && sample.fix != null) {
        engine.onLocation(sample.fix!, now);
      }
      final latest = engine.reliableLatestFix(now);
      if (latest != null && !searching && now - lastSearch >= 90000) {
        unawaited(_discover(latest, observation));
      }
      await _publish();
    } catch (_) {
      error = 'Warte auf einen zuverlässigen Standort.';
    }
  }

  Future<void> _discover(LocationFix fix, int observed) async {
    if (!current || searching) return;
    searching = true;
    final operation = ++searchOperation;
    lastSearch = DateTime.now().millisecondsSinceEpoch;
    try {
      final rides = await discovery.discover(fix);
      if (current && observation == observed) {
        engine.updateRides(rides, DateTime.now().millisecondsSinceEpoch);
        error = null;
      }
    } on StaleRequestException {
      if (!current) close();
    } on ApiException catch (failure) {
      if (current && observation == observed) error = failure.message;
    } catch (_) {
      if (current && observation == observed) {
        error =
            'Fahrten konnten gerade nicht geladen werden. Die Suche läuft weiter.';
      }
    } finally {
      if (operation == searchOperation) {
        searching = false;
        if (current) await _publish();
      }
    }
  }

  Future<void> _publish() async {
    if (!current) return;
    final config = await bridge.getConfiguration();
    if (!current || !identity.matches(config) || config['running'] != true) {
      close();
      return;
    }
    final now = DateTime.now().millisecondsSinceEpoch,
        matches = engine.matches(now);
    final candidates = <Json>[];
    for (final match in matches) {
      final payload = match.ride.payload;
      if (payload is! (Station, Departure, Trip)) continue;
      candidates.add({
        'id': match.id,
        'latestFixMillis': match.latestFixMillis,
        'nextStationName': match.nextStationName,
        'station': payload.$1.toJson(),
        'departure': payload.$2.toJson(),
        'trip': payload.$3.toJson(),
      });
    }
    final message = candidates.isNotEmpty
        ? 'Eine passende Fahrt wurde erkannt. Bitte Fahrt und Ziel bestätigen.'
        : error ??
              (engine.reliableLatestFix(now) == null
                  ? 'Warte auf einen frischen, präzisen Standort.'
                  : searching
                  ? 'Suche passende Fahrten …'
                  : 'Beobachte deinen Fahrweg für einen sicheren Vorschlag.');
    await bridge.publish({
      ...identity.toMap(),
      'line': 'Fahrterkennung',
      'nextStop': message,
      'destination': '',
      'timeLabel': '',
      'progress': 0.0,
      'recognition': {
        'message': message,
        'phase': candidates.isNotEmpty
            ? 'matches'
            : error != null
            ? 'error'
            : searching
            ? 'searching'
            : engine.reliableLatestFix(now) == null
            ? 'waitingForLocation'
            : 'observing',
        'candidates': candidates,
      },
      'runtime': <String, dynamic>{},
    });
  }

  void close() {
    if (closed) return;
    closed = true;
    engine.clear();
    discovery.clear();
    tick?.cancel();
    unawaited(_releaseOwner());
  }

  Future<void> _releaseOwner() async {
    try {
      await subscription?.cancel();
      await _releaseRecognitionOwner(bridge, identity);
    } finally {
      if (!finished.isCompleted) finished.complete();
    }
  }
}
