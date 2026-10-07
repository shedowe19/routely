import 'dart:async';
import 'dart:convert';
import 'dart:ui' show DartPluginRegistrant;

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_tts/flutter_tts.dart';

import '../data/app_store.dart';
import '../data/models.dart';
import '../data/routely_api.dart';
import '../platform/native_trip_bridge.dart';
import '../tracking/models.dart';
import '../tracking/sev_enrichment.dart';
import '../tracking/tracking_api_mapper.dart';
import '../tracking/trip_tracker.dart';
import '../tracking/gps_journey_time_estimator.dart';
import '../tracking/journey_time_resolver.dart';
import 'route_geometry_runtime.dart';
import 'foreground_trip_host.dart';

typedef RecognitionBackgroundRunner =
    Future<void> Function(AppStore, NativeTripBridge, Json);

/// Called by main.dart's preserved native entrypoint. Native owns engine lifetime;
/// UI and background engines never consume the same location stream.
Future<void> runTrackingBackground({
  RecognitionBackgroundRunner? recognitionRunner,
}) async {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();
  final store = AppStore();
  final bridge = NativeTripBridge();
  Json? configuration;
  try {
    await store.initialize(validate: false, readOnly: true);
    final config = await bridge.getConfiguration();
    configuration = config;
    if (config['mode'] == 'recognition') {
      if (recognitionRunner != null) {
        await recognitionRunner(store, bridge, config);
      } else {
        await _publishOwnedTerminal(bridge, config);
      }
      return;
    }
    final runner = TrackingBackgroundRunner(store: store, bridge: bridge);
    try {
      await runner.start(config);
      await runner.done;
    } finally {
      await runner.dispose();
    }
  } catch (_) {
    if (configuration != null) {
      await _publishOwnedTerminal(bridge, configuration);
    }
  } finally {
    store.dispose();
  }
}

Future<bool> _publishOwnedTerminal(NativeTripBridge bridge, Json config) async {
  try {
    final identity = NativeTripIdentity.fromMap(config);
    if (identity.generation <= 0 || identity.statusId < 0) return false;
    final current = await bridge.getConfiguration().timeout(
      const Duration(seconds: 3),
    );
    if (current['running'] != true || !identity.matches(current)) return false;
    final result = await bridge
        .publish({
          ...identity.toMap(),
          'completed': true,
          'completionReason': 'runtime_unavailable',
        })
        .timeout(const Duration(seconds: 3));
    return result['accepted'] == true;
  } catch (_) {
    return false;
  }
}

/// Visible-engine adapter: observes evaluated snapshots and authorizes starts.
class TrackingRuntime extends ChangeNotifier with WidgetsBindingObserver {
  TrackingRuntime({required this.store, NativeTripBridge? bridge})
    : bridge = bridge ?? NativeTripBridge();
  final AppStore store;
  final NativeTripBridge bridge;
  ForegroundTripHost? _foreground;
  NativeTripBridge get _host =>
      bridge.supported ? bridge : (_foreground ??= ForegroundTripHost(store));
  bool get backgroundSupported => bridge.supported;
  bool _foregroundWasRunning = false;
  bool _internalActiveWrite = false;
  final ValueNotifier<Json?> snapshot = ValueNotifier(null);
  final StreamController<int> _openStatuses = StreamController.broadcast();
  Stream<int> get openStatuses => _openStatuses.stream;
  NativeTripIdentity? _identity;
  NativeTripIdentity? get identity => _identity;
  bool get running => _identity != null;
  String? error;
  Json _permissions = const {};
  Json get permissions => Map.unmodifiable(_permissions);
  String? _permissionWarning;
  StreamSubscription<Json>? _events;
  StreamSubscription<StatusMutation>? _mutations;
  Status? _lastStatus;
  List<Stop> _lastStops = const [];
  int _operation = 0;
  int _lastGeneration = 0;
  bool _initialized = false;
  bool _disposed = false;
  Future<void>? _stopReconciliation;

  Future<void> initialize() async {
    if (_initialized || _disposed) return;
    _initialized = true;
    store.addListener(_onStoreChanged);
    _mutations = store.mutations.listen(_onMutation);
    if (!bridge.supported) WidgetsBinding.instance.addObserver(this);
    _events = _host.events.listen(
      _onEvent,
      onError: (_) {
        error = 'Die Fahrtbegleitung kann gerade nicht erreicht werden.';
        if (!_disposed) notifyListeners();
      },
    );
    try {
      final config = await _host.getConfiguration();
      _adoptConfiguration(config);
      await _stopReconciliation;
      _acceptSnapshot(await _host.getSnapshot());
    } catch (_) {
      error = 'Die Fahrtbegleitung konnte nicht geladen werden.';
      if (!_disposed) notifyListeners();
    }
  }

  void _adoptConfiguration(Json config) {
    final generation = config['generation'];
    final currentGeneration = _identity?.generation ?? _lastGeneration;
    if (generation is int && generation < currentGeneration) return;
    if (generation is! int && _identity != null) return;
    if (generation is int && generation > _lastGeneration) {
      _lastGeneration = generation;
    }
    if (config['running'] != true ||
        config['mode'] == 'recognition' ||
        config['statusId'] == 0 ||
        config['sessionRevision'] != store.sessionRevision) {
      _identity = null;
      _reconcileStopped(config);
      return;
    }
    try {
      _identity = NativeTripIdentity.fromMap(config);
      final route = config['route'];
      if (route is Map) {
        if (route['status'] is Map) {
          _lastStatus = Status.fromJson(
            Map<String, dynamic>.from(route['status'] as Map),
          );
        }
        if (route['providerFullStops'] is List) {
          _lastStops = (route['providerFullStops'] as List)
              .whereType<Map>()
              .map((s) => Stop.fromJson(Map<String, dynamic>.from(s)))
              .toList(growable: false);
        }
      }
    } catch (_) {
      _identity = null;
    }
  }

  void _reconcileStopped(Json config) {
    if (config['running'] == true || config['lastStopped'] is! Map) return;
    try {
      final marker = Map<String, dynamic>.from(config['lastStopped'] as Map);
      if (![
        'manual',
        'notification',
        'completed',
        'unavailable',
      ].contains(marker['reason'])) {
        return;
      }
      final stopped = NativeTripIdentity.fromMap(marker),
          session = store.session;
      if (stopped.generation <= 0 ||
          stopped.statusId <= 0 ||
          session == null ||
          session.revision != stopped.sessionRevision ||
          store.activeStatusId != stopped.statusId ||
          stopped.generation < _lastGeneration) {
        return;
      }
      final operation = _operation;
      _stopReconciliation = store
          .setActiveStatus(
            null,
            expectedSession: session,
            expectedActiveStatusId: stopped.statusId,
            isStillCurrent: () =>
                !_disposed &&
                operation == _operation &&
                _identity == null &&
                _lastGeneration <= stopped.generation,
          )
          .catchError((Object _) {
            // A newer UI start/logout supersedes this exact stopped generation.
          });
    } catch (_) {
      // Invalid/nonmatching public tombstones cannot modify secure state.
    }
  }

  void _onEvent(Json event) {
    if (_disposed) return;
    switch (event['type']) {
      case 'configuration':
        _adoptConfiguration(
          event['configuration'] is Map
              ? Map<String, dynamic>.from(event['configuration'] as Map)
              : event,
        );
        if (_identity == null) snapshot.value = null;
        notifyListeners();
      case 'snapshot':
        _acceptSnapshot(event);
      case 'openStatus':
        final id = event['statusId'];
        if (id is int &&
            id > 0 &&
            event['sessionRevision'] == store.sessionRevision) {
          _openStatuses.add(id);
        }
      case 'error':
        if (_identity?.matches(event) == true) {
          error = event['message'] is String
              ? event['message'] as String
              : 'Die Fahrtbegleitung ist unterbrochen.';
          notifyListeners();
        }
    }
  }

  void _acceptSnapshot(Json event) {
    if (_disposed) return;
    final payload = event['snapshot'] is Map
        ? Map<String, dynamic>.from(event['snapshot'] as Map)
        : event;
    final identity = _identity;
    if (identity == null ||
        !identity.matches(payload) ||
        payload['sessionRevision'] != store.sessionRevision ||
        payload['statusId'] == 0) {
      return;
    }
    snapshot.value = Map.unmodifiable(payload);
    if (payload['source'] == 'gps' && payload['locationError'] == null) {
      _permissionWarning = null;
    }
    error =
        payload['locationError'] as String? ??
        payload['speechError'] as String? ??
        _permissionWarning;
    notifyListeners();
    if (payload['completed'] == true &&
        payload['completionReason'] != 'runtime_unavailable' &&
        store.activeStatusId == identity.statusId) {
      final session = store.session;
      final operation = _operation;
      if (session != null) {
        unawaited(
          store
              .setActiveStatus(
                null,
                expectedSession: session,
                expectedActiveStatusId: identity.statusId,
                isStillCurrent: () =>
                    !_disposed &&
                    operation == _operation &&
                    _lastGeneration <= identity.generation,
              )
              .catchError((_) {}),
        );
      }
    }
  }

  void _onStoreChanged() {
    if (_internalActiveWrite) return;
    final identity = _identity;
    if (identity != null &&
        (identity.sessionRevision != store.sessionRevision ||
            store.activeStatusId != identity.statusId)) {
      unawaited(
        stop(
          statusId: identity.statusId,
          sessionRevision: identity.sessionRevision,
        ).catchError((_) {}),
      );
    }
  }

  void _onMutation(StatusMutation event) {
    if (_identity == null ||
        event.sessionRevision != _identity!.sessionRevision ||
        event.statusId != _identity!.statusId ||
        event.kind == StatusMutationKind.like) {
      return;
    }
    if (event.kind == StatusMutationKind.deleted) {
      unawaited(
        stop(
          statusId: event.statusId,
          sessionRevision: event.sessionRevision,
        ).catchError((_) {}),
      );
    } else {
      // The new native generation retires pending ETA, network and speech work.
      unawaited(_restartFromServer(event.statusId, event.sessionRevision));
    }
  }

  Future<void> _restartFromServer(int statusId, String revision) async {
    final operation = ++_operation;
    snapshot.value = null;
    try {
      final status = await store.status(statusId);
      final full = await _loadProviderStops(store, status);
      if (_disposed ||
          operation != _operation ||
          store.sessionRevision != revision ||
          store.activeStatusId != statusId) {
        return;
      }
      await start(status, full);
    } catch (_) {
      if (!_disposed && operation == _operation) {
        error =
            'Die geänderte Fahrt muss erneut geladen werden, bevor ihre Begleitung fortgesetzt wird.';
        notifyListeners();
      }
    }
  }

  Future<void> start(Status status, List<Stop> fullStops) async {
    if (_disposed) return;
    final session = store.session;
    final checkin = status.checkin;
    if (session == null ||
        checkin == null ||
        status.id <= 0 ||
        status.user?.username != store.user?.username) {
      throw const ApiException(
        'Diese Fahrt kann nicht mit der aktuellen Anmeldung begleitet werden.',
      );
    }
    final checked = checkedInRoute(fullStops, checkin);
    if (checked == null || checked.length < 2) {
      throw const ApiException(
        'Die Einstieg- und Ausstiegshalte sind in der Fahrt nicht eindeutig zugeordnet. Bitte lade die Fahrt erneut.',
      );
    }
    final operation = ++_operation;
    final revision = store.contentRevision;
    if (bridge.supported || store.settings.gpsTrackingEnabled) {
      final permissions = await _host.requestPermissions(
        background: bridge.supported,
      );
      store.requireCurrent(session);
      if (operation != _operation || revision != store.contentRevision) {
        throw const StaleRequestException();
      }
      _permissions = Map.of(permissions);
      _permissionWarning = trackingLocationPermissionMessage(
        permissions,
        gpsEnabled: store.settings.gpsTrackingEnabled,
      );
    } else {
      _permissionWarning = null;
    }
    final previous = await _host.getConfiguration();
    store.requireCurrent(session);
    if (operation != _operation || revision != store.contentRevision) {
      throw const StaleRequestException();
    }
    final previousGeneration = previous['generation'] is int
        ? previous['generation'] as int
        : 0;
    final epoch = DateTime.now().microsecondsSinceEpoch;
    final generation = [
      epoch,
      _lastGeneration + 1,
      previousGeneration + 1,
    ].reduce((a, b) => a > b ? a : b);
    final identity = NativeTripIdentity(
      sessionRevision: session.revision,
      statusId: status.id,
      generation: generation,
    );
    final journey = _journey(
      status,
      checked,
      session.revision,
      store.statusRevision(status.id),
    );
    final oldActive = store.activeStatusId;
    _internalActiveWrite = true;
    try {
      await store.setActiveStatus(status.id, expectedSession: session);
    } finally {
      _internalActiveWrite = false;
    }
    store.requireCurrent(session);
    if (operation != _operation || revision != store.contentRevision) {
      throw const StaleRequestException();
    }
    final payload = <String, dynamic>{
      ...identity.toMap(),
      'mode': 'tracking',
      'route': {
        'journey': journey.toJson(),
        'status': status.toJson(),
        'providerFullStops': fullStops.map((s) => s.toJson()).toList(),
      },
      'settings': store.settings.toJson(),
    };
    final runtime = previous['runtime'];
    if (runtime is Map &&
        _compatibleCheckpoint(Map<String, dynamic>.from(runtime), journey)) {
      payload['runtime'] = runtime;
    }
    final priorIdentity = _identity;
    _identity = identity;
    try {
      final result = await _host.startTracking(payload);
      store.requireCurrent(session);
      if (_disposed || operation != _operation) {
        throw const StaleRequestException();
      }
      if (result['running'] != true || result['accepted'] == false) {
        throw ApiException(
          result['message'] is String
              ? result['message'] as String
              : 'Die Fahrtbegleitung konnte nicht gestartet werden. Prüfe die Standort- und Benachrichtigungsfreigaben.',
        );
      }
    } catch (_) {
      // A channel error may arrive after native startup. Retire only this
      // attempt, including when logout or a newer start superseded its result.
      try {
        await _host
            .stopTracking(identity, reason: 'startFailed')
            .timeout(const Duration(seconds: 3));
      } catch (_) {}
      if (!_disposed && operation == _operation && store.isCurrent(session)) {
        _identity = null;
        // Rejection can leave the previous native owner running. Do not claim
        // it is still active when the failed attempt replaced that owner.
        try {
          final current = await _host.getConfiguration().timeout(
            const Duration(seconds: 3),
          );
          if (!_disposed &&
              operation == _operation &&
              store.isCurrent(session) &&
              current['running'] == true &&
              priorIdentity?.matches(current) == true) {
            _identity = priorIdentity;
          }
        } catch (_) {}
        if (!_disposed && operation == _operation && store.isCurrent(session)) {
          _internalActiveWrite = true;
          try {
            if (store.activeStatusId == status.id) {
              await store.setActiveStatus(
                oldActive,
                expectedSession: session,
                expectedActiveStatusId: status.id,
                isStillCurrent: () => !_disposed && operation == _operation,
              );
            }
          } finally {
            _internalActiveWrite = false;
          }
          snapshot.value = null;
          notifyListeners();
        }
      }
      rethrow;
    }
    _identity = identity;
    _lastGeneration = generation;
    _lastStatus = status;
    _lastStops = List.unmodifiable(fullStops);
    error = _permissionWarning;
    snapshot.value = null;
    try {
      _acceptSnapshot(await _host.getSnapshot());
    } catch (_) {
      // The confirmed native start remains valid if its initial display fails.
      if (!_disposed && operation == _operation) {
        error =
            'Die Fahrtbegleitung ist aktiv. Ihre Anzeige wird erneut geladen.';
      }
    }
    if (_disposed || operation != _operation) return;
    notifyListeners();
  }

  Future<void> stop({int? statusId, String? sessionRevision}) async {
    final identity = _identity;
    if (identity == null ||
        statusId != null && statusId != identity.statusId ||
        sessionRevision != null &&
            sessionRevision != identity.sessionRevision) {
      return;
    }
    final operation = ++_operation;
    final result = await _host.stopTracking(identity);
    if (_disposed ||
        operation != _operation ||
        _identity != null && _identity != identity ||
        _lastGeneration > identity.generation) {
      return;
    }
    if (result['accepted'] != true || result['running'] == true) {
      throw const ApiException(
        'Die Fahrtbegleitung konnte nicht beendet werden. Bitte versuche es erneut.',
      );
    }
    _identity = null;
    snapshot.value = null;
    final session = store.session;
    if (session != null &&
        session.revision == identity.sessionRevision &&
        store.activeStatusId == identity.statusId) {
      await store.setActiveStatus(
        null,
        expectedSession: session,
        expectedActiveStatusId: identity.statusId,
        isStillCurrent: () =>
            !_disposed &&
            operation == _operation &&
            (_identity == null || _identity == identity),
      );
    }
    notifyListeners();
  }

  Future<void> settingsChanged() async {
    final status = _lastStatus;
    if (_identity == null || status == null || _disposed) return;
    await start(status, _lastStops);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (bridge.supported || _disposed) return;
    if (state == AppLifecycleState.resumed) {
      if (_foregroundWasRunning && _lastStatus != null && store.authenticated) {
        _foregroundWasRunning = false;
        unawaited(start(_lastStatus!, _lastStops).catchError((_) {}));
      }
    } else if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.hidden ||
        state == AppLifecycleState.detached) {
      _foregroundWasRunning = running;
      unawaited(_foreground?.pause());
    }
  }

  @override
  void dispose() {
    _disposed = true;
    ++_operation;
    store.removeListener(_onStoreChanged);
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_foreground?.close());
    _events?.cancel();
    _mutations?.cancel();
    _openStatuses.close();
    snapshot.dispose();
    super.dispose();
  }
}

TrackingJourney _journey(
  Status status,
  List<Stop> checked,
  String revision,
  int contentRevision, {
  Map<String, (double, double)> coordinates = const {},
}) {
  final checkin = status.checkin!;
  return TrackingJourney(
    statusId: status.id,
    sessionRevision: revision,
    contentRevision: contentRevision,
    tripIdentity: '${checkin.trip}:${checkin.tripUuid ?? ''}',
    lineName: checkin.lineName ?? 'Fahrt',
    destinationName: checkin.destination?.stationName ?? 'Ausstieg',
    replacementBus: SevStopResolver.isReplacementBus(checkin),
    manualDepartureMillis: parseMillis(checkin.manualDeparture),
    manualArrivalMillis: parseMillis(checkin.manualArrival),
    stops: toTrackingStops(
      checked,
      checkin,
      replacementCoordinates: coordinates,
    ),
  );
}

bool _compatibleCheckpoint(Json checkpoint, TrackingJourney journey) {
  try {
    final previous = TrackingJourney.fromJson(
      Map<String, dynamic>.from(checkpoint['journey'] as Map),
    );
    if (previous.identity != journey.identity ||
        previous.contentRevision != journey.contentRevision ||
        previous.tripIdentity != journey.tripIdentity ||
        previous.stops.isEmpty ||
        journey.stops.isEmpty) {
      return false;
    }
    if (previous.stops.first.key != journey.stops.first.key ||
        previous.stops.last.key != journey.stops.last.key ||
        !previous.stops.first.isOrigin ||
        !previous.stops.last.isDestination ||
        !journey.stops.first.isOrigin ||
        !journey.stops.last.isDestination) {
      return false;
    }
    return checkpoint['version'] == 1;
  } catch (_) {
    return false;
  }
}

Future<List<Stop>> _loadProviderStops(AppStore store, Status status) async {
  final checkin = status.checkin;
  if (checkin == null) {
    throw const ApiException('Die Fahrt enthält keine Haltfolge.');
  }
  if (checkin.trip != null && checkin.trip! > 0) {
    return store.api.stopovers(checkin.trip!);
  }
  if (checkin.hafasId?.isNotEmpty == true &&
      checkin.lineName?.isNotEmpty == true) {
    return (await store.api.trip(
      checkin.hafasId!,
      checkin.lineName!,
    )).stopovers;
  }
  throw const ApiException(
    'Die vollständige Haltfolge kann nicht geladen werden.',
  );
}

abstract class TrackingSpeech {
  Future<bool> speak(
    String text,
    AppSettings settings, {
    required Future<bool> Function() stillRelevant,
  });
  Future<void> stop();
}

class DeviceTrackingSpeech implements TrackingSpeech {
  final FlutterTts _tts = FlutterTts();
  @override
  Future<bool> speak(
    String text,
    AppSettings settings, {
    required Future<bool> Function() stillRelevant,
  }) async {
    try {
      await _tts.awaitSpeakCompletion(true);
      if (!kIsWeb && defaultTargetPlatform == TargetPlatform.iOS) {
        await _tts.setIosAudioCategory(IosTextToSpeechAudioCategory.playback, [
          IosTextToSpeechAudioCategoryOptions.duckOthers,
        ], IosTextToSpeechAudioMode.spokenAudio);
      }
      if (defaultTargetPlatform == TargetPlatform.android &&
          settings.ttsEngine?.isNotEmpty == true) {
        await _tts.setEngine(settings.ttsEngine!);
      }
      final language = settings.ttsLanguage ?? 'de-DE';
      await _tts.setLanguage(language);
      if (settings.ttsVoice?.isNotEmpty == true) {
        await _tts.setVoice({'name': settings.ttsVoice!, 'locale': language});
      }
      await _tts.setSpeechRate(settings.ttsRate);
      await _tts.setPitch(settings.ttsPitch);
      if (!await stillRelevant()) return false;
      final result = await _tts
          .speak(text, focus: true)
          .timeout(const Duration(seconds: 45));
      return result == 1 || result == true;
    } catch (_) {
      await stop();
      return false;
    }
  }

  @override
  Future<void> stop() async {
    try {
      await _tts.stop();
    } catch (_) {
      /* Engine might have stopped already. */
    }
  }
}

class _SpeechItem {
  _SpeechItem({
    required this.key,
    this.stopKey,
    this.message,
    required this.priority,
    required this.source,
  });
  final String key;
  final String? stopKey, message;
  final int priority;
  final TrackingSource source;
  int attempts = 0, retryAt = 0;
}

/// The sole tracking fix consumer. This class is injectable for lifecycle tests.
class TrackingBackgroundRunner {
  TrackingBackgroundRunner({
    required this.store,
    required this.bridge,
    TrackingSpeech? speech,
    int Function()? nowMillis,
    bool enrichmentEnabled = true,
    RuntimeRouteGeometry? geometry,
    SevJourneyEnricher? sevEnricher,
  }) : _speech = speech ?? DeviceTrackingSpeech(),
       _now = nowMillis ?? _systemNow,
       _enrichmentEnabled = enrichmentEnabled,
       _providedGeometry = geometry,
       _providedSev = sevEnricher;
  final AppStore store;
  final NativeTripBridge bridge;
  final TrackingSpeech _speech;
  final int Function() _now;
  final bool _enrichmentEnabled;
  final RuntimeRouteGeometry? _providedGeometry;
  final SevJourneyEnricher? _providedSev;
  final TripTracker tracker = TripTracker();
  final Completer<void> _done = Completer();
  Future<void> get done => _done.future;
  NativeTripIdentity? _identity;
  NativeTripIdentity? get identity => _identity;
  Status? _status;
  List<Stop> _fullStops = const [], _checked = const [];
  AppSettings _settings = AppSettings();
  RuntimeRouteGeometry? _geometry;
  BahnhofSevRepository? _sevRepository;
  SevJourneyEnricher? _sev;
  final Map<String, SevMap> _sevMaps = {};
  StreamSubscription<Json>? _events;
  Timer? _tickTimer, _refreshTimer, _completionTimer;
  final Map<String, _SpeechItem> _speechQueue = {};
  final Map<String, Json> _retainedChanges = {};
  bool _speechBusy = false,
      _refreshing = false,
      _enriching = false,
      _disposed = false,
      _finishing = false;
  bool _enrichmentRequested = false;
  bool _gpsAvailable = true;
  bool _terminalPublished = false;
  int _routeRevision = 0;
  int _speechEpoch = 0;
  String? _speechError, _locationError, _clockId;

  Future<void> start(Json config, {bool timers = true}) async {
    try {
      await _start(config, timers: timers);
    } catch (_) {
      await dispose();
      rethrow;
    }
  }

  Future<void> _start(Json config, {bool timers = true}) async {
    if (_disposed ||
        config['running'] != true ||
        config['mode'] == 'recognition') {
      await dispose();
      return;
    }
    final identity = NativeTripIdentity.fromMap(config);
    _identity = identity;
    if (store.sessionRevision != identity.sessionRevision ||
        store.activeStatusId != identity.statusId) {
      await dispose();
      return;
    }
    final route = Map<String, dynamic>.from(config['route'] as Map);
    _routeRevision = route['journey'] is Map
        ? ((route['journey'] as Map)['contentRevision'] as int? ?? 0)
        : store.statusRevision(identity.statusId);
    if (_routeRevision != store.statusRevision(identity.statusId)) {
      await dispose();
      return;
    }
    final status = Status.fromJson(
      Map<String, dynamic>.from(route['status'] as Map),
    );
    final full = (route['providerFullStops'] as List)
        .whereType<Map>()
        .map((s) => Stop.fromJson(Map<String, dynamic>.from(s)))
        .toList(growable: false);
    final checked = status.checkin == null
        ? null
        : checkedInRoute(full, status.checkin!);
    if (status.id != identity.statusId ||
        status.user?.username != store.user?.username ||
        checked == null ||
        checked.length < 2) {
      await dispose();
      return;
    }
    _identity = identity;
    _status = status;
    _fullStops = full;
    _checked = checked;
    _settings = AppSettings(
      config['settings'] is Map
          ? Map<String, dynamic>.from(config['settings'] as Map)
          : store.settings.toJson(),
    );
    final runtime = config['runtime'];
    if (runtime is Map && runtime['sevMaps'] is Map) {
      for (final entry in (runtime['sevMaps'] as Map).entries) {
        if (entry.key is String && entry.value is Map) {
          try {
            _sevMaps[entry.key as String] = SevMap.fromJson(
              Map<String, dynamic>.from(entry.value as Map),
            );
          } catch (_) {
            /* Ignore invalid public map snapshots. */
          }
        }
      }
    }
    final journey = _journey(
      status,
      checked,
      identity.sessionRevision,
      _routeRevision,
      coordinates: _replacementCoordinates(),
    );
    _gpsAvailable = config['gpsAvailable'] != false;
    Json? restore = runtime is Map ? Map<String, dynamic>.from(runtime) : null;
    if (restore != null && _sevMaps.isNotEmpty && restore['progress'] is Map) {
      restore = {
        ...restore,
        'progress': {
          ...Map<String, dynamic>.from(restore['progress'] as Map),
          'arrivedAtCurrent': false,
        },
      };
    }
    if (restore == null ||
        !_compatibleCheckpoint(restore, journey) ||
        !tracker.restore(
          restore,
          expectedSessionRevision: identity.sessionRevision,
          expectedStatusId: identity.statusId,
          gpsEnabled: _settings.gpsTrackingEnabled,
          radiusMeters: _settings.announcementRadiusMeters,
        )) {
      tracker.start(
        journey,
        gpsEnabled: _settings.gpsTrackingEnabled,
        radiusMeters: _settings.announcementRadiusMeters,
      );
    } else {
      tracker.updateRoute(journey);
    }
    if (restore?['retainedChanges'] is List) {
      final keys = journey.stops.map((stop) => stop.key).toSet();
      for (final entry
          in (restore!['retainedChanges'] as List).whereType<Map>()) {
        if (entry['key'] is String &&
            entry['message'] is String &&
            keys.contains(entry['stopKey'])) {
          _retainedChanges[entry['key'] as String] = Map<String, dynamic>.from(
            entry,
          );
        }
      }
    }
    if (!_gpsAvailable) {
      tracker.invalidateLocation();
      if (_settings.gpsTrackingEnabled) {
        _locationError =
            'Standort nicht verfügbar. Die Anzeige verwendet den Fahrplan.';
      }
    }
    if (_enrichmentEnabled) {
      _geometry = _providedGeometry ?? RuntimeRouteGeometry(store);
      if (_providedSev == null) _sevRepository = BahnhofSevRepository();
      _sev = _providedSev ?? SevJourneyEnricher(_sevRepository!);
    }
    _events = bridge.events.listen(
      (event) => unawaited(handleEvent(event)),
      onError: (_) {
        _locationError = 'Der Standortdienst ist unterbrochen.';
        tracker.invalidateLocation();
        unawaited(tick());
      },
    );
    if (timers) {
      _tickTimer = Timer.periodic(
        const Duration(seconds: 10),
        (_) => unawaited(tick()),
      );
      _refreshTimer = Timer.periodic(
        const Duration(seconds: 55),
        (_) => unawaited(refresh()),
      );
    }
    await tick();
    if (timers) {
      unawaited(refresh());
      unawaited(_enrich());
    }
  }

  Future<bool> _current() async {
    final identity = _identity;
    if (_disposed || identity == null) return false;
    try {
      await store.synchronizeSession();
      if (store.sessionRevision != identity.sessionRevision ||
          store.activeStatusId != identity.statusId ||
          store.statusRevision(identity.statusId) != _routeRevision) {
        await dispose();
        return false;
      }
      if (_settings.gpsTrackingEnabled != store.settings.gpsTrackingEnabled) {
        tracker.setGpsEnabled(store.settings.gpsTrackingEnabled);
      }
      tracker.setRadiusMeters(store.settings.announcementRadiusMeters);
      if (_settings.ttsEnabled && !store.settings.ttsEnabled) {
        _speechEpoch++;
        for (final item in _speechQueue.values) {
          if (item.stopKey != null) tracker.releaseAnnouncement(item.stopKey!);
        }
        _speechQueue.clear();
        unawaited(_speech.stop());
      }
      _settings = store.settings;
      final config = await bridge.getConfiguration();
      if (config['running'] != true || !identity.matches(config)) {
        await dispose();
        return false;
      }
      return !_disposed;
    } catch (_) {
      return false;
    }
  }

  Future<void> handleEvent(Json event) async {
    if (_disposed || _identity == null) return;
    if (event['type'] == 'configuration') {
      final config = event['configuration'] is Map
          ? Map<String, dynamic>.from(event['configuration'] as Map)
          : event;
      if (!_identity!.matches(config) || config['running'] != true) {
        // Events can be delayed. Only the current native owner may retire this
        // runner; a queued stop from an older generation cannot close a new one.
        await _current();
      }
      return;
    }
    if (!_identity!.matches(event)) return;
    if (event['type'] == 'locationAvailability' || event['type'] == 'error') {
      if (event['available'] == false || event['type'] == 'error') {
        _gpsAvailable = false;
        _locationError = event['message'] is String
            ? event['message'] as String
            : 'Standort nicht verfügbar. Die Anzeige verwendet den Fahrplan.';
        tracker.invalidateLocation();
        await tick();
      } else {
        _locationError = null;
        _gpsAvailable = true;
      }
      return;
    }
    if (event['type'] != 'fix' || !await _current()) return;
    try {
      final fix = NativeLocationFix.fromMap(event);
      if (!_settings.gpsTrackingEnabled) return;
      if (_clockId != null && _clockId != fix.clockId) {
        tracker.resetNativeClock();
      }
      _clockId = fix.clockId;
      _gpsAvailable = true;
      _locationError = fix.accuracy > 100
          ? 'Der Standort ist zu ungenau. Die Anzeige verwendet den Fahrplan.'
          : null;
      _recheckSev();
      final state = tracker.observeNativeLocation(
        latitude: fix.latitude,
        longitude: fix.longitude,
        accuracyMeters: fix.accuracy,
        monotonicNanos: fix.elapsedRealtimeNanos,
        nowMonotonicNanos: (event['nowMonotonicNanos'] as num).toInt(),
        nowMillis: (event['nowMillis'] as num?)?.toInt() ?? _now(),
        hasSpeed: fix.speed != null,
        speedMetersPerSecond: fix.speed ?? 0,
      );
      if (state != null) await _evaluate(state);
    } catch (_) {
      _locationError = 'Ein Standortsignal konnte nicht ausgewertet werden.';
      tracker.invalidateLocation();
    }
  }

  Future<void> tick() async {
    if (!await _current()) return;
    _recheckSev();
    final state = tracker.update(nowMillis: _now());
    if (state != null) await _evaluate(state);
  }

  Future<void> refresh() async {
    if (_refreshing || !await _current()) return;
    _refreshing = true;
    final identity = _identity!;
    final contentRevision = store.contentRevision;
    try {
      final status = await store.status(identity.statusId);
      final full = await _loadProviderStops(store, status);
      if (!await _current() ||
          _identity != identity ||
          store.contentRevision != contentRevision) {
        return;
      }
      final checked = status.checkin == null
          ? null
          : checkedInRoute(full, status.checkin!);
      if (checked == null || checked.length < 2) return;
      _status = status;
      _fullStops = full;
      _checked = checked;
      tracker.updateRoute(
        _journey(
          status,
          checked,
          identity.sessionRevision,
          _routeRevision,
          coordinates: _replacementCoordinates(),
        ),
      );
      final retainedKeys = tracker.journey!.stops
          .map((stop) => stop.key)
          .toSet();
      _retainedChanges.removeWhere(
        (_, change) => !retainedKeys.contains(change['stopKey']),
      );
      final state = tracker.update(
        nowMillis: _now(),
        successfulApiSnapshot: tripChangeSnapshot(
          statusId: status.id,
          observedAtMillis: _now(),
          stops: checked,
          checkin: status.checkin!,
          nextIndex: tracker.snapshot?.progress.nextIndex ?? 0,
        ),
      );
      if (state != null) await _evaluate(state);
      unawaited(_enrich());
    } on ApiException catch (error) {
      if (error.statusCode == 401 ||
          error.statusCode == 403 ||
          error.statusCode == 404) {
        // Background never deletes credentials. Native cleanup is identity-bound.
        if (await _current()) {
          final result = await bridge.publish({
            ...identity.toMap(),
            'completed': true,
            'runtime': _checkpoint(),
            'error': error.message,
            'completionReason': 'unavailable',
          });
          _terminalPublished = result['accepted'] == true;
        }
        await dispose();
      }
    } on StaleRequestException {
      /* A newer route/request owns the result. */
    } catch (_) {
      /* Keep the last validated timetable. */
    } finally {
      _refreshing = false;
    }
  }

  Map<String, (double, double)> _replacementCoordinates() {
    final status = _status;
    if (status?.checkin == null) return const {};
    final infos = SevStopResolver.resolve(
      status!.checkin!,
      _fullStops,
      _sevMaps,
      _now(),
    );
    return {
      for (var i = 0; i < _checked.length; i++)
        if (infos[SevStopResolver.visitKey(_checked[i])]?.hasCoordinates ==
            true)
          trackingVisitKey(_checked[i], i): (
            infos[SevStopResolver.visitKey(_checked[i])]!.latitude!,
            infos[SevStopResolver.visitKey(_checked[i])]!.longitude!,
          ),
    };
  }

  void _recheckSev() {
    if (_status?.checkin == null || _identity == null || _sevMaps.isEmpty) {
      return;
    }
    final old = tracker.journey;
    final next = _journey(
      _status!,
      _checked,
      _identity!.sessionRevision,
      old?.contentRevision ?? store.contentRevision,
      coordinates: _replacementCoordinates(),
    );
    var changed = old?.stops.length != next.stops.length;
    if (!changed && old != null) {
      for (var i = 0; i < next.stops.length; i++) {
        if (old.stops[i].latitude != next.stops[i].latitude ||
            old.stops[i].longitude != next.stops[i].longitude) {
          changed = true;
          break;
        }
      }
    }
    if (changed) {
      tracker.invalidateLocation();
      tracker.updateSegmentGeometries(const []);
      tracker.updateRoute(next);
    }
  }

  Future<void> refreshEnrichment() => _enrich();
  Future<void> _enrich() async {
    if (!_enrichmentEnabled ||
        _disposed ||
        _identity == null ||
        _status?.checkin == null) {
      return;
    }
    if (_enriching) {
      _enrichmentRequested = true;
      return;
    }
    _enriching = true;
    final identity = _identity!,
        status = _status!,
        full = _fullStops,
        checked = _checked;
    final revision = tracker.journey!.contentRevision;
    final basis = trackingEnrichmentBasis(status, full);
    try {
      final maps = await _sev!.loadMaps(status.checkin!, full);
      if (!await _current() ||
          _identity != identity ||
          tracker.journey?.contentRevision != revision ||
          trackingEnrichmentBasis(_status!, _fullStops) != basis) {
        return;
      }
      _sevMaps.addAll(maps);
      _recheckSev();
      final stops = tracker.journey!.stops;
      final geometries = await _geometry!.load(
        statusId: status.id,
        checkin: status.checkin!,
        fullStops: full,
        checkedInStops: checked,
        trackingStops: stops,
        nextIndex: tracker.snapshot?.progress.nextIndex ?? 0,
        verifiedReplacementKeys: _replacementCoordinates().keys.toSet(),
      );
      if (!await _current() ||
          _identity != identity ||
          tracker.journey?.contentRevision != revision ||
          trackingEnrichmentBasis(_status!, _fullStops) != basis) {
        return;
      }
      tracker.updateSegmentGeometries(geometries);
      // Geometry completion is not another observation of a previously used fix.
      final state = tracker.update(nowMillis: _now());
      if (state != null) await _evaluate(state);
    } catch (_) {
      /* Public enrichment is optional and never invents positions. */
    } finally {
      _enriching = false;
      if (_enrichmentRequested && !_disposed) {
        _enrichmentRequested = false;
        unawaited(_enrich());
      }
    }
  }

  Future<void> _evaluate(TripTrackingSnapshot state) async {
    if (_disposed) return;
    for (final change in state.changes) {
      _retainedChanges.remove(change.key);
      _retainedChanges[change.key] = {
        'key': change.key,
        'stopKey': change.stopKey,
        'kind': change.kind.name,
        'title': change.title,
        'message': change.message,
      };
    }
    while (_retainedChanges.length > 8) {
      _retainedChanges.remove(_retainedChanges.keys.first);
    }
    final announcement = state.update.announcement;
    if (announcement != null) {
      if (_settings.ttsEnabled) {
        _speechQueue.putIfAbsent(
          'stop:${announcement.key}',
          () => _SpeechItem(
            key: 'stop:${announcement.key}',
            stopKey: announcement.key,
            priority: announcement.isDestination
                ? 100
                : announcement.isOrigin
                ? 75
                : 50,
            source: state.update.source,
          ),
        );
      } else {
        tracker.releaseAnnouncement(announcement.key);
      }
    }
    if (_settings.ttsEnabled &&
        _settings.tripChangeAlertsEnabled &&
        _settings.tripChangeSpeechEnabled) {
      for (final change in state.changes) {
        _speechQueue.putIfAbsent(
          'change:${change.key}',
          () => _SpeechItem(
            key: 'change:${change.key}',
            message: change.message,
            priority: 10,
            source: state.update.source,
          ),
        );
      }
    }
    while (_speechQueue.length > 16) {
      final key = _speechQueue.keys.first;
      final dropped = _speechQueue.remove(key)!;
      if (dropped.stopKey != null) {
        tracker.releaseAnnouncement(dropped.stopKey!);
      }
    }
    if (!state.progress.completed && _completionTimer != null) {
      _completionTimer!.cancel();
      _completionTimer = null;
    }
    if (state.progress.completed && _completionTimer == null) {
      _completionTimer = Timer(
        const Duration(seconds: 15),
        () => unawaited(_finishCompleted()),
      );
    }
    await _publish(state);
    unawaited(_drainSpeech());
    await _maybeComplete();
  }

  Future<void> _publish(
    TripTrackingSnapshot state, {
    bool completed = false,
  }) async {
    if (!await _current() || _identity == null) return;
    state = _displayState(state);
    final stop = state.update.stop, eta = state.eta;
    final runtime = _checkpoint();
    final model = state.progressModel;
    final progress = model.progress == null
        ? 0.0
        : (model.progress! / model.progressMax).clamp(0.0, 1.0);
    final result = await bridge.publish({
      ...state.toJson(),
      ..._identity!.toMap(),
      'completed': completed,
      'changes': _retainedChanges.values.toList(growable: false),
      'changeAlerts': _settings.tripChangeAlertsEnabled
          ? _retainedChanges.values.toList(growable: false)
          : const [],
      'line': state.journey.lineName,
      'nextStop': stop?.name ?? state.journey.destinationName,
      'destination': state.journey.destinationName,
      'timeLabel': eta == null ? 'Zeit nicht verfügbar' : _time(eta.millis),
      'timeSource': eta?.sourceLabel ?? 'Fahrplan',
      'platform': state.journey.replacementBus
          ? null
          : stop?.isOrigin == true
          ? stop?.departurePlatform
          : stop?.arrivalPlatform,
      'delayMinutes': eta?.delayMinutes,
      'progress': progress,
      'runtime': runtime,
      'locationError': _locationError,
      'speechError': _speechError,
    });
    if (completed) _terminalPublished = result['accepted'] == true;
  }

  TripTrackingSnapshot _displayState(TripTrackingSnapshot state) {
    if (!state.journey.replacementBus) return state;
    final verified = _replacementCoordinates().keys.toSet();
    final start = (state.progress.nextIndex - 1).clamp(
      0,
      state.journey.stops.length,
    );
    if (state.journey.stops
        .skip(start)
        .where((stop) => !stop.cancelled)
        .every((stop) => verified.contains(stop.key))) {
      return state;
    }
    final original = state.gpsTimes;
    final observed = original == null
        ? null
        : GpsJourneyTimes(
            updatedAtMillis: original.updatedAtMillis,
            validUntilMillis: original.validUntilMillis,
            stopTimes: original.stopTimes
                .map(
                  (time) => GpsStopTime(
                    stopKey: time.stopKey,
                    stationId: time.stationId,
                    plannedArrivalMillis: time.plannedArrivalMillis,
                    plannedDepartureMillis: time.plannedDepartureMillis,
                    arrivalMillis: time.arrivalObserved
                        ? time.arrivalMillis
                        : null,
                    departureMillis: time.departureObserved
                        ? time.departureMillis
                        : null,
                    arrivalObserved: time.arrivalObserved,
                    departureObserved: time.departureObserved,
                  ),
                )
                .toList(growable: false),
          );
    final stop = state.update.stop;
    final manual = stop?.isOrigin == true
        ? state.journey.manualDepartureMillis
        : stop?.isDestination == true
        ? state.journey.manualArrivalMillis
        : null;
    final manualTime = manual == null
        ? null
        : DateTime.fromMillisecondsSinceEpoch(
            manual,
            isUtc: true,
          ).toIso8601String();
    final eta = stop == null
        ? null
        : stop.isOrigin
        ? JourneyTimeResolver.departure(
            stop,
            observed,
            _now(),
            manualTime: manualTime,
          )
        : JourneyTimeResolver.arrival(
            stop,
            observed,
            _now(),
            manualTime: manualTime,
          );
    return TripTrackingSnapshot(
      journey: state.journey,
      update: state.update,
      progress: state.progress,
      gpsTimes: observed,
      eta: eta,
      etaReason: GpsTimeUnavailableReason.replacementStopUnconfirmed,
      geometrySource: null,
      reacquiring: state.reacquiring,
      changes: state.changes,
      evaluatedAtMillis: state.evaluatedAtMillis,
    );
  }

  Json? _checkpoint() {
    final value = tracker.checkpoint();
    if (value == null) return null;
    final progress = Map<String, dynamic>.from(value['progress'] as Map);
    final pendingKeys = _speechQueue.values
        .map((item) => item.stopKey)
        .whereType<String>()
        .toSet();
    progress['announcedKeys'] = (progress['announcedKeys'] as List)
        .where((key) => !pendingKeys.contains(key))
        .toList(growable: false);
    return {
      ...value,
      'progress': progress,
      'sevMaps': {
        for (final entry in _sevMaps.entries) entry.key: entry.value.toJson(),
      },
      'retainedChanges': _retainedChanges.values.toList(growable: false),
    };
  }

  TrackingStop? _speechStop(_SpeechItem item) {
    final stops = tracker.journey?.stops
        .where((stop) => stop.key == item.stopKey)
        .toList();
    return stops?.length == 1 ? stops!.single : null;
  }

  Future<bool> _speechRelevant(_SpeechItem item, int epoch) async {
    if (_disposed ||
        epoch != _speechEpoch ||
        !_settings.ttsEnabled ||
        !await _current()) {
      return false;
    }
    if (item.stopKey == null) {
      return _settings.tripChangeAlertsEnabled &&
          _settings.tripChangeSpeechEnabled;
    }
    final stop = _speechStop(item), state = tracker.snapshot;
    if (stop == null || stop.cancelled || state == null) return false;
    final index = state.journey.stops.indexWhere(
      (value) => value.key == stop.key,
    );
    if (index != state.progress.nextIndex || state.reacquiring) return false;
    if (stop.isOrigin) {
      return tracker.isOriginAnnouncementRelevant(
        stop.key,
        item.source,
        _now(),
      );
    }
    return true;
  }

  String _speechText(_SpeechItem item) {
    if (item.message != null) return item.message!;
    final stop = _speechStop(item)!, state = tracker.snapshot!;
    final platform = state.journey.replacementBus
        ? null
        : stop.isOrigin
        ? stop.departurePlatform
        : stop.arrivalPlatform;
    final platformText = platform == null ? '' : ' auf Gleis $platform';
    final approximate = state.update.source == TrackingSource.timetable
        ? 'Voraussichtlich '
        : '';
    if (stop.isOrigin) {
      final departure = stop.effectiveDepartureMillis;
      return 'Deine Fahrt mit der Linie ${state.journey.lineName} startet in Kürze in ${stop.name}. ${approximate}Abfahrt${departure == null ? '' : ' um ${_time(departure)}'}$platformText. Bitte mach dich zum Einsteigen bereit.';
    }
    return stop.isDestination
        ? '${approximate}Du erreichst in Kürze deine Ausstiegshaltestelle ${stop.name}$platformText.'
        : '${approximate}Nächste Haltestelle in Kürze: ${stop.name}$platformText.';
  }

  Future<void> _drainSpeech() async {
    if (_speechBusy || _disposed) return;
    final candidates =
        _speechQueue.values.where((item) => item.retryAt <= _now()).toList()
          ..sort((a, b) => b.priority.compareTo(a.priority));
    if (candidates.isEmpty) return;
    final item = candidates.first;
    _speechBusy = true;
    final epoch = _speechEpoch;
    try {
      if (!await _speechRelevant(item, epoch)) {
        _speechQueue.remove(item.key);
        if (item.stopKey != null) tracker.releaseAnnouncement(item.stopKey!);
        return;
      }
      final success = await _speech.speak(
        _speechText(item),
        _settings,
        stillRelevant: () => _speechRelevant(item, epoch),
      );
      if (_disposed || epoch != _speechEpoch) return;
      final relevant = await _speechRelevant(item, epoch);
      if (!relevant) {
        _speechQueue.remove(item.key);
        if (item.stopKey != null) tracker.releaseAnnouncement(item.stopKey!);
      } else if (success) {
        if (item.stopKey != null) {
          tracker.acknowledgeAnnouncement(item.stopKey!);
        }
        _speechQueue.remove(item.key);
        _speechError = null;
      } else {
        _speechError =
            'Die Ansage konnte nicht wiedergegeben werden. Prüfe die Sprachausgabe.';
        if (item.stopKey != null) tracker.releaseAnnouncement(item.stopKey!);
        item.attempts++;
        item.retryAt = _now() + 15000;
        if (item.attempts >= 2) _speechQueue.remove(item.key);
      }
    } finally {
      _speechBusy = false;
      if (!_disposed) {
        final state = tracker.snapshot;
        if (state != null) await _publish(state);
        await _maybeComplete();
        unawaited(_drainSpeech());
      }
    }
  }

  Future<void> _maybeComplete() async {
    final state = tracker.snapshot;
    if (_disposed ||
        _finishing ||
        state?.progress.completed != true ||
        _speechBusy ||
        _speechQueue.isNotEmpty) {
      return;
    }
    await _finishCompleted();
  }

  Future<void> _finishCompleted() async {
    final state = tracker.snapshot;
    if (_disposed || _finishing || state?.progress.completed != true) return;
    _finishing = true;
    _speechEpoch++;
    _speechQueue.clear();
    try {
      await _speech.stop().timeout(const Duration(seconds: 3));
    } catch (_) {
      /* Native engine teardown also stops speech. */
    }
    await _publish(state!, completed: true);
    await dispose();
  }

  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    _speechEpoch++;
    _tickTimer?.cancel();
    _refreshTimer?.cancel();
    _completionTimer?.cancel();
    await _events?.cancel();
    _sev?.cancel();
    _sevRepository?.close();
    _geometry?.close();
    _speechQueue.clear();
    try {
      await _speech.stop().timeout(const Duration(seconds: 3));
    } catch (_) {
      // Native engine teardown stops any remaining speech.
    }
    if (!_terminalPublished && _identity != null) {
      _terminalPublished = await _publishOwnedTerminal(
        bridge,
        _identity!.toMap(),
      );
    }
    tracker.stop();
    if (!_done.isCompleted) _done.complete();
  }

  static int _systemNow() => DateTime.now().millisecondsSinceEpoch;
}

String _time(int millis) {
  final time = DateTime.fromMillisecondsSinceEpoch(millis);
  return '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
}

/// Matches the native permission snapshot. Denial keeps timetable tracking
/// available; only real, accurate location samples may establish GPS progress.
String? trackingLocationPermissionMessage(
  Json permissions, {
  required bool gpsEnabled,
}) {
  if (!gpsEnabled) return null;
  if (permissions['location'] != true) {
    return 'Kein Standortzugriff. Die Fahrtbegleitung verwendet den Fahrplan.';
  }
  if (permissions['locationServicesEnabled'] != true) {
    return 'Der Standortdienst ist ausgeschaltet. Die Fahrtbegleitung verwendet den Fahrplan.';
  }
  if (permissions['precise'] == false) {
    return 'Nur ungefährer Standort freigegeben. GPS-Fortschritt und Prognosen benötigen ein ausreichend genaues Signal.';
  }
  return null;
}

/// A provider refresh may change realtime or body without changing the public
/// physical route on which an in-flight geometry/SEV job is based.
String trackingEnrichmentBasis(Status status, List<Stop> fullStops) {
  final c = status.checkin;
  Json visit(Stop? stop) => {
    'uuid': stop?.uuid,
    'stationId': stop?.stationId,
    'stationUuid': stop?.station?.uuid,
    'name': stop?.stationName,
    'arrival': parseMillis(stop?.arrivalPlanned),
    'departure': parseMillis(stop?.departurePlanned),
    'latitude': stop?.station?.latitude,
    'longitude': stop?.station?.longitude,
    'cancelled': stop?.cancelled,
  };
  return jsonEncode({
    'statusId': status.id,
    'trip': c?.trip,
    'tripUuid': c?.tripUuid,
    'hafasId': c?.hafasId,
    'category': c?.category,
    'mode': c?.mode,
    'lineName': c?.lineName,
    'origin': visit(c?.origin),
    'destination': visit(c?.destination),
    'visits': fullStops.map(visit).toList(growable: false),
  });
}
