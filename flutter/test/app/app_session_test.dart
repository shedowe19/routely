import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:routely/app.dart';
import 'package:routely/data/app_store.dart';
import 'package:routely/features/setup/setup_screen.dart';
import 'package:routely/features/settings/settings_screen.dart';
import 'package:routely/features/detail/status_detail_screen.dart';
import 'package:routely/features/notifications/notifications_screen.dart';
import 'package:routely/features/profile/profile_screen.dart';
import 'package:routely/features/users/user_profile_screen.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'memory_feed_cache.dart';
import 'widget_http_client.dart';

class AppSecure implements SecureSessionStorage {
  String? value;
  @override
  Future<String?> read() async => value;
  @override
  Future<void> write(String? next) async => value = next;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const native = MethodChannel('routely/tracking');
  const events = MethodChannel('routely/tracking_events');
  const speech = MethodChannel('flutter_tts');
  late AppStore store;
  Future<Object?> Function(MethodCall)? nativeHandler;
  var rejectAuth = false;
  final nativeCalls = <String>[];
  // Keep store and HTTP futures in the widget test's FakeAsync zone.
  Future<void> initializeStore() async {
    SharedPreferences.setMockInitialValues({});
    store = AppStore(
      secureStorage: AppSecure(),
      preferences: await SharedPreferences.getInstance(),
      feedCache: MemoryFeedCache(),
      client: widgetHttpClient((request) async {
        if (request.url.path.endsWith('/auth/user')) {
          if (rejectAuth) return http.Response('{}', 401);
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
        if (request.url.path.endsWith('/statistics')) {
          return http.Response('{"data":{}}', 200);
        }
        if (request.url.path.endsWith('/unread/count')) {
          return http.Response('{"data":0}', 200);
        }
        return http.Response(
          '{"data":[],"meta":{"current_page":1,"last_page":1}}',
          200,
        );
      }),
    );
    await store.initialize(validate: false);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(native, (call) async {
          nativeCalls.add(call.method);
          if (nativeHandler != null) return nativeHandler!(call);
          if (call.method == 'legacyImport') return <String, dynamic>{};
          return {'running': false, 'generation': 0};
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(events, (_) async => null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(speech, (call) async {
          if ([
            'getLanguages',
            'getVoices',
            'getEngines',
          ].contains(call.method)) {
            return <Object>[];
          }
          return 1;
        });
  }

  tearDown(() async {
    store.dispose();
    nativeHandler = null;
    nativeCalls.clear();
    rejectAuth = false;
    for (final channel in [native, events, speech]) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    }
  });

  testWidgets(
    'legacy bootstrap completes before manual token login is offered',
    (tester) async {
      await initializeStore();
      final legacy = Completer<Object?>();
      nativeHandler = (call) => call.method == 'legacyImport'
          ? legacy.future
          : Future.value({'running': false, 'generation': 0});
      await tester.pumpWidget(RoutelyApp(store: store));
      await tester.pump();
      expect(find.byType(SetupScreen), findsNothing);
      expect(find.text('Routely wird vorbereitet …'), findsOneWidget);
      expect(nativeCalls, ['legacyImport']);
      legacy.complete(<String, dynamic>{});
      await tester.pumpAndSettle();
      expect(find.byType(SetupScreen), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Anmelden'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
    variant: const TargetPlatformVariant({TargetPlatform.android}),
  );

  testWidgets(
    'saved auth is validated and 401 returns to token setup without displaying old content',
    (tester) async {
      await initializeStore();
      await store.login('https://example.test', 'a');
      rejectAuth = true;
      await tester.pumpWidget(RoutelyApp(store: store));
      await tester.pumpAndSettle();
      expect(store.authenticated, isFalse);
      expect(find.byType(SetupScreen), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Anmelden'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
    variant: const TargetPlatformVariant({TargetPlatform.android}),
  );

  testWidgets(
    'account generation resets settings route and tab while system theme follows brightness',
    (tester) async {
      await initializeStore();
      await store.login('https://example.test', 'a');
      await store.setSetting('app_theme', 'SYSTEM');
      tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
      addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
      await tester.pumpWidget(RoutelyApp(store: store));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Profil'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Einstellungen'),
        200,
        scrollable: find
            .descendant(
              of: find.byType(ProfileScreen),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.ensureVisible(find.text('Einstellungen'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Einstellungen'));
      await tester.pumpAndSettle();
      expect(find.text('Einstellungen'), findsOneWidget);
      tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
      await tester.pumpAndSettle();
      expect(
        Theme.of(tester.element(find.text('Einstellungen'))).brightness,
        Brightness.dark,
      );
      await store.login('https://example.test', 'b');
      await tester.pumpAndSettle();
      expect(find.text('Einstellungen'), findsNothing);
      expect(find.text('Reisebegleitung'), findsNothing);
      expect(
        tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex,
        0,
      );
      expect(store.user?.username, 'b');
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
    variant: const TargetPlatformVariant({TargetPlatform.android}),
  );

  testWidgets('a saved inactive native trip remains visibly resumable', (
    tester,
  ) async {
    await initializeStore();
    await store.login('https://example.test', 'a');
    await store.setActiveStatus(77);
    await tester.pumpWidget(RoutelyApp(store: store));
    await tester.pumpAndSettle();
    expect(find.text('Aktive Fahrt fortsetzen'), findsOneWidget);
    expect(nativeCalls, isNot(contains('startTracking')));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  }, variant: const TargetPlatformVariant({TargetPlatform.android}));
  testWidgets('late DELETE completion retires only its own detail route', (
    tester,
  ) async {
    await initializeStore();
    await store.login('https://example.test', 'a');
    await store.setActiveStatus(77);
    await tester.pumpWidget(RoutelyApp(store: store));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Aktive Fahrt fortsetzen'));
    await tester.pumpAndSettle();
    final detail = tester.widget<StatusDetailScreen>(
      find.byType(StatusDetailScreen),
    );
    final navigator = Navigator.of(
      tester.element(find.byType(StatusDetailScreen)),
    );
    unawaited(
      navigator.push<void>(
        MaterialPageRoute<void>(
          builder: (_) =>
              const Scaffold(body: Text('Eine später geöffnete Fahrt')),
        ),
      ),
    );
    await tester.pumpAndSettle();
    detail.onDeleted!();
    await tester.pumpAndSettle();
    expect(find.text('Eine später geöffnete Fahrt'), findsOneWidget);
    navigator.pop();
    await tester.pumpAndSettle();
    expect(find.byType(StatusDetailScreen), findsNothing);
    expect(find.text('Aktive Fahrt fortsetzen'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  }, variant: const TargetPlatformVariant({TargetPlatform.android}));

  testWidgets(
    'old logout waiting on native stop cannot revoke a newer account',
    (tester) async {
      await initializeStore();
      await store.login('https://example.test', 'a');
      await store.setActiveStatus(77);
      final old = store.sessionRevision;
      final stopped = Completer<Object?>();
      nativeHandler = (call) async {
        if (call.method == 'getConfiguration') {
          return {
            'running': true,
            'mode': 'tracking',
            'sessionRevision': old,
            'statusId': 77,
            'generation': 1,
          };
        }
        if (call.method == 'stopTracking') return stopped.future;
        return <String, dynamic>{};
      };
      await tester.pumpWidget(RoutelyApp(store: store));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Profil'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Einstellungen'),
        200,
        scrollable: find
            .descendant(
              of: find.byType(ProfileScreen),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.ensureVisible(find.text('Einstellungen'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Einstellungen'));
      await tester.pumpAndSettle();
      final settings = tester.widget<SettingsScreen>(
        find.byType(SettingsScreen),
      );
      final logout = settings.onLogout();
      await tester.pump();
      expect(nativeCalls, contains('stopTracking'));
      await store.login('https://example.test', 'b');
      await tester.pumpAndSettle();
      stopped.complete({'accepted': true});
      await tester.pumpAndSettle();
      await logout;
      await tester.pumpAndSettle();
      expect(store.authenticated, isTrue);
      expect(store.user?.username, 'b');
      expect(find.byType(SetupScreen), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
    variant: const TargetPlatformVariant({TargetPlatform.android}),
  );
  testWidgets(
    'follow notification opens the current /@username profile route with configured base prefix',
    (tester) async {
      await initializeStore();
      await store.login('https://example.test/routely', 'a');
      await tester.pumpWidget(RoutelyApp(store: store));
      await tester.pumpAndSettle();
      final notifications = tester.widget<NotificationsScreen>(
        find.byType(NotificationsScreen, skipOffstage: false),
      );
      for (final link in [
        '/@friend_7',
        '/routely/@friend_7',
        'https://example.test/routely/@friend_7',
        '/routely/user/friend_7',
      ]) {
        notifications.onNotificationLink!(link);
        await tester.pumpAndSettle();
        expect(
          tester
              .widget<UserProfileScreen>(find.byType(UserProfileScreen))
              .username,
          'friend_7',
        );
        Navigator.of(tester.element(find.byType(UserProfileScreen))).pop();
        await tester.pumpAndSettle();
      }
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
    variant: const TargetPlatformVariant({TargetPlatform.android}),
  );

  testWidgets(
    'notification routing rejects foreign origins and invalid backend usernames',
    (tester) async {
      await initializeStore();
      await store.login('https://example.test/routely', 'a');
      await tester.pumpWidget(RoutelyApp(store: store));
      await tester.pumpAndSettle();
      final notifications = tester.widget<NotificationsScreen>(
        find.byType(NotificationsScreen, skipOffstage: false),
      );
      for (final link in [
        'https://evil.test/@friend_7',
        'http://example.test/@friend_7',
        'https://example.test:444/@friend_7',
        '//evil.test/@friend_7',
        '/@bad-name',
        '/@a%2Fb',
        '/@abcdefghijklmnopqrstuvwxyz',
        '/@',
        '/routely/user/bad.name',
      ]) {
        notifications.onNotificationLink!(link);
        await tester.pump();
        expect(find.byType(UserProfileScreen), findsNothing);
        expect(find.byType(StatusDetailScreen), findsNothing);
      }
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
    variant: const TargetPlatformVariant({TargetPlatform.android}),
  );
}
