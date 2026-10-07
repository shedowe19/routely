import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:routely/data/models.dart';
import 'package:routely/features/checkin/checkin_selection.dart';

Json fixture(String name) => Map<String, dynamic>.from(
  jsonDecode(
        File(
          '../app/src/test/resources/traewelling/$name-current.json',
        ).readAsStringSync(),
      )
      as Map,
);

void main() {
  test(
    'current upstream status retains nested station/visit identity and manual times',
    () {
      final status = Status.fromJson(fixture('status')['data'] as Json);
      expect(status.id, 731);
      expect(status.checkin!.origin!.id, 91001);
      expect(status.checkin!.origin!.stationId, 80);
      expect(status.checkin!.origin!.station!.ibnr, 8010324);
      expect(status.checkin!.origin!.stationIdentifier('local_code'), 'SH');
      expect(status.checkin!.origin!.departurePlatformReal, '4');
      expect(
        status.checkin!.destination!.effectiveArrival,
        '2026-10-05T10:26:00+00:00',
      );
      expect(status.checkin!.tripUuid, 'baec4235-3cb6-4d3c-8f76-d17081c22e53');
      final corrected = Status.fromJson({
        ...status.toJson(),
        'checkin': {
          ...status.checkin!.toJson(),
          'manualDeparture': '2026-10-05T08:55:00+00:00',
        },
      });
      expect(corrected.checkin!.manualDeparture, '2026-10-05T08:55:00+00:00');
      expect(
        corrected.checkin!.origin!.departureReal,
        '2026-10-05T08:57:00+00:00',
      );
    },
  );

  test(
    'legacy stop name is displayed without reusing visit id as station id',
    () {
      final stop = Stop.fromJson({
        'id': 80,
        'name': 'Legacy Bahnhof',
        'departure': '2026-10-07T12:00:00Z',
      });
      expect(stop.stationName, 'Legacy Bahnhof');
      expect(stop.stationId, isNull);
      expect(stop.departurePlanned, isNull);
      expect(stop.effectiveDeparture, isNull);
      expect(stop.matchesStopover(stop), isFalse);
    },
  );

  test(
    'UUID differentiates repeated visits, instant fallback handles offsets',
    () {
      Stop stop(String? uuid, String departure) => Stop.fromJson({
        'uuid': uuid,
        'station': {'id': 10},
        'departurePlanned': departure,
      });
      final first = stop('first', '2026-10-07T12:00:00Z');
      final second = stop('second', '2026-10-07T12:00:00Z');
      expect(first.matchesStopover(second), isFalse);
      expect(
        first.matchesStopover(stop('first', '2026-10-07T13:00:00Z')),
        isTrue,
      );
      expect(
        stop(
          null,
          '2026-10-07T12:00:00Z',
        ).matchesStopover(stop(null, '2026-10-07T14:00:00+02:00')),
        isTrue,
      );
      expect(
        stop(
          null,
          '2026-10-07T12:00:00Z',
        ).matchesStopover(stop(null, '2026-10-07T13:00:00Z')),
        isFalse,
      );
      expect(deduplicateStops([first, second]), hasLength(2));
    },
  );

  test(
    'dedup only merges adjacent same visits and prefers platform details',
    () {
      final a = Stop.fromJson({
        'station': {'id': 1, 'name': 'Long Station'},
        'departurePlanned': '2026-10-07T12:00:00Z',
      });
      final b = Stop.fromJson({
        'station': {'id': 1, 'name': 'Short'},
        'departurePlanned': '2026-10-07T12:00:00Z',
        'departurePlatformPlanned': '2',
      });
      final c = Stop.fromJson({
        'station': {'id': 2, 'name': 'Other'},
        'departurePlanned': '2026-10-07T12:00:00Z',
      });
      expect(deduplicateStops([a, b, c, a]).map((e) => e.stationName), [
        'Short',
        'Other',
        'Long Station',
      ]);
    },
  );

  test(
    'trip normalization preserves one unambiguous boarding visit and conflicting UUIDs',
    () {
      const time = '2026-10-07T10:00:00Z';
      final boarding = Station.fromJson({'id': 1, 'name': 'A'});
      final departure = Departure.fromJson({
        'tripId': 'provider',
        'plannedWhen': time,
        'station': boarding.toJson(),
        'line': {'name': 'RE 1'},
      });
      final origin = {
        'uuid': 'origin',
        'station': boarding.toJson(),
        'departurePlanned': time,
      };
      final destination = {
        'uuid': 'destination',
        'station': {'id': 2, 'name': 'B'},
        'arrivalPlanned': '2026-10-07T10:30:00Z',
      };
      for (final duplicateUuid in ['origin', null]) {
        final trip = Trip.fromJson({
          'stopovers': [
            origin,
            {...origin, 'uuid': duplicateUuid, 'departurePlatformPlanned': '2'},
            destination,
          ],
        });
        expect(trip.stopovers, hasLength(2));
        final index = resolveCheckInOriginIndex(
          trip.stopovers,
          boarding,
          departure,
        );
        expect(index, 0);
        expect(
          validCheckInDestinations(trip.stopovers, index).single.uuid,
          'destination',
        );
        expect(trip.stopovers.first.departurePlatformPlanned, '2');
      }
      final conflicting = Trip.fromJson({
        'stopovers': [
          origin,
          {...origin, 'uuid': 'other-visit'},
          destination,
        ],
      });
      expect(conflicting.stopovers, hasLength(3));
      expect(
        resolveCheckInOriginIndex(conflicting.stopovers, boarding, departure),
        -1,
      );
    },
  );

  test(
    'unknown response fields survive cache and cannot be mutated in place',
    () {
      final json = {
        'id': 1,
        'checkin': {
          'origin': {
            'station': {'id': 2, 'name': 'A'},
          },
          'futureField': [1, 2],
        },
      };
      final status = Status.fromJson(json);
      (json['checkin'] as Map)['futureField'] = [3];
      expect((status.toJson()['checkin'] as Map)['futureField'], [1, 2]);
      expect(
        () => (status.raw['checkin'] as Map)['futureField'] = [4],
        throwsUnsupportedError,
      );
      expect(
        status.copyWith(liked: true, likes: 3).checkin!.origin!.stationId,
        2,
      );
    },
  );

  test('missing user username remains invalid for login', () {
    expect(User.fromJson({'id': 1}).username, isEmpty);
  });

  test(
    'request corrections omit unchanged fields and require complete destination visit',
    () {
      expect(UpdateStatusRequest(departure: '2026-10-07T10:00:00Z').toJson(), {
        'manualDeparture': '2026-10-07T10:00:00Z',
      });
      expect(UpdateStatusRequest(body: '').toJson(), {'body': ''});
      expect(() => UpdateStatusRequest(destination: 12), throwsArgumentError);
      expect(
        UpdateStatusRequest(
          destination: 12,
          destinationArrivalPlanned: '2026-10-07T11:00:00Z',
        ).toJson(),
        {
          'destinationId': 12,
          'destinationArrivalPlanned': '2026-10-07T11:00:00Z',
        },
      );
      expect(
        const CheckInRequest(
          tripId: 'trip',
          lineName: 'RE1',
          startStationId: 5,
          destinationStationId: 6,
          departure: 'a',
          arrival: 'b',
          business: 2,
        ).toJson()['start'],
        5,
      );
    },
  );

  test(
    'notification read rollback retains metadata and pagination handles global feed',
    () {
      final original = AppNotification(
        id: 'uuid',
        type: 'StatusLiked',
        notice: 'Text',
      );
      final changed = original.withReadAt('2026-10-07T10:00:00Z');
      expect(changed.isRead, isTrue);
      expect(changed.withReadAt(null).isRead, isFalse);
      expect(changed.withReadAt(null).type, 'StatusLiked');
      expect(
        Page<Status>.fromJson({'data': []}, Status.fromJson).hasNext,
        isFalse,
      );
      expect(
        Page<Status>.fromJson({
          'data': [],
          'links': {'next': '/dashboard?page=2'},
          'meta': {'current_page': 1, 'last_page': 3},
        }, Status.fromJson).hasNext,
        isTrue,
      );
    },
  );
}
