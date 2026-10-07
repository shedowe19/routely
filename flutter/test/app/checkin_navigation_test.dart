import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:routely/data/app_store.dart';
import 'package:routely/data/models.dart';
import 'package:routely/features/checkin/checkin_screen.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'memory_feed_cache.dart';
import 'widget_http_client.dart';

class TestSecure implements SecureSessionStorage {
  String? value;
  @override
  Future<String?> read() async => value;
  @override
  Future<void> write(String? next) async => value = next;
}

final station = Station.fromJson({'id': 1, 'name': 'Start'});
final departure = Departure.fromJson({
  'tripId': 'ride',
  'line': {'name': 'RE1'},
  'plannedWhen': '2026-10-07T10:00:00Z',
  'station': station.toJson(),
});
final trip = Trip.fromJson({
  'stopovers': [
    {
      'uuid': 'a',
      'station': station.toJson(),
      'departurePlanned': '2026-10-07T10:00:00Z',
    },
    {
      'uuid': 'b',
      'station': {'id': 2, 'name': 'Ziel'},
      'arrivalPlanned': '2026-10-07T10:30:00Z',
    },
  ],
});

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late AppStore store;
  Future<http.Response> Function(http.Request)? network;
  var posts = 0;
  // Keep store and HTTP futures in the widget test's FakeAsync zone.
  Future<void> initializeStore() async {
    SharedPreferences.setMockInitialValues({});
    store = AppStore(
      secureStorage: TestSecure(),
      feedCache: MemoryFeedCache(),
      preferences: await SharedPreferences.getInstance(),
      client: widgetHttpClient((request) async {
        if (request.url.path.endsWith('/auth/user')) {
          return http.Response('{"data":{"id":1,"username":"tester"}}', 200);
        }
        if (request.method == 'POST') posts++;
        if (network != null) return network!(request);
        return http.Response('{"data":[]}', 200);
      }),
    );
    await store.login('https://example.test', 'test-only');
    posts = 0;
  }

  tearDown(() {
    store.dispose();
    network = null;
  });

  Widget screen({
    required VoidCallback rootBack,
    required Future<void> Function(Status) created,
    ValueChanged<bool>? pending,
  }) => MaterialApp(
    home: PopScope(
      canPop: false,
      child: CheckInScreen(
        store: store,
        initialStation: station,
        initialDeparture: departure,
        initialTrip: trip,
        onCreated: created,
        onBackAtRoot: rootBack,
        onSubmissionPendingChanged: pending,
      ),
    ),
  );

  testWidgets(
    'System Back moves one check-in step before returning to the feed root',
    (tester) async {
      await initializeStore();
      var rootBacks = 0;
      await tester.pumpWidget(
        screen(rootBack: () => rootBacks++, created: (_) async {}),
      );
      await tester.pumpAndSettle();
      expect(find.text('Ziel wählen — RE1'), findsOneWidget);
      await tester.tap(find.text('Ziel'));
      await tester.pumpAndSettle();
      expect(find.text('Details bestätigen'), findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.text('Ziel wählen — RE1'), findsOneWidget);
      expect(rootBacks, 0);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.text('Bahnhof suchen'), findsOneWidget);
      expect(rootBacks, 0);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(rootBacks, 1);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'pending accepted check-in cannot pop or submit twice and callback runs once',
    (tester) async {
      await initializeStore();
      final result = Completer<http.Response>();
      var callbacks = 0, rootBacks = 0, pending = false;
      network = (request) => result.future;
      await tester.pumpWidget(
        screen(
          rootBack: () => rootBacks++,
          created: (_) async => callbacks++,
          pending: (value) => pending = value,
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Ziel'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Jetzt einchecken!'),
        200,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.ensureVisible(find.text('Jetzt einchecken!'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Jetzt einchecken!'));
      await tester.pump();
      expect(pending, isTrue);
      await tester.binding.handlePopRoute();
      await tester.pump();
      expect(find.text('Details bestätigen'), findsOneWidget);
      expect(rootBacks, 0);
      expect(posts, 1);
      result.complete(
        http.Response(
          jsonEncode({
            'data': {
              'status': {
                'id': 77,
                'user': {'username': 'tester'},
                'checkin': {'lineName': 'RE1'},
              },
            },
          }),
          201,
        ),
      );
      await tester.pump();
      await tester.pumpAndSettle();
      expect(callbacks, 1);
      expect(pending, isFalse);
      expect(find.text('Erfolgreich eingecheckt!'), findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(rootBacks, 1);
      expect(posts, 1);
      expect(tester.takeException(), isNull);
    },
  );
}
