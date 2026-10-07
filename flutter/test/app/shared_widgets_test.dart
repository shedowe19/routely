import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:routely/data/models.dart';
import 'package:routely/shared/theme.dart';
import 'package:routely/shared/widgets.dart';

void main() {
  testWidgets(
    'history cards show their local date on narrow screens with large text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 480);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: const MediaQueryData(
              size: Size(320, 480),
              textScaler: TextScaler.linear(2),
            ),
            child: Scaffold(
              body: ListView(
                children: [
                  StatusCard(
                    status: Status.fromJson({
                      'id': 1,
                      'createdAt': DateTime(
                        2026,
                        10,
                        7,
                        12,
                        18,
                      ).toUtc().toIso8601String(),
                      'user': {'username': 'commuter'},
                    }),
                  ),
                  StatusCard(
                    status: Status.fromJson({
                      'id': 2,
                      'createdAt': DateTime(
                        2026,
                        10,
                        6,
                        12,
                        18,
                      ).toUtc().toIso8601String(),
                    }),
                  ),
                  StatusCard(
                    status: Status.fromJson({
                      'id': 3,
                      'createdAt': 'invalid-date',
                      'user': {'username': 'without_date'},
                    }),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
      expect(find.text('07.10.2026 12:18'), findsOneWidget);
      expect(find.text('06.10.2026 12:18'), findsOneWidget);
      expect(find.text('invalid-date'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'state errors and retry stay reachable on a short display with large text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 240);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      var retried = false;
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: const MediaQueryData(
              size: Size(320, 240),
              textScaler: TextScaler.linear(2),
            ),
            child: Scaffold(
              body: StateMessage(
                icon: Icons.wifi_off,
                title: 'Die Anmeldung ist gerade nicht erreichbar.',
                message:
                    'Du kannst es später erneut versuchen. Deine bereits gespeicherten Fahrten bleiben erhalten.',
                actionLabel: 'Erneut versuchen',
                onAction: () => retried = true,
              ),
            ),
          ),
        ),
      );
      expect(tester.takeException(), isNull);
      await tester.scrollUntilVisible(
        find.text('Erneut versuchen'),
        120,
        scrollable: find.byType(Scrollable),
      );
      await tester.tap(find.text('Erneut versuchen'));
      expect(retried, isTrue);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('malformed route colors preserve the transport fallback', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: LineBadge(
            line: 'RE1',
            category: 'bus',
            color: 'zzzzzz',
            textColor: 'broken',
          ),
        ),
      ),
    );
    final container = tester.widget<Container>(
      find.descendant(
        of: find.byType(LineBadge),
        matching: find.byType(Container),
      ),
    );
    expect(
      (container.decoration as BoxDecoration).color,
      RoutelyColors.transport('bus'),
    );
    expect(tester.takeException(), isNull);
  });

  test(
    'system theme follows both platform brightnesses and AMOLED remains black',
    () {
      expect(
        routelyTheme('SYSTEM', Brightness.dark).brightness,
        Brightness.dark,
      );
      expect(
        routelyTheme('SYSTEM', Brightness.light).brightness,
        Brightness.light,
      );
      expect(
        routelyTheme('AMOLED', Brightness.light).scaffoldBackgroundColor,
        Colors.black,
      );
    },
  );
}
