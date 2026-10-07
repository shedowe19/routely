import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:routely/data/models.dart';
import 'package:routely/data/routely_api.dart';
import 'package:routely/features/checkin/checkin_controller.dart';
import 'package:routely/features/checkin/checkin_selection.dart';

Station station(int id) => Station.fromJson({'id': id, 'name': 'Station $id'});
Stop stop(
  int stationId,
  String time, {
  String? arrival,
  String? uuid,
  bool cancelled = false,
}) => Stop.fromJson({
  'id': 9000 + stationId,
  'uuid': uuid ?? 'visit-$stationId-$time',
  'station': station(stationId).toJson(),
  'departurePlanned': time,
  'arrivalPlanned': arrival ?? time,
  'cancelled': cancelled,
});
Departure departure({
  int start = 1,
  String time = '2026-10-07T10:00:00Z',
  bool cancelled = false,
}) => Departure.fromJson({
  'tripId': 'provider-trip',
  'plannedWhen': time,
  'station': station(start).toJson(),
  'line': {'name': 'RE 1', 'product': 'regional'},
  'cancelled': cancelled,
});
Trip trip() => Trip.fromJson({
  'stopovers': [
    stop(1, '2026-10-07T10:00:00Z').toJson(),
    stop(2, '2026-10-07T10:30:00Z').toJson(),
  ],
});
Status createdStatus(int id) => Status.fromJson({
  'id': id,
  'checkin': {'lineName': 'RE 1'},
});

CheckInController controller({
  Future<Status> Function(CheckInRequest)? create,
  Future<Status> Function(int, UpdateStatusRequest)? correct,
  Future<void> Function(Status)? onCreated,
  String? Function()? session,
  Future<List<Departure>> Function(int, String?)? departures,
  Future<Trip> Function(String, String)? loadTrip,
}) => CheckInController(
  searchStations: (_) async => [station(1)],
  nearbyStations: (_, _) async => [station(1)],
  loadDepartures: departures ?? (_, _) async => [departure()],
  loadTrip: loadTrip ?? (_, _) async => trip(),
  create: create ?? (_) async => createdStatus(77),
  correctTimes: correct ?? (id, _) async => createdStatus(id),
  onCreated: onCreated ?? (_) async {},
  sessionRevision: session ?? () => 'session-one',
);

Future<void> prepare(CheckInController state) async {
  await state.selectStation(station(1));
  await state.selectDeparture(departure());
  state.selectDestination(state.destinations.last);
}

void main() {
  test(
    'recurring station uses planned visit, never first station occurrence',
    () {
      final stops = [
        stop(1, '2026-10-07T09:00:00Z'),
        stop(2, '2026-10-07T09:30:00Z'),
        stop(1, '2026-10-07T10:00:00Z'),
        stop(3, '2026-10-07T10:30:00Z'),
      ];
      expect(
        resolveCheckInOriginIndex(
          stops,
          station(1),
          departure(time: '2026-10-07T12:00:00+02:00'),
        ),
        2,
      );
      expect(
        resolveCheckInOriginIndex(
          stops,
          station(1),
          departure(time: '2026-10-07T11:00:00Z'),
        ),
        -1,
      );
      expect(validCheckInDestinations(stops, 2).single.stationId, 3);
    },
  );

  test('ambiguous repeated boarding and cancelled targets are excluded', () {
    final stops = [
      stop(1, '2026-10-07T10:00:00Z'),
      stop(2, '2026-10-07T10:20:00Z', cancelled: true),
      stop(3, '2026-10-07T10:30:00Z'),
    ];
    expect(validCheckInDestinations(stops, 0).map((item) => item.stationId), [
      3,
    ]);
    expect(validCheckInDestinations(stops, -1), isEmpty);
    expect(
      validCheckInDestinations([
        stop(1, '2026-10-07T10:00:00Z', cancelled: true),
        stops.last,
      ], 0),
      isEmpty,
    );
  });

  test('planned POST markers and manual PUT times remain distinct', () {
    final origin = stop(1, '2026-10-07T10:00:00Z');
    final target = stop(2, '2026-10-07T10:30:00Z');
    final submission = buildCheckInSubmission(
      departure: departure(),
      station: station(1),
      origin: origin,
      destination: target,
      manualDeparture: '2026-10-07T12:05:00+02:00',
      manualArrival: '2026-10-07T12:35:00+02:00',
      body: 'Unterwegs',
      business: 2,
    );
    expect(submission.request.startStationId, 1);
    expect(submission.request.destinationStationId, 2);
    expect(submission.request.departure, origin.departurePlanned);
    expect(submission.request.arrival, target.arrivalPlanned);
    expect(submission.request.toJson(), isNot(contains('manualDeparture')));
    expect(
      submission.timeCorrection!.toJson()['manualDeparture'],
      '2026-10-07T10:05:00.000Z',
    );
    expect(submission.request.business, 2);
  });

  test('manual times require valid calendar date and zone', () {
    expect(parseZonedTime('2026-10-07T12:00:00'), isNull);
    expect(parseZonedTime('2026-02-30T12:00:00Z'), isNull);
    expect(parseZonedTime('2026-10-07T24:00:00Z'), isNull);
    expect(parseZonedTime('2026-10-07T12:00:00+18:01'), isNull);
    expect(
      parseZonedTime('2026-10-07T12:00:00+02:00'),
      DateTime.utc(2026, 10, 7, 10),
    );
    expect(
      () => buildCheckInSubmission(
        departure: departure(),
        station: station(1),
        origin: trip().stopovers.first,
        destination: trip().stopovers.last,
        manualDeparture: '2026-10-07T11:00:00Z',
      ),
      throwsFormatException,
    );
  });

  test(
    'destination visit identity cannot be replaced by another visit at same station',
    () async {
      final state = controller();
      addTearDown(state.dispose);
      await state.selectStation(station(1));
      await state.selectDeparture(departure());
      state.selectDestination(stop(2, '2026-10-07T11:30:00Z'));
      expect(state.step, CheckInStep.destination);
      expect(state.error, contains('nicht verfügbar'));
    },
  );

  test(
    'POST executes once and pending create/PUT protects all navigation',
    () async {
      final post = Completer<Status>();
      final put = Completer<Status>();
      var creates = 0;
      var corrections = 0;
      final state = controller(
        create: (_) {
          creates++;
          return post.future;
        },
        correct: (_, _) {
          corrections++;
          return put.future;
        },
      );
      addTearDown(state.dispose);
      await prepare(state);
      state.updateDetails(realDeparture: '2026-10-07T10:05:00Z');
      final completion = state.confirm();
      await state.confirm();
      state.goBack();
      state.reset();
      state.updateQuery('Andere');
      await state.selectStation(station(2));
      expect(state.submissionPending, isTrue);
      expect(state.step, CheckInStep.confirm);
      expect(creates, 1);
      post.complete(createdStatus(77));
      await Future<void>.value();
      expect(state.step, CheckInStep.success);
      state.reset();
      state.goBack();
      await state.confirm();
      expect(state.step, CheckInStep.success);
      expect(corrections, 1);
      put.completeError(const ApiException('Offline'));
      await completion;
      expect(state.result!.id, 77);
      expect(state.warning, contains('erneuter Check-in ist nicht nötig'));
      expect(state.submissionPending, isFalse);
      expect(creates, 1);
    },
  );

  test(
    'accepted response without status id ends in success without another POST',
    () async {
      var creates = 0;
      var activated = 0;
      final state = controller(
        create: (_) async {
          creates++;
          throw const AcceptedMutationException();
        },
        onCreated: (_) async {
          activated++;
        },
      );
      addTearDown(state.dispose);
      await prepare(state);
      await state.confirm();
      await state.confirm();
      expect(state.step, CheckInStep.success);
      expect(state.result, isNull);
      expect(state.warning, contains('keine bestätigte Fahrt-ID'));
      expect(creates, 1);
      expect(activated, 0);
    },
  );

  test('local activation failure leaves confirmed check-in visible', () async {
    final state = controller(
      onCreated: (_) async {
        throw StateError('disk full');
      },
    );
    addTearDown(state.dispose);
    await prepare(state);
    await state.confirm();
    expect(state.step, CheckInStep.success);
    expect(state.result!.id, 77);
    expect(state.warning, contains('lokale Reisebegleitung'));
    expect(state.submissionPending, isFalse);
  });

  test('corrected status is delivered to tracking callback', () async {
    Status? tracked;
    final state = controller(
      correct: (id, _) async => Status.fromJson({
        'id': id,
        'checkin': {'manualDeparture': '2026-10-07T10:05:00Z'},
      }),
      onCreated: (status) async {
        tracked = status;
      },
    );
    addTearDown(state.dispose);
    await prepare(state);
    state.updateDetails(realDeparture: '2026-10-07T10:05:00Z');
    await state.confirm();
    expect(tracked!.checkin!.manualDeparture, '2026-10-07T10:05:00Z');
  });

  test(
    'awarded points survive a later manual time correction response',
    () async {
      final state = controller(
        create: (_) async => Status.fromJson({'id': 77, 'earnedPoints': 9}),
        correct: (id, _) async => createdStatus(id),
      );
      addTearDown(state.dispose);
      await prepare(state);
      state.updateDetails(realDeparture: '2026-10-07T10:05:00Z');
      await state.confirm();
      expect(state.earnedPoints, 9);
      expect(state.result!.id, 77);
    },
  );

  test('old account creation response never activates new account', () async {
    var session = 'old';
    final post = Completer<Status>();
    var activated = 0;
    final state = controller(
      create: (_) => post.future,
      onCreated: (_) async {
        activated++;
      },
      session: () => session,
    );
    addTearDown(state.dispose);
    await prepare(state);
    final completion = state.confirm();
    session = 'new';
    post.complete(createdStatus(77));
    await completion;
    expect(activated, 0);
    expect(state.result, isNull);
  });

  test('late departures cannot replace a newer station selection', () async {
    final old = Completer<List<Departure>>();
    final state = controller(
      departures: (id, _) =>
          id == 1 ? old.future : Future.value([departure(start: 2)]),
    );
    addTearDown(state.dispose);
    final first = state.selectStation(station(1));
    await state.selectStation(station(2));
    old.complete([departure()]);
    await first;
    expect(state.station!.id, 2);
    expect(state.departures.single.station!.id, 2);
  });

  test('late GPS lookup is discarded after manual search', () async {
    final location = Completer<(double, double)>();
    var nearbyCalls = 0;
    final state = CheckInController(
      searchStations: (_) async => [],
      nearbyStations: (_, _) async {
        nearbyCalls++;
        return [];
      },
      loadDepartures: (_, _) async => [],
      loadTrip: (_, _) async => trip(),
      create: (_) async => createdStatus(77),
      correctTimes: (_, _) async => createdStatus(77),
      onCreated: (_) async {},
      sessionRevision: () => 'session',
    );
    addTearDown(state.dispose);
    final lookup = state.findNearby(() => location.future);
    state.updateQuery('Berlin');
    location.complete((52.5, 13.4));
    await lookup;
    expect(nearbyCalls, 0);
    expect(state.query, 'Berlin');
  });

  test(
    'recognized route back returns to station without inventing departures',
    () async {
      final state = controller();
      addTearDown(state.dispose);
      await state.selectDeparture(
        departure(),
        recognizedStation: station(1),
        recognizedTrip: trip(),
      );
      state.goBack();
      expect(state.step, CheckInStep.station);
      expect(state.departures, isEmpty);
      expect(state.station, isNull);
      expect(state.query, isEmpty);
    },
  );

  test(
    'manual back retains proven departures and selected station result',
    () async {
      final state = controller();
      addTearDown(state.dispose);
      await state.selectStation(station(1));
      await state.selectDeparture(departure());
      state.goBack();
      expect(state.step, CheckInStep.departures);
      expect(state.departures, hasLength(1));
      state.goBack();
      expect(state.step, CheckInStep.station);
      expect(state.stations.single.id, 1);
      expect(state.query, 'Station 1');
    },
  );

  test('cancelled departure cannot enter destination selection', () async {
    final state = controller();
    addTearDown(state.dispose);
    await state.selectStation(station(1));
    await state.selectDeparture(departure(cancelled: true));
    expect(state.step, CheckInStep.departures);
    expect(state.error, contains('fällt aus'));
  });

  test(
    'earlier departure displays real time, plan time and negative delay',
    () {
      final early = Departure.fromJson({
        ...departure().toJson(),
        'when': '2026-10-07T09:57:00Z',
      });
      expect(departureTimeLabel(early), contains('Plan'));
      expect(departureTimeLabel(early), contains('−3 min'));
    },
  );

  test(
    'departure date selection sends UTC and reset restores current timetable',
    () async {
      final requests = <String?>[];
      final state = controller(
        departures: (_, when) async {
          requests.add(when);
          return [];
        },
      );
      addTearDown(state.dispose);
      await state.selectStation(station(1));
      await state.refreshDepartures(when: DateTime.utc(2026, 10, 7, 10));
      await state.refreshDepartures(resetTime: true);
      expect(requests, [null, '2026-10-07T10:00:00.000Z', null]);
    },
  );
}
