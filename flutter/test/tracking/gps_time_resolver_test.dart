import 'package:flutter_test/flutter_test.dart';

import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/gps_journey_time_estimator.dart';
import 'package:routely/tracking/journey_time_resolver.dart';

void main() {
  const arrival = 1791224040000,
      departure = arrival + 60000,
      now = arrival - 120000;
  const stop = TrackingStop(
    key: 'visit',
    name: 'Station',
    stationId: 7,
    plannedArrivalMillis: arrival,
    plannedDepartureMillis: departure,
    arrivalRealMillis: arrival + 300000,
    departureRealMillis: departure + 300000,
  );
  GpsJourneyTimes gps({int? a = arrival - 120000, bool observed = false}) =>
      GpsJourneyTimes(
        updatedAtMillis: now,
        validUntilMillis: now + 30000,
        stopTimes: [
          GpsStopTime(
            stopKey: 'visit',
            stationId: 7,
            plannedArrivalMillis: arrival,
            plannedDepartureMillis: departure,
            arrivalMillis: a,
            departureMillis: departure - 120000,
            arrivalObserved: observed,
          ),
        ],
      );
  test('event-independent source priority and delay preserve early GPS', () {
    final result = JourneyTimeResolver.arrival(
      stop,
      gps(),
      now,
      manualTime: DateTime.fromMillisecondsSinceEpoch(
        arrival + 600000,
        isUtc: true,
      ).toIso8601String(),
    )!;
    expect(result.source, JourneyTimeSource.gpsEstimate);
    expect(result.delayMinutes, -2);
    expect(result.sourceLabel, 'GPS-Schätzung');
    expect(
      JourneyTimeResolver.arrival(stop, gps(observed: true), now)!.source,
      JourneyTimeSource.gpsObserved,
    );
    expect(
      JourneyTimeResolver.arrival(stop, gps(a: null), now)!.source,
      JourneyTimeSource.apiRealtime,
    );
    expect(
      JourneyTimeResolver.departure(stop, gps(a: null), now)!.source,
      JourneyTimeSource.gpsEstimate,
    );
  });
  test(
    'expired GPS falls back to manual then API then plan without mutation',
    () {
      expect(
        JourneyTimeResolver.arrival(stop, gps(), now + 30001)!.millis,
        arrival + 300000,
      );
      expect(
        JourneyTimeResolver.arrival(
          stop,
          gps(),
          now + 30001,
          manualTime: '2026-10-05T18:14:00Z',
        )!.source,
        JourneyTimeSource.manual,
      );
      expect(
        JourneyTimeResolver.arrival(stop, null, now, manualTime: 'bad')!.source,
        JourneyTimeSource.apiRealtime,
      );
      expect(
        JourneyTimeResolver.arrival(
          stop.copyWith(arrivalRealMillis: null),
          null,
          now,
        )!.source,
        JourneyTimeSource.timetable,
      );
      expect(stop.arrivalRealMillis, arrival + 300000);
    },
  );
  test('manual clock affects only unique concrete visits and both events', () {
    final repeated = stop.copyWith(
      key: 'return',
      plannedArrivalMillis: arrival + 3600000,
    );
    final projected = JourneyTimeResolver.manualTimelineStops(
      [stop, repeated],
      stop,
      repeated,
      '2026-10-05T18:13:00Z',
      '2026-10-05T19:16:00Z',
    );
    expect(projected.first.arrivalRealMillis, stop.arrivalRealMillis);
    expect(
      projected.first.departureRealMillis,
      DateTime.parse('2026-10-05T18:13:00Z').millisecondsSinceEpoch,
    );
    expect(
      projected.last.arrivalRealMillis,
      DateTime.parse('2026-10-05T19:16:00Z').millisecondsSinceEpoch,
    );
    final ambiguous = JourneyTimeResolver.manualTimelineStops(
      [stop, stop],
      stop,
      stop,
      '2026-10-05T18:13:00Z',
      '2026-10-05T18:12:00Z',
    );
    expect(
      ambiguous.every(
        (s) =>
            s.arrivalRealMillis == stop.arrivalRealMillis &&
            s.departureRealMillis == stop.departureRealMillis,
      ),
      isTrue,
    );
  });
}
