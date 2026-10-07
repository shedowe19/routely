import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:routely/data/models.dart';
import 'package:routely/data/routely_api.dart';

const session = AuthSession(
  serverUrl: 'https://example.com/routely',
  accessToken: 'test-secret',
  revision: 'one',
);

void main() {
  test(
    'successful mutation headers prevent resubmission after a body failure',
    () async {
      const request = CheckInRequest(
        tripId: 'a',
        lineName: 'b',
        startStationId: 80,
        destinationStationId: 81,
        departure: '2026-10-07T12:00:00Z',
        arrival: '2026-10-07T13:00:00Z',
      );
      for (final failure in [
        http.ClientException('connection lost'),
        TimeoutException('body timed out'),
      ]) {
        final api = RoutelyApi(
          session: session,
          client: MockClient.streaming((request, body) async {
            return http.StreamedResponse(Stream<List<int>>.error(failure), 200);
          }),
        );

        await expectLater(
          api.checkIn(request),
          throwsA(isA<AcceptedMutationException>()),
        );
        await expectLater(
          api.updateStatus(10, UpdateStatusRequest(body: 'updated')),
          throwsA(isA<AcceptedMutationException>()),
        );
        await expectLater(
          api.status(10),
          throwsA(
            isA<ApiException>().having((e) => e.temporary, 'temporary', true),
          ),
        );
      }
    },
  );

  test('only valid HTTPS server hosts without credentials/query/fragment', () {
    expect(
      normalizeServerUrl(' https://EXAMPLE.com/base/// '),
      'https://example.com/base',
    );
    for (final value in [
      'http://example.com',
      'https://u:p@example.com',
      'https://example.com?q=1',
      'https://example.com#frag',
      'not a URL',
    ]) {
      expect(() => normalizeServerUrl(value), throwsFormatException);
    }
    expect(session.toString(), isNot(contains('test-secret')));
  });

  test(
    'endpoint paths preserve server base path and encode user input',
    () async {
      final requests = <http.Request>[];
      final api = RoutelyApi(
        session: session,
        client: MockClient((request) async {
          requests.add(request);
          expect(request.followRedirects, isFalse);
          expect(request.headers['Authorization'], 'Bearer test-secret');
          expect(request.url.path, startsWith('/routely/api/v1/'));
          return http.Response(
            jsonEncode({
              'data': request.url.path.contains('statistics') ? {} : [],
            }),
            200,
          );
        }),
      );
      await api.searchStations('Köln / Hbf?');
      expect(
        requests.single.url.toString(),
        contains('K%C3%B6ln%20%2F%20Hbf%3F'),
      );
      await api.departures(
        123,
        when: '2026-10-07T10:00:00+02:00',
        travelType: 'regional',
      );
      expect(requests.last.url.path, '/routely/api/v1/station/123/departures');
      expect(
        requests.last.url.queryParameters['when'],
        '2026-10-07T10:00:00+02:00',
      );
      await api.statistics();
      expect(requests.last.url.path, '/routely/api/v1/statistics');
    },
  );

  test('checkin and correction send exact current request contracts', () async {
    final requests = <http.Request>[];
    final api = RoutelyApi(
      session: session,
      client: MockClient((request) async {
        requests.add(request);
        return http.Response(
          jsonEncode({
            'data': request.method == 'POST'
                ? {
                    'status': {'id': 10},
                  }
                : {'id': 10},
          }),
          200,
        );
      }),
    );
    await api.checkIn(
      const CheckInRequest(
        tripId: 'provider-id',
        lineName: 'RE 1',
        startStationId: 80,
        destinationStationId: 81,
        departure: '2026-10-07T12:00:00Z',
        arrival: '2026-10-07T13:00:00Z',
      ),
    );
    expect(requests.last.url.path, '/routely/api/v1/trains/checkin');
    expect(jsonDecode(requests.last.body)['start'], 80);
    expect(jsonDecode(requests.last.body)['destination'], 81);
    await api.updateStatus(
      10,
      UpdateStatusRequest(
        destination: 81,
        destinationArrivalPlanned: '2026-10-07T13:00:00Z',
      ),
    );
    expect(requests.last.method, 'PUT');
    expect(requests.last.url.path, '/routely/api/v1/status/10');
    expect(jsonDecode(requests.last.body), {
      'destinationId': 81,
      'destinationArrivalPlanned': '2026-10-07T13:00:00Z',
    });
  });

  test(
    'conflict is parsed as statuses, successful missing mutation is accepted',
    () async {
      final conflicts = RoutelyApi(
        session: session,
        client: MockClient(
          (_) async => http.Response('{"data":{"conflicts":[{"id":22}]}}', 409),
        ),
      );
      const request = CheckInRequest(
        tripId: 'a',
        lineName: 'b',
        startStationId: 1,
        destinationStationId: 2,
        departure: 'x',
        arrival: 'y',
      );
      await expectLater(
        conflicts.checkIn(request),
        throwsA(
          isA<CheckInConflictException>().having(
            (error) => error.conflicts.single.id,
            'conflict id',
            22,
          ),
        ),
      );
      final accepted = RoutelyApi(
        session: session,
        client: MockClient((_) async => http.Response('{}', 201)),
      );
      await expectLater(
        accepted.checkIn(request),
        throwsA(isA<AcceptedMutationException>()),
      );
      await expectLater(
        accepted.updateStatus(10, UpdateStatusRequest(body: 'x')),
        throwsA(isA<AcceptedMutationException>()),
      );
    },
  );

  test('stale failures cannot surface an old authentication error', () async {
    bool current = true;
    final pending = Completer<http.Response>();
    final api = RoutelyApi(
      session: session,
      isCurrent: (_) => current,
      client: MockClient((_) => pending.future),
    );
    final result = api.authUser();
    final expectation = expectLater(
      result,
      throwsA(isA<StaleRequestException>()),
    );
    current = false;
    pending.complete(http.Response('{"message":"secret server body"}', 401));
    await expectation;
  });

  test(
    'geometry uses status id only and never follows a foreign redirect',
    () async {
      final api = RoutelyApi(
        session: session,
        client: MockClient((request) async {
          expect(request.url.path, '/routely/api/v1/polyline/99');
          expect(request.url.hasQuery, isFalse);
          expect(request.followRedirects, isFalse);
          return http.Response(
            '',
            302,
            headers: {'location': 'https://foreign.example/collect'},
          );
        }),
      );
      await expectLater(api.polyline(99), throwsA(isA<ApiException>()));
    },
  );

  test(
    'stopovers map and notification endpoint shapes are parsed correctly',
    () async {
      final paths = <String>[];
      final api = RoutelyApi(
        session: session,
        client: MockClient((request) async {
          paths.add('${request.method} ${request.url.path}');
          if (request.url.path.contains('/stopovers/')) {
            return http.Response(
              '{"data":{"12":[{"uuid":"visit","station":{"id":3,"name":"Station"}}]}}',
              200,
            );
          }
          if (request.url.path.endsWith('/count')) {
            return http.Response('{"data":3}', 200);
          }
          return http.Response('', 204);
        }),
      );
      expect((await api.stopovers(12)).single.stationId, 3);
      expect(await api.unreadCount(), 3);
      await api.markNotificationRead('uuid-a');
      await api.markAllNotificationsRead();
      await api.setFollowing(4, true);
      await api.setLiked(5, false);
      expect(
        paths,
        containsAll([
          'PUT /routely/api/v1/notifications/read/uuid-a',
          'PUT /routely/api/v1/notifications/read/all',
          'POST /routely/api/v1/user/4/follow',
          'DELETE /routely/api/v1/status/5/like',
        ]),
      );
    },
  );
}
