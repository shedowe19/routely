import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:routely/data/app_store.dart';
import 'package:routely/features/settings/settings_screen.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'memory_feed_cache.dart';
import 'widget_http_client.dart';

class SettingsSecure implements SecureSessionStorage {
  String? value;
  @override
  Future<String?> read() async => value;
  @override
  Future<void> write(String? next) async => value = next;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const speech = MethodChannel('flutter_tts');
  const native = MethodChannel('routely/tracking');
  late AppStore store;
  Future<Object?> Function(MethodCall)? speechHandler;
  final calls = <String>[];
  // Keep store and HTTP futures in the widget test's FakeAsync zone.
  Future<void> initializeStore() async {
    SharedPreferences.setMockInitialValues({});
    store = AppStore(
      secureStorage: SettingsSecure(),
      feedCache: MemoryFeedCache(),
      preferences: await SharedPreferences.getInstance(),
      client: widgetHttpClient(
        (_) async => http.Response('{"data":{"id":1,"username":"test"}}', 200),
      ),
    );
    await store.login('https://example.test', 'test-only');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(speech, (call) async {
          calls.add(call.method);
          if ([
            'getLanguages',
            'getVoices',
            'getEngines',
          ].contains(call.method)) {
            return <Object>[];
          }
          if (speechHandler != null) return speechHandler!(call);
          return 1;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(native, (_) async => {'supported': true});
  }

  tearDown(() {
    store.dispose();
    calls.clear();
    speechHandler = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(speech, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(native, null);
  });
  Widget screen(Future<void> Function() logout) => MaterialApp(
    home: SettingsScreen(
      store: store,
      onLogout: logout,
      onSettingsChanged: () async {},
    ),
  );
  Future<void> confirmLogout(WidgetTester tester) async {
    await tester.scrollUntilVisible(
      find.widgetWithText(OutlinedButton, 'Abmelden'),
      300,
      scrollable: find
          .descendant(
            of: find.byType(ListView),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await tester.ensureVisible(find.widgetWithText(OutlinedButton, 'Abmelden'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, 'Abmelden'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Abmelden'));
    await tester.pump();
  }

  testWidgets(
    'late preview initialization cannot speak after screen disposal',
    (tester) async {
      await initializeStore();
      final rate = Completer<Object?>();
      speechHandler = (call) =>
          call.method == 'setSpeechRate' ? rate.future : Future.value(1);
      await tester.pumpWidget(screen(() async {}));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Stimme testen'),
        300,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.ensureVisible(find.text('Stimme testen'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Stimme testen'));
      await tester.pump();
      expect(calls, contains('setSpeechRate'));
      await tester.pumpWidget(const SizedBox.shrink());
      rate.complete(1);
      await tester.pumpAndSettle();
      expect(calls, contains('stop'));
      expect(calls, isNot(contains('speak')));
      expect(tester.takeException(), isNull);
    },
    variant: const TargetPlatformVariant({TargetPlatform.android}),
  );

  testWidgets('speech stop rejection never blocks credential removal', (
    tester,
  ) async {
    await initializeStore();
    var loggedOut = 0;
    speechHandler = (call) async {
      if (call.method == 'stop') throw PlatformException(code: 'unavailable');
      return 1;
    };
    await tester.pumpWidget(
      screen(() async {
        loggedOut++;
        await store.logout();
      }),
    );
    await tester.pumpAndSettle();
    await confirmLogout(tester);
    await tester.pump();
    await tester.pumpAndSettle();
    expect(loggedOut, 1);
    expect(store.authenticated, isFalse);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  }, variant: const TargetPlatformVariant({TargetPlatform.android}));

  testWidgets(
    'nonreturning speech stop is bounded and logout stays disabled while waiting',
    (tester) async {
      await initializeStore();
      var loggedOut = 0;
      final stopped = Completer<Object?>();
      speechHandler = (call) =>
          call.method == 'stop' ? stopped.future : Future.value(1);
      await tester.pumpWidget(
        screen(() async {
          loggedOut++;
        }),
      );
      await tester.pumpAndSettle();
      await confirmLogout(tester);
      expect(loggedOut, 0);
      final logout = tester.widget<OutlinedButton>(
        find.widgetWithText(OutlinedButton, 'Abmelden'),
      );
      expect(logout.onPressed, isNull);
      await tester.pump(const Duration(seconds: 4));
      await tester.pumpAndSettle();
      expect(loggedOut, 1);
      stopped.complete(1);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
    variant: const TargetPlatformVariant({TargetPlatform.android}),
  );
  testWidgets(
    'account replacement during speech stop cancels the old logout action',
    (tester) async {
      await initializeStore();
      var loggedOut = 0;
      final stopped = Completer<Object?>();
      speechHandler = (call) =>
          call.method == 'stop' ? stopped.future : Future.value(1);
      await tester.pumpWidget(
        screen(() async {
          loggedOut++;
        }),
      );
      await tester.pumpAndSettle();
      await confirmLogout(tester);
      final old = store.sessionRevision;
      await store.login('https://example.test', 'new-test-only');
      stopped.complete(1);
      await tester.pumpAndSettle();
      expect(store.sessionRevision, isNot(old));
      expect(loggedOut, 0);
      expect(store.authenticated, isTrue);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
    variant: const TargetPlatformVariant({TargetPlatform.android}),
  );
}
