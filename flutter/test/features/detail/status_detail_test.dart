import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:routely/data/app_store.dart';
import 'package:routely/data/models.dart';
import 'package:routely/data/routely_api.dart';
import 'package:routely/features/detail/detail_presentation.dart';
import 'package:routely/features/detail/status_detail_controller.dart';
import 'package:routely/features/detail/status_detail_screen.dart';
import 'package:routely/features/detail/status_edit_draft.dart';
import 'package:routely/shared/theme.dart';

const auth = AuthSession(
  serverUrl: 'https://example.test',
  accessToken: 'test-only',
  revision: 'one',
);
Stop visit(
  int station,
  String uuid,
  String arrival,
  String departure, {
  bool cancelled = false,
}) => Stop.fromJson({
  'uuid': uuid,
  'station': {
    'id': station,
    'name': 'Station $station',
    'latitude': 51.4,
    'longitude': 7.0,
  },
  'arrivalPlanned': arrival,
  'departurePlanned': departure,
  'cancelled': cancelled,
});
final a = visit(1, 'a', '2026-10-07T10:00:00Z', '2026-10-07T10:01:00Z');
final b = visit(2, 'b', '2026-10-07T10:05:00Z', '2026-10-07T10:06:00Z');
final c = visit(1, 'c', '2026-10-07T10:10:00Z', '2026-10-07T10:11:00Z');
Status ride({
  String body = 'opening',
  Stop? destination,
  String? manualArrival,
}) => Status.fromJson({
  'id': 42,
  'body': body,
  'visibility': 0,
  'user': {'id': 1, 'username': 'owner', 'pointsEnabled': true},
  'checkin': {
    'trip': 8,
    'lineName': 'S28',
    'category': 'suburban',
    'origin': a.toJson(),
    'destination': (destination ?? b).toJson(),
    'manualArrival': ?manualArrival,
    'distance': 1200,
    'duration': 5,
    'points': 2,
  },
});

class FakeApi extends RoutelyApi {
  FakeApi() : super(session: auth);
  Future<List<Stop>> Function(int)? readStops;
  @override
  Future<List<Stop>> stopovers(int tripId) =>
      readStops?.call(tripId) ?? Future.value([a, b, c]);
  @override
  void close() {}
}

class FakeStore extends AppStore {
  final fakeApi = FakeApi();
  final events = StreamController<StatusMutation>.broadcast(sync: true);
  AuthSession? current = auth;
  Status currentStatus = ride();
  AppSettings displaySettings = AppSettings();
  Future<Status> Function(int)? readStatus;
  int revision = 0, deletes = 0;
  int? active = 42;
  UpdateStatusRequest? lastWrite;
  @override
  AuthSession? get session => current;
  @override
  String get sessionRevision => current?.revision ?? '';
  @override
  int get contentRevision => revision;
  @override
  AppSettings get settings => displaySettings;
  @override
  User? get user => User.fromJson({'id': 1, 'username': 'owner'});
  @override
  int? get activeStatusId => active;
  @override
  RoutelyApi get api => fakeApi;
  @override
  bool isCurrent(AuthSession session) => session == current;
  @override
  Stream<StatusMutation> get mutations => events.stream;
  @override
  Future<Status> status(int id) =>
      readStatus?.call(id) ?? Future.value(currentStatus);
  @override
  Future<Status> updateStatus(
    int id,
    UpdateStatusRequest request, {
    AuthSession? expectedSession,
  }) async {
    if (!isCurrent(expectedSession!)) throw const StaleRequestException();
    lastWrite = request;
    revision++;
    currentStatus = Status.fromJson({
      ...currentStatus.toJson(),
      if (request.body != null) 'body': request.body,
    });
    events.add(
      StatusMutation(
        kind: StatusMutationKind.updated,
        statusId: id,
        sessionRevision: auth.revision,
        status: currentStatus,
      ),
    );
    return currentStatus;
  }

  @override
  Future<void> deleteStatus(int id, {AuthSession? expectedSession}) async {
    deletes++;
    revision++;
    active = null;
    events.add(
      StatusMutation(
        kind: StatusMutationKind.deleted,
        statusId: id,
        sessionRevision: auth.revision,
      ),
    );
  }

  void endSession() {
    current = null;
    notifyListeners();
  }

  @override
  void dispose() {
    events.close();
    super.dispose();
  }
}

Future<void> flush() async {
  for (var i = 0; i < 12; i++) {
    await Future<void>.value();
  }
}

void main() {
  test(
    'a body edit never converts untouched API or GPS times to manual times',
    () {
      final opening = ride(), draft = StatusEditDraft(opening);
      draft.body = 'edited';
      final request = draft.build(ride(body: 'remote change', destination: c));
      expect(request.toJson(), {'body': 'edited'});
      final untouched = StatusEditDraft(
        opening,
      ).build(ride(body: 'remote change', destination: c));
      expect(untouched.toJson(), isEmpty);
    },
  );
  test(
    'a later visit to the same station sends the full planned destination pair',
    () {
      final opening = ride(manualArrival: '2026-10-07T10:07:00Z'),
          draft = StatusEditDraft(opening);
      expect(draft.selectDestination(c, opening, [a, b, c]), isTrue);
      final request = draft.build(opening).toJson();
      expect(request, {
        'destinationId': 1,
        'destinationArrivalPlanned': c.arrivalPlanned,
        'manualArrival': '',
      });
      expect(draft.selectDestination(b, opening, [a, b, c]), isTrue);
      expect(draft.arrival, '2026-10-07T10:07:00.000Z');
      expect(draft.build(opening).toJson(), isEmpty);
    },
  );
  test(
    'explicit arrival intent survives an automatic destination time change',
    () {
      final opening = ride(), draft = StatusEditDraft(opening);
      draft.setArrival('2026-10-07T10:10:00.000Z');
      expect(draft.selectDestination(c, opening, [a, b, c]), isTrue);
      expect(
        draft.build(opening).toJson()['manualArrival'],
        '2026-10-07T10:10:00.000Z',
      );
    },
  );
  test(
    'cancelled, ambiguous and pre-boarding destinations cannot be selected',
    () {
      final opening = ride(), draft = StatusEditDraft(opening);
      expect(draft.selectDestination(a, opening, [a, b, c]), isFalse);
      expect(draft.selectDestination(b, opening, [a, b, b, c]), isFalse);
      expect(
        draft.selectDestination(
          visit(
            3,
            'x',
            '2026-10-07T10:12:00Z',
            '2026-10-07T10:12:00Z',
            cancelled: true,
          ),
          opening,
          [a, b, c],
        ),
        isFalse,
      );
    },
  );
  test('manual input without a timezone is rejected', () {
    final draft = StatusEditDraft(ride())..departure = '2026-10-07T10:02:00';
    expect(() => draft.build(ride()), throwsFormatException);
  });
  test(
    'manual input cannot normalize an impossible calendar date silently',
    () {
      final draft = StatusEditDraft(ride())..departure = '2026-02-31T10:02:00Z';
      expect(() => draft.build(ride()), throwsFormatException);
    },
  );
  test(
    'a selected destination cancelled or retimed during editing is rejected',
    () {
      final opening = ride(), draft = StatusEditDraft(opening);
      expect(draft.selectDestination(c, opening, [a, b, c]), isTrue);
      final cancelled = Stop.fromJson({...c.toJson(), 'cancelled': true});
      expect(
        () => validateEditedDestination(draft, opening, [a, b, cancelled]),
        throwsFormatException,
      );
      final retimed = Stop.fromJson({
        ...c.toJson(),
        'arrivalPlanned': '2026-10-07T10:13:00Z',
      });
      expect(
        () => validateEditedDestination(draft, opening, [a, b, retimed]),
        throwsFormatException,
      );
    },
  );
  test(
    'a refreshed plan marker cannot reuse an incompatible previous stop list',
    () {
      final old = ride();
      final changed = visit(
        2,
        'b',
        '2026-10-07T10:08:00Z',
        b.departurePlanned!,
      );
      expect(
        compatibleExistingStops(ride(destination: changed), old, [a, b, c]),
        isEmpty,
      );
    },
  );
  test(
    'current and next markers share one cursor even at overlapping dwell times',
    () {
      final stops = [
        a,
        visit(2, 'b', a.arrivalPlanned!, a.departurePlanned!),
        c,
      ];
      final progress = resolveTimelineProgress(
        stops,
        displayStops(stops, ride().checkin!),
        DateTime.parse('2026-10-07T10:00:30Z').millisecondsSinceEpoch,
        null,
        destinationIndex: 1,
      );
      expect(progress.currentIndex, 1);
      expect(
        [
          for (var i = 0; i < stops.length; i++)
            if (progress.badgeFor(i) != null) i,
        ],
        [1],
      );
    },
  );
  test(
    'section GPS cursor is mapped by visit identity rather than its array index',
    () {
      final route = [a, b, c],
          snapshot = <String, dynamic>{
            'source': 'gps',
            'nextIndex': 0,
            'nextStopKey': 'b',
            'arrivedAtCurrent': true,
            'stop': displayStops(route, ride().checkin!)[1].toJson(),
          };
      final progress = resolveTimelineProgress(
        route,
        displayStops(route, ride().checkin!),
        0,
        snapshot,
        destinationIndex: 1,
      );
      expect(progress.currentIndex, 1);
      expect(progress.badgeFor(0), isNull);
      expect(progress.badgeFor(1), 'AKTUELL');
      snapshot['stop'] = null;
      snapshot['nextStopKey'] = 'missing';
      expect(
        resolveTimelineProgress(
          route,
          displayStops(route, ride().checkin!),
          DateTime.now().millisecondsSinceEpoch,
          snapshot,
          destinationIndex: 1,
        ).source,
        TimelineSource.waiting,
      );
    },
  );
  test('tracking from another session and unsafe map links are rejected', () {
    expect(
      acceptedTracking(
        {'statusId': 42, 'sessionRevision': 'old'},
        statusId: 42,
        sessionRevision: 'one',
        own: true,
        activeStatusId: 42,
      ),
      isNull,
    );
    expect(safeSevMapUri('https://www.bahnhof.de/essen-hbf/karte'), isNotNull);
    expect(
      safeSevMapUri('https://www.bahnhof.de.evil.test/essen-hbf/karte'),
      isNull,
    );
    expect(
      safeSevMapUri('https://user:secret@www.bahnhof.de/essen-hbf/karte'),
      isNull,
    );
    expect(safeSevMapUri('javascript:alert(1)'), isNull);
  });
  test(
    'directional platform lookup never uses the opposite event as fallback',
    () {
      final stop = Stop.fromJson({
        ...b.toJson(),
        'arrivalPlatformReal': ' 02a ',
        'departurePlatformReal': ' 9 ',
      });
      expect(trackingPlatform(stop, true), '9');
      expect(trackingPlatform(stop, false), '02a');
      expect(
        trackingPlatform(
          Stop.fromJson({...b.toJson(), 'arrivalPlatformReal': '2'}),
          true,
        ),
        isNull,
      );
    },
  );
  test(
    'the newest complete refresh wins over an older delayed stop response',
    () async {
      final store = FakeStore(), old = Completer<List<Stop>>();
      var stopReads = 0;
      store.fakeApi.readStops = (_) =>
          ++stopReads == 1 ? old.future : Future.value([a, b, c]);
      final controller = StatusDetailController(store, 42);
      addTearDown(() {
        controller.dispose();
        store.dispose();
      });
      final first = controller.refresh();
      await flush();
      store.currentStatus = ride(body: 'new');
      await controller.refresh();
      old.complete([a, b]);
      await first;
      expect(controller.status!.body, 'new');
      expect(controller.stops.length, 3);
    },
  );
  test(
    'a content mutation during the second endpoint causes a whole snapshot retry',
    () async {
      final store = FakeStore();
      var stopReads = 0;
      store.fakeApi.readStops = (_) async {
        if (++stopReads == 1) {
          store.revision++;
          store.currentStatus = ride(body: 'corrected');
        }
        return [a, b, c];
      };
      final controller = StatusDetailController(store, 42);
      addTearDown(() {
        controller.dispose();
        store.dispose();
      });
      await controller.refresh();
      expect(stopReads, 2);
      expect(controller.status!.body, 'corrected');
      expect(controller.error, isNull);
    },
  );
  test('logout discards old detail responses and drafts', () async {
    final store = FakeStore(), response = Completer<Status>();
    store.readStatus = (_) => response.future;
    final controller = StatusDetailController(store, 42);
    addTearDown(() {
      controller.dispose();
      store.dispose();
    });
    final read = controller.refresh();
    store.endSession();
    response.complete(ride());
    await read;
    expect(controller.status, isNull);
    expect(controller.draft, isNull);
    expect(controller.loading, isFalse);
  });
  test(
    'confirmed deletion remains successful when native cleanup fails',
    () async {
      final store = FakeStore();
      final detail = StatusDetailController(store, 42);
      addTearDown(() {
        detail.dispose();
        store.dispose();
      });
      await detail.refresh();
      final deleted = await detail.delete(
        cleanup: () async => throw StateError('native service unavailable'),
      );
      expect(deleted, isTrue);
      expect(store.deletes, 1);
      expect(detail.deleting, isFalse);
      expect(detail.error, contains('Fahrt gelöscht'));
      expect(detail.status, isNull);
    },
  );
  testWidgets(
    'own live details remain usable on a narrow screen with large text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final store = FakeStore();
      final status = store.currentStatus;
      store.currentStatus = Status.fromJson({
        ...status.toJson(),
        'checkin': {
          ...status.checkin!.toJson(),
          'origin': {
            ...status.checkin!.origin!.toJson(),
            'departurePlanned': DateTime.now().toUtc().toIso8601String(),
          },
        },
      });
      addTearDown(store.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: routelyTheme('LIGHT', Brightness.light),
          home: MediaQuery(
            data: const MediaQueryData(
              size: Size(320, 640),
              textScaler: TextScaler.linear(2),
            ),
            child: StatusDetailScreen(
              store: store,
              statusId: 42,
              onTrack: (_, _) async {},
              onStop: () async {},
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 10));
      expect(find.byTooltip('Fahrt bearbeiten'), findsOneWidget);
      expect(find.byTooltip('Fahrt löschen'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.scrollUntilVisible(
        find.text('Haltestellenverlauf'),
        300,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'detail renders one GPS current visit and keeps departure source independent',
    (tester) async {
      final store = FakeStore();
      addTearDown(store.dispose);
      final snapshot = ValueNotifier<Map<String, dynamic>?>({
        'statusId': 42,
        'sessionRevision': 'one',
        'source': 'gps',
        'nextStopKey': 'b',
        'arrivedAtCurrent': true,
        'stop': displayStops([a, b, c], ride().checkin!)[1].toJson(),
        'etaReason': 'noFreshLocation',
        'tripProgress': {'remainingText': 'Noch 1 Halt'},
        'changes': [
          {
            'key': 'platform-b',
            'title': 'Gleiswechsel',
            'message': 'Der nächste Halt nutzt jetzt Gleis 3 statt Gleis 2.',
          },
          {
            'key': 'platform-b',
            'title': 'Gleiswechsel',
            'message': 'Der nächste Halt nutzt jetzt Gleis 3 statt Gleis 2.',
          },
        ],
      });
      addTearDown(snapshot.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: routelyTheme('AMOLED', Brightness.dark),
          home: StatusDetailScreen(
            store: store,
            statusId: 42,
            tracking: snapshot,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 10));
      expect(find.text('Fahrt-Details'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('Zuletzt gemeldete Änderungen'),
        300,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      expect(find.text('Zuletzt gemeldete Änderungen'), findsOneWidget);
      expect(
        find.text('Der nächste Halt nutzt jetzt Gleis 3 statt Gleis 2.'),
        findsOneWidget,
      );
      await tester.scrollUntilVisible(
        find.text('AKTUELL'),
        400,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      expect(find.text('AKTUELL'), findsOneWidget);
      expect(find.text('Noch 1 Halt'), findsOneWidget);
      expect(
        find.text('Zeitprognose wartet auf frisches GPS.'),
        findsOneWidget,
      );
      await tester.pumpWidget(const SizedBox());
    },
  );
}
