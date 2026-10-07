import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:routely/data/models.dart' as api;
import 'package:routely/tracking/sev_enrichment.dart';

final now = DateTime.parse('2026-10-06T10:00:00Z').millisecondsSinceEpoch;
api.Stop stop(
  String key,
  String name,
  double lat,
  double lon, {
  String planned = '2026-10-06T10:00:00Z',
  bool cancelled = false,
}) => api.Stop.fromJson({
  'uuid': key,
  'station': {
    'id': key.hashCode,
    'name': name,
    'latitude': lat,
    'longitude': lon,
  },
  'departurePlanned': planned,
  'cancelled': cancelled,
});
final essen = stop('essen-visit', 'Essen Hbf', 51.451, 7.014),
    muelheim = stop('muelheim-visit', 'Mülheim (Ruhr) Hbf', 51.431, 6.886),
    duisburg = stop('duisburg-visit', 'Duisburg Hbf', 51.429, 6.777),
    oberhausen = stop('oberhausen-visit', 'Oberhausen Hbf', 51.474, 6.852);
const toDuisburg = SevPoint(
  id: 'sev.136938',
  latitude: 51.43222557,
  longitude: 6.88553272,
  label: 'Richtung Duisburg \nbis zum 09.10.2026: Richtung Oberhausen',
);
const toEssen = SevPoint(
  id: 'sev.136949',
  latitude: 51.43175246,
  longitude: 6.88538831,
  label: 'Richtung Essen',
);
const temporary = 'Temporäre Ersatzhaltestellen vom 04.09 bis 30.10.2026:';
api.Checkin checkin([api.Stop? origin, api.Stop? destination]) =>
    api.Checkin.fromJson({
      'category': 'bus',
      'lineName': 'RE 1',
      'origin': origin?.toJson(),
      'destination': destination?.toJson(),
    });
SevMap map(
  api.Stop station,
  List<SevPoint> points, {
  List<String> notes = const [],
  int? fetched,
}) => SevMap(
  slug: SevStopResolver.stationSlug(station.station!)!,
  sourceUrl:
      'https://www.bahnhof.de/${SevStopResolver.stationSlug(station.station!)}/karte',
  stationLatitude: station.station!.latitude!,
  stationLongitude: station.station!.longitude!,
  points: points,
  notes: notes,
  fetchedAtMillis: fetched ?? now,
);
SevStopInfo resolve(List<api.Stop> route, SevMap data, {int? time}) =>
    SevStopResolver.resolve(checkin(), route, {
      data.slug: data,
    }, time ?? now)[SevStopResolver.visitKey(route.first)]!;
Map<String, dynamic> payload() => {
  'slug': 'essen-hbf',
  'location': {'latitude': 51.451355, 'longitude': 7.014793},
  'poi': {
    'RAIL_REPLACEMENT_TRANSPORT': [
      {
        'type': 'Feature',
        'id': 'sev.101840',
        'properties': {
          'type': 'RAIL_REPLACEMENT_TRANSPORT',
          'id': 'sev.101840',
          'name': r'$undefined',
          'version': '27.05.2025, 09:19',
        },
        'geometry': {
          'type': 'Point',
          'coordinates': [7.0101172, 51.45018831],
        },
      },
    ],
  },
  'notes': {
    'RAIL_REPLACEMENT_TRANSPORT': [
      {'body': 'Kruppstraße vor DSV.'},
    ],
  },
};
String page(dynamic data) {
  final record = '27:${jsonEncode(['\$', 'Map', null, data])}\n';
  return '<script>self.__next_f.push(${jsonEncode([1, record])})</script>';
}

void main() {
  test('parser extracts only official point and direction notes as JSON', () {
    final data = BahnhofSevParser.parse(page(payload()), 'essen-hbf', now)!;
    expect(data.points.single.id, 'sev.101840');
    expect(data.points.single.label, isNull);
    expect(data.points.single.latitude, 51.45018831);
    expect(data.stationLatitude, 51.451355);
  });
  test(
    'flight records split across scripts reassemble without executing expressions',
    () {
      final record = '27:${jsonEncode(payload())}\n', half = record.length ~/ 2;
      final html =
          '<script>self.__next_f.push(${jsonEncode([1, record.substring(0, half)])});</script>'
          '<SCRIPT async>self.__next_f.push(${jsonEncode([1, record.substring(half)])})</SCRIPT>'
          "<script>throw new Error('never execute')</script>";
      expect(
        BahnhofSevParser.parse(html, 'essen-hbf', now)?.points.single.id,
        'sev.101840',
      );
      expect(
        BahnhofSevParser.parse(
          '<script>self.__next_f.push([1,loadMap()])</script>',
          'essen-hbf',
          now,
        ),
        isNull,
      );
    },
  );
  test(
    'conflicting map snapshots and feature identities rejected, equal repetitions accepted',
    () {
      expect(
        BahnhofSevParser.parse(
          page([payload(), payload()]),
          'essen-hbf',
          now,
        )?.points.length,
        1,
      );
      final other = payload();
      (other['location'] as Map)['latitude'] = 51.45;
      expect(
        BahnhofSevParser.parse(page([payload(), other]), 'essen-hbf', now),
        isNull,
      );
      final bad = payload();
      (((bad['poi'] as Map)['RAIL_REPLACEMENT_TRANSPORT'] as List)
                  .single['properties']
              as Map)['id'] =
          'other';
      expect(BahnhofSevParser.parse(page(bad), 'essen-hbf', now), isNull);
    },
  );
  test('unclassified geometry and wrong coordinate numeric type rejected', () {
    for (final change in [
      ({
        'type': 'LineString',
        'coordinates': [7.0, 51.0],
      }),
      ({
        'type': 'Point',
        'coordinates': ['7.0', 51.0],
      }),
      ({
        'type': 'Point',
        'coordinates': [7.0, 91.0],
      }),
      ({
        'type': 'Point',
        'coordinates': [7.0, 51.0, 0],
      }),
    ]) {
      final bad = payload();
      ((bad['poi'] as Map)['RAIL_REPLACEMENT_TRANSPORT'] as List)
              .single['geometry'] =
          change;
      expect(BahnhofSevParser.parse(page(bad), 'essen-hbf', now), isNull);
    }
    expect(BahnhofSevParser.isValidSlug('../essen-hbf'), isFalse);
    expect(
      BahnhofSevParser.parse(page(payload()), 'duisburg-hbf', now),
      isNull,
    );
  });
  test('German and Unicode slug normalization preserves station names', () {
    expect(SevStopResolver.stationSlug(muelheim.station!), 'muelheim-ruhr-hbf');
    expect(
      SevStopResolver.stationSlug(
        api.Station.fromJson({'name': 'Düsseldorf Hbf'}),
      ),
      'duesseldorf-hbf',
    );
    expect(
      SevStopResolver.stationSlug(
        api.Station.fromJson({'name': 'Gare de Saint-Étienne'}),
      ),
      'gare-de-saint-etienne',
    );
  });
  test('only bus RE/RB candidates classify as replacement bus', () {
    expect(SevStopResolver.isReplacementBus(checkin()), isTrue);
    expect(
      SevStopResolver.isReplacementBus(
        api.Checkin.fromJson({'category': 'bus', 'lineName': 'SB1'}),
      ),
      isFalse,
    );
    expect(
      SevStopResolver.isReplacementBus(
        api.Checkin.fromJson({
          'category': 'regional',
          'mode': 'train',
          'lineName': 'RE1',
        }),
      ),
      isFalse,
    );
  });
  test(
    'single unlabelled point changes physical coordinate only, never API identity',
    () {
      const point = SevPoint(
        id: 'sev',
        latitude: 51.45018831,
        longitude: 7.0101172,
      );
      final info = resolve([essen, muelheim], map(essen, [point]));
      expect(info.latitude, point.latitude);
      expect(essen.station!.latitude, 51.451);
      expect(essen.uuid, 'essen-visit');
    },
  );
  test('forward route proves direction and reverse route selects opposite', () {
    final data = map(muelheim, [toEssen, toDuisburg], notes: [temporary]);
    expect(resolve([muelheim, duisburg], data).label, toDuisburg.label);
    expect(resolve([muelheim, essen], data).label, toEssen.label);
    expect(resolve([muelheim], data).hasCoordinates, isFalse);
  });
  test(
    'unlabelled competing point and two matching directions are ambiguous',
    () {
      expect(
        resolve(
          [muelheim, duisburg],
          map(
            muelheim,
            [
              toDuisburg,
              const SevPoint(id: 'other', latitude: 51.431, longitude: 6.886),
            ],
            notes: [temporary],
          ),
        ).hasCoordinates,
        isFalse,
      );
      expect(
        resolve(
          [muelheim, duisburg],
          map(
            muelheim,
            [
              toDuisburg,
              const SevPoint(
                id: 'other',
                latitude: 51.431,
                longitude: 6.886,
                label: 'Richtung Duisburg',
              ),
            ],
            notes: [temporary],
          ),
        ).hasCoordinates,
        isFalse,
      );
    },
  );
  test(
    'extra directional deadline expires without expiring ordinary direction',
    () {
      final later = DateTime.parse(
        '2026-10-10T10:00:00Z',
      ).millisecondsSinceEpoch;
      final current = stop(
        'muelheim-visit',
        'Mülheim (Ruhr) Hbf',
        51.431,
        6.886,
        planned: '2026-10-10T10:00:00Z',
      );
      final data = map(
        current,
        [toDuisburg, toEssen],
        notes: [temporary],
        fetched: later,
      );
      expect(
        resolve([current, oberhausen], data, time: later).hasCoordinates,
        isFalse,
      );
      expect(
        resolve([current, duisburg], data, time: later).hasCoordinates,
        isTrue,
      );
    },
  );
  test('Berlin date boundary governs last directional day', () {
    final last = DateTime.parse('2026-10-09T21:59:59Z').millisecondsSinceEpoch;
    final current = stop(
      'muelheim-visit',
      'Mülheim (Ruhr) Hbf',
      51.431,
      6.886,
      planned: '2026-10-09T21:59:59Z',
    );
    final data = map(
      current,
      [toDuisburg, toEssen],
      notes: [temporary],
      fetched: last,
    );
    expect(
      resolve([current, oberhausen], data, time: last).hasCoordinates,
      isTrue,
    );
    expect(
      resolve([current, oberhausen], data, time: last + 1000).hasCoordinates,
      isFalse,
    );
  });
  test('whole map validity checks both today and planned visit date', () {
    final future = stop(
      'muelheim-visit',
      'Mülheim (Ruhr) Hbf',
      51.431,
      6.886,
      planned: '2026-10-31T10:00:00Z',
    );
    expect(
      resolve(
        [future, duisburg],
        map(muelheim, [toDuisburg, toEssen], notes: [temporary]),
      ).hasCoordinates,
      isFalse,
    );
    final expired = DateTime.parse(
      '2026-10-31T10:00:00Z',
    ).millisecondsSinceEpoch;
    expect(
      resolve(
        [essen],
        map(
          essen,
          [const SevPoint(id: 'sev', latitude: 51.451, longitude: 7.014)],
          notes: [temporary],
          fetched: expired,
        ),
        time: expired,
      ).hasCoordinates,
      isFalse,
    );
  });
  for (final note in [
    'Temporäre Ersatzhaltestellen',
    'Temporäre Ersatzhaltestellen vom 31.02 bis 30.10.2026',
    'Die Ersatzhaltestelle gilt ab dem 31.10.2026.',
    '$temporary Die Ersatzhaltestelle gilt nur bis zum 05.10.2026.',
    'vom 01.10.2026 bis 31.10.2026 und vom 01.11.2026 bis 30.11.2026',
  ]) {
    test('uncertain date notice stays guidance without positions: $note', () {
      final info = resolve(
        [essen],
        map(
          essen,
          [const SevPoint(id: 'sev', latitude: 51.451, longitude: 7.014)],
          notes: [note],
        ),
      );
      expect(info.hasCoordinates, isFalse);
      expect(info.guidance, note);
      expect(info.reason, isNotNull);
    });
  }
  test('stale map and remote station centre never authorize coordinates', () {
    expect(
      resolve(
        [essen],
        map(essen, [
          const SevPoint(id: 'sev', latitude: 51.451, longitude: 7.014),
        ], fetched: now - 86400001),
      ).hasCoordinates,
      isFalse,
    );
    final data = map(muelheim, [toDuisburg]);
    final wrong = SevMap(
      slug: data.slug,
      sourceUrl: data.sourceUrl,
      stationLatitude: 0,
      stationLongitude: 0,
      points: data.points,
      notes: [],
      fetchedAtMillis: now,
    );
    expect(resolve([muelheim, duisburg], wrong).hasCoordinates, isFalse);
  });
  test(
    'enrichment requests only unique checked window and skips cancelled stations',
    () {
      final route = [essen, muelheim, duisburg, oberhausen];
      expect(
        SevJourneyEnricher.stationSlugs(checkin(muelheim, duisburg), route),
        ['muelheim-ruhr-hbf', 'duisburg-hbf'],
      );
      expect(
        SevJourneyEnricher.stationSlugs(checkin(muelheim, duisburg), [
          essen,
          muelheim,
          muelheim,
          duisburg,
        ]),
        isEmpty,
      );
    },
  );
  test(
    'anonymous repository singleflight and cache do not send API credentials',
    () async {
      var calls = 0;
      final client = MockClient((request) async {
        calls++;
        expect(request.headers.containsKey('authorization'), isFalse);
        expect(request.url.host, 'www.bahnhof.de');
        return http.Response(
          page(payload()),
          200,
          headers: {'content-type': 'text/html; charset=utf-8'},
        );
      });
      final repo = BahnhofSevRepository(client: client, nowMillis: () => now);
      final results = await Future.wait([
        repo.getMap('essen-hbf'),
        repo.getMap('essen-hbf'),
      ]);
      expect(results.every((m) => m != null), isTrue);
      expect(calls, 1);
      await repo.getMap('essen-hbf');
      expect(calls, 1);
      repo.close();
      client.close();
    },
  );
  test(
    'repository rejects redirects away from official host and caches failure',
    () async {
      var calls = 0;
      final client = MockClient((request) async {
        calls++;
        return http.Response(
          '',
          302,
          headers: {'location': 'https://example.com/steal'},
        );
      });
      final repo = BahnhofSevRepository(client: client);
      expect(await repo.getMap('essen-hbf'), isNull);
      expect(await repo.getMap('essen-hbf'), isNull);
      expect(calls, 1);
      repo.close();
      client.close();
    },
  );
}
