import 'dart:async';

import 'package:geolocator/geolocator.dart';

import '../data/app_store.dart';
import '../data/models.dart';
import '../platform/native_trip_bridge.dart';
import 'tracking_runtime.dart';

/// Desktop/browser host. Tracking exists only while the visible engine is alive;
/// its snapshots and public checkpoint remain in RAM, never a background promise.
class ForegroundTripHost extends NativeTripBridge {
  ForegroundTripHost(this.store);
  final AppStore store;
  final StreamController<Json> _events = StreamController.broadcast();
  Json _configuration = {'running': false, 'foregroundOnly': true};
  Json _snapshot = {};
  StreamSubscription<Position>? _positions;
  TrackingBackgroundRunner? _runner;
  Stopwatch? _clock;
  bool _disposed = false;
  @override
  bool get supported => true;
  @override
  Stream<Json> get events => _events.stream;
  @override
  Future<Json> getConfiguration() async => Map.of(_configuration);
  @override
  Future<Json> getSnapshot() async => Map.of(_snapshot);

  @override
  Future<Json> requestPermissions({bool background = false}) async {
    try {
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      final allowed =
          permission == LocationPermission.whileInUse ||
          permission == LocationPermission.always;
      bool? precise;
      if (allowed) {
        try {
          final accuracy = await Geolocator.getLocationAccuracy();
          if (accuracy == LocationAccuracyStatus.precise) precise = true;
          if (accuracy == LocationAccuracyStatus.reduced) precise = false;
        } catch (_) {
          // Some desktop/browser providers do not expose precision permission.
        }
      }
      return {
        'foregroundOnly': true,
        'location': allowed,
        'precise': precise,
        'background': false,
        'locationServicesEnabled': await Geolocator.isLocationServiceEnabled(),
      };
    } catch (_) {
      return {
        'foregroundOnly': true,
        'location': false,
        'precise': false,
        'background': false,
        'locationServicesEnabled': false,
        'message': 'Auf diesem Gerät ist kein Standortdienst verfügbar.',
      };
    }
  }

  @override
  Future<Json> startTracking(Json payload) async {
    if (_disposed) return {'accepted': false, 'running': false};
    await _positions?.cancel();
    await _runner?.dispose();
    _configuration = {
      ...payload,
      'running': true,
      'foregroundOnly': true,
      'gpsAvailable': false,
    };
    _snapshot = {};
    _clock = Stopwatch()..start();
    final identity = NativeTripIdentity.fromMap(payload);
    final settings = AppSettings(
      payload['settings'] is Map
          ? Map<String, dynamic>.from(payload['settings'] as Map)
          : store.settings.toJson(),
    );
    if (settings.gpsTrackingEnabled) {
      try {
        final permission = await Geolocator.checkPermission();
        final enabled = await Geolocator.isLocationServiceEnabled();
        if (enabled &&
            (permission == LocationPermission.whileInUse ||
                permission == LocationPermission.always)) {
          _configuration['gpsAvailable'] = true;
          _positions =
              Geolocator.getPositionStream(
                locationSettings: const LocationSettings(
                  accuracy: LocationAccuracy.high,
                  distanceFilter: 0,
                ),
              ).listen(
                (position) {
                  if (_disposed ||
                      _configuration['running'] != true ||
                      !identity.matches(_configuration)) {
                    return;
                  }
                  final now = DateTime.now().millisecondsSinceEpoch;
                  final nanos = _clock!.elapsedMicroseconds * 1000;
                  final age = now - position.timestamp.millisecondsSinceEpoch;
                  final captured = nanos - age * 1000000;
                  if (age < 0 || captured <= 0) return;
                  _events.add({
                    ...identity.toMap(),
                    'type': 'fix',
                    'latitude': position.latitude,
                    'longitude': position.longitude,
                    'accuracy': position.accuracy,
                    'speed': position.speed >= 0 ? position.speed : null,
                    'heading': position.heading,
                    'timeMillis': position.timestamp.millisecondsSinceEpoch,
                    'elapsedRealtimeNanos': captured,
                    'nowMonotonicNanos': nanos,
                    'nowMillis': now,
                    'clockId': 'foreground:${identity.generation}',
                  });
                },
                onError: (_) {
                  if (!_disposed && identity.matches(_configuration)) {
                    _configuration['gpsAvailable'] = false;
                    _events.add({
                      ...identity.toMap(),
                      'type': 'locationAvailability',
                      'available': false,
                      'message':
                          'Standort nicht verfügbar. Die Anzeige verwendet den Fahrplan.',
                    });
                  }
                },
              );
        }
      } catch (_) {
        /* Public API/timetable tracking remains usable without GPS. */
      }
    }
    _events.add({'type': 'configuration', ..._configuration});
    _runner = TrackingBackgroundRunner(store: store, bridge: this);
    await _runner!.start(_configuration);
    return {
      'accepted': true,
      'running': true,
      'foregroundOnly': true,
      'gpsAvailable': _configuration['gpsAvailable'],
    };
  }

  @override
  Future<Json> publish(Json snapshot) async {
    if (_disposed || _configuration['running'] != true) {
      return {'accepted': false};
    }
    final identity = NativeTripIdentity.fromMap(_configuration);
    if (!identity.matches(snapshot)) return {'accepted': false};
    _snapshot = {...snapshot, 'foregroundOnly': true};
    if (snapshot['runtime'] is Map) {
      _configuration['runtime'] = snapshot['runtime'];
    }
    _events.add({'type': 'snapshot', 'snapshot': _snapshot});
    if (snapshot['completed'] == true) {
      _configuration['running'] = false;
      await _positions?.cancel();
      _positions = null;
      _events.add({'type': 'configuration', ..._configuration});
    }
    return {'accepted': true};
  }

  @override
  Future<Json> stopTracking(
    NativeTripIdentity identity, {
    String reason = 'manual',
  }) async {
    if (!identity.matches(_configuration)) {
      return {'accepted': false, 'running': _configuration['running']};
    }
    await pause();
    _configuration = {
      ...identity.toMap(),
      'running': false,
      'foregroundOnly': true,
    };
    return {'accepted': true, 'running': false};
  }

  Future<void> pause() async {
    _configuration['running'] = false;
    await _positions?.cancel();
    _positions = null;
    await _runner?.dispose();
    _runner = null;
    if (!_disposed) _events.add({'type': 'configuration', ..._configuration});
  }

  Future<void> close() async {
    if (_disposed) return;
    await pause();
    _disposed = true;
    await _events.close();
  }
}
