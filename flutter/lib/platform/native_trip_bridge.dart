import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// The identity is opaque to the operating system. A generation must increase
/// whenever a session, route or content revision changes.
class NativeTripIdentity {
  const NativeTripIdentity({
    required this.sessionRevision,
    required this.statusId,
    required this.generation,
  });
  final String sessionRevision;
  final int statusId;
  final int generation;
  factory NativeTripIdentity.fromMap(Map<String, dynamic> map) =>
      NativeTripIdentity(
        sessionRevision: map['sessionRevision'] as String,
        statusId: (map['statusId'] as num).toInt(),
        generation: (map['generation'] as num).toInt(),
      );
  Map<String, dynamic> toMap() => {
    'sessionRevision': sessionRevision,
    'statusId': statusId,
    'generation': generation,
  };
  bool matches(Map<String, dynamic> map) =>
      map['sessionRevision'] == sessionRevision &&
      map['statusId'] == statusId &&
      map['generation'] == generation;
}

class NativeLocationFix {
  const NativeLocationFix({
    required this.latitude,
    required this.longitude,
    required this.accuracy,
    required this.timeMillis,
    required this.elapsedRealtimeNanos,
    required this.clockId,
    this.speed,
    this.heading,
  });
  final double latitude, longitude, accuracy;
  final double? speed, heading;
  final int timeMillis, elapsedRealtimeNanos;
  final String clockId;
  factory NativeLocationFix.fromMap(Map<String, dynamic> map) =>
      NativeLocationFix(
        latitude: (map['latitude'] as num).toDouble(),
        longitude: (map['longitude'] as num).toDouble(),
        accuracy: (map['accuracy'] as num).toDouble(),
        speed: (map['speed'] as num?)?.toDouble(),
        heading: (map['heading'] as num?)?.toDouble(),
        timeMillis: (map['timeMillis'] as num).toInt(),
        elapsedRealtimeNanos: (map['elapsedRealtimeNanos'] as num).toInt(),
        clockId: map['clockId'] as String,
      );
}

/// Native Android/iOS lifecycle. The headless engine alone receives `fix`
/// events; the visible engine observes already evaluated snapshots.
class NativeTripBridge {
  NativeTripBridge({MethodChannel? channel, EventChannel? events})
    : _channel = channel ?? const MethodChannel('routely/tracking'),
      _events = events ?? const EventChannel('routely/tracking_events'),
      _usesDefaultEvents = events == null;
  final MethodChannel _channel;
  final EventChannel _events;
  final bool _usesDefaultEvents;
  // EventChannel owns one binary-message handler per engine. Sharing the
  // broadcast stream lets tracking and recognition observe it independently.
  // Static Dart state belongs to this isolate, so the headless engine still
  // has its own native subscription and cannot receive UI snapshots.
  static Stream<Map<String, dynamic>>? _defaultStream;
  Stream<Map<String, dynamic>>? _stream;
  bool get supported =>
      !kIsWeb &&
      (defaultTargetPlatform == TargetPlatform.android ||
          defaultTargetPlatform == TargetPlatform.iOS);
  Stream<Map<String, dynamic>> get events => _stream ??= _usesDefaultEvents
      ? _defaultStream ??= _eventStream(_events)
      : _eventStream(_events);
  static Stream<Map<String, dynamic>> _eventStream(EventChannel events) =>
      events.receiveBroadcastStream().map(
        (event) => Map<String, dynamic>.from(event as Map),
      );
  Future<Map<String, dynamic>> _map(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    if (!supported) return {'supported': false, 'running': false};
    final result = await _channel.invokeMapMethod<String, dynamic>(
      method,
      arguments,
    );
    return result ?? <String, dynamic>{};
  }

  Future<Map<String, dynamic>> getConfiguration() => _map('getConfiguration');
  Future<Map<String, dynamic>> getSnapshot() => _map('getSnapshot');
  Future<Map<String, dynamic>> requestPermissions({bool background = false}) =>
      _map('requestPermissions', {'background': background});
  Future<Map<String, dynamic>> requestLocation() => _map('requestLocation');
  Future<Map<String, dynamic>> startTracking(Map<String, dynamic> payload) =>
      _map('startTracking', payload);
  Future<Map<String, dynamic>> startRecognition(Map<String, dynamic> payload) =>
      _map('startRecognition', {
        ...payload,
        'mode': 'recognition',
        'statusId': 0,
      });
  Future<Map<String, dynamic>> stopTracking(
    NativeTripIdentity identity, {
    String reason = 'manual',
  }) => _map('stopTracking', {...identity.toMap(), 'reason': reason});
  Future<Map<String, dynamic>> publish(Map<String, dynamic> snapshot) =>
      _map('publish', snapshot);
  Future<Map<String, dynamic>> batteryStatus() => _map('batteryStatus');
  Future<Map<String, dynamic>> requestBatteryExemption() =>
      _map('requestBatteryExemption');
  Future<Map<String, dynamic>> openNotificationSettings() =>
      _map('openNotificationSettings');
  Future<Map<String, dynamic>> legacyImport() => _map('legacyImport');
  Future<Map<String, dynamic>> commitLegacyImport(String importId) =>
      _map('commitLegacyImport', {'importId': importId});
}
