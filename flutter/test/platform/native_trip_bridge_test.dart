import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:routely/platform/native_trip_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('routely/tracking');
  final calls = <MethodCall>[];
  setUp(() {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return {'accepted': true};
        });
  });
  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
  test(
    'stop carries account, route and generation even for identical status IDs',
    () async {
      const old = NativeTripIdentity(
        sessionRevision: 'old',
        statusId: 42,
        generation: 7,
      );
      const current = NativeTripIdentity(
        sessionRevision: 'new',
        statusId: 42,
        generation: 8,
      );
      expect(current.matches(old.toMap()), isFalse);
      await NativeTripBridge().stopTracking(current, reason: 'logout');
      expect(calls.single.method, 'stopTracking');
      expect(calls.single.arguments, {...current.toMap(), 'reason': 'logout'});
    },
  );
  test(
    'one-shot legacy credentials are deleted only after Dart commits the import',
    () async {
      final bridge = NativeTripBridge();
      await bridge.legacyImport();
      expect(calls.map((call) => call.method), ['legacyImport']);
      await bridge.commitLegacyImport('import-1');
      expect(calls.last.arguments, {'importId': 'import-1'});
    },
  );
  test(
    'default bridges share one subscription with independent observers',
    () async {
      const eventsChannel = MethodChannel('routely/tracking_events');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final eventCalls = <String>[];
      messenger.setMockMethodCallHandler(eventsChannel, (call) async {
        eventCalls.add(call.method);
        return null;
      });
      final trackingEvents = <Map<String, dynamic>>[];
      final recognitionEvents = <Map<String, dynamic>>[];
      final tracking = NativeTripBridge().events.listen(trackingEvents.add);
      final recognition = NativeTripBridge().events.listen(
        recognitionEvents.add,
      );
      try {
        await Future<void>.delayed(Duration.zero);
        expect(eventCalls, ['listen']);
        const first = {'type': 'snapshot', 'statusId': 42};
        await messenger.handlePlatformMessage(
          eventsChannel.name,
          const StandardMethodCodec().encodeSuccessEnvelope(first),
          null,
        );
        expect(trackingEvents, [first]);
        expect(recognitionEvents, [first]);
        await tracking.cancel();
        expect(eventCalls, ['listen']);
        const second = {'type': 'openStatus', 'statusId': 42};
        await messenger.handlePlatformMessage(
          eventsChannel.name,
          const StandardMethodCodec().encodeSuccessEnvelope(second),
          null,
        );
        expect(trackingEvents, [first]);
        expect(recognitionEvents, [first, second]);
        await recognition.cancel();
        expect(eventCalls, ['listen', 'cancel']);
      } finally {
        await tracking.cancel();
        await recognition.cancel();
        messenger.setMockMethodCallHandler(eventsChannel, null);
      }
    },
  );
  test(
    'injected event channels remain isolated from the default stream',
    () async {
      const injected = EventChannel('routely/test_events');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      for (final name in ['routely/tracking_events', injected.name]) {
        messenger.setMockMethodCallHandler(
          MethodChannel(name),
          (_) async => null,
        );
      }
      final defaultEvents = <Map<String, dynamic>>[];
      final injectedEvents = <Map<String, dynamic>>[];
      final first = NativeTripBridge().events.listen(defaultEvents.add);
      final second = NativeTripBridge(
        events: injected,
      ).events.listen(injectedEvents.add);
      try {
        await Future<void>.delayed(Duration.zero);
        const event = {'type': 'snapshot', 'statusId': 7};
        await messenger.handlePlatformMessage(
          injected.name,
          const StandardMethodCodec().encodeSuccessEnvelope(event),
          null,
        );
        expect(defaultEvents, isEmpty);
        expect(injectedEvents, [event]);
      } finally {
        await first.cancel();
        await second.cancel();
        for (final name in ['routely/tracking_events', injected.name]) {
          messenger.setMockMethodCallHandler(MethodChannel(name), null);
        }
      }
    },
  );
  test('location preserves independent UTC and monotonic time bases', () {
    final fix = NativeLocationFix.fromMap({
      'latitude': 50.1,
      'longitude': 6.1,
      'accuracy': 18,
      'timeMillis': 1700000000000,
      'elapsedRealtimeNanos': 222000000,
      'clockId': 'android-boot-1',
      'speed': 8.2,
    });
    expect(fix.timeMillis, 1700000000000);
    expect(fix.elapsedRealtimeNanos, 222000000);
    expect(fix.clockId, 'android-boot-1');
    expect(fix.speed, 8.2);
  });
  test(
    'desktop does not advertise an unavailable background service',
    () async {
      debugDefaultTargetPlatformOverride = TargetPlatform.linux;
      final bridge = NativeTripBridge();
      expect(bridge.supported, isFalse);
      expect(await bridge.getConfiguration(), {
        'supported': false,
        'running': false,
      });
      expect(calls, isEmpty);
    },
  );
}
