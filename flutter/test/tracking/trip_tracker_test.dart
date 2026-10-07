import 'package:flutter_test/flutter_test.dart';

import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/trip_tracker.dart';
import 'package:routely/tracking/gps_journey_time_estimator.dart';

final base = DateTime.parse('2026-10-05T18:00:00Z').millisecondsSinceEpoch;
List<TrackingStop> stops() => [
  TrackingStop(
    key: 'origin',
    name: 'Origin',
    stationId: 1,
    latitude: 50,
    longitude: 0,
    isOrigin: true,
    plannedDepartureMillis: base,
    effectiveDepartureMillis: base,
  ),
  TrackingStop(
    key: 'middle',
    name: 'Middle',
    stationId: 2,
    latitude: 50,
    longitude: .02,
    plannedArrivalMillis: base + 120000,
    effectiveArrivalMillis: base + 120000,
    plannedDepartureMillis: base + 150000,
    effectiveDepartureMillis: base + 150000,
  ),
  TrackingStop(
    key: 'destination',
    name: 'Destination',
    stationId: 3,
    latitude: 50,
    longitude: .04,
    isDestination: true,
    plannedArrivalMillis: base + 270000,
    effectiveArrivalMillis: base + 270000,
    plannedDepartureMillis: base + 300000,
    effectiveDepartureMillis: base + 300000,
  ),
];
TrackingJourney journey(
  List<TrackingStop> route, {
  int revision = 0,
  String trip = 'trip:1',
  String session = 'session',
  int? manual,
}) => TrackingJourney(
  statusId: 42,
  sessionRevision: session,
  stops: route,
  contentRevision: revision,
  tripIdentity: trip,
  manualArrivalMillis: manual,
);
LocationFix fix(double lon, int time) => LocationFix(
  latitude: 50,
  longitude: lon,
  accuracyMeters: 10,
  timeMillis: time,
  speedMetersPerSecond: 1,
);
void main() {
  test(
    'backward wall-clock correction resets ETA watermark and accepts new monotonic evidence',
    () {
      final tracker = TripTracker();
      tracker.start(
        journey(stops()),
        progress: const TrackingProgress(
          nextIndex: 1,
          nextStopKey: 'middle',
          gpsEstablished: true,
        ),
      );
      const monoBase = 1000000000000;
      TripTrackingSnapshot? snapshot;
      for (final fraction in [.25, .30, .35]) {
        final time = base + (120000 * fraction).round();
        final nanos = monoBase + (time - base) * 1000000;
        snapshot = tracker.observeNativeLocation(
          latitude: 50,
          longitude: .02 * fraction,
          accuracyMeters: 10,
          monotonicNanos: nanos,
          nowMonotonicNanos: nanos,
          nowMillis: time,
          hasSpeed: true,
          speedMetersPerSecond: 12,
        );
      }
      expect(snapshot!.gpsTimes, isNotNull);
      final before = snapshot.gpsTimes!.updatedAtMillis;
      for (var i = 0; i < 3; i++) {
        final nanos = monoBase + (48000 + i * 6000) * 1000000;
        final time = base + 48000 + i * 6000 - 600000;
        snapshot = tracker.observeNativeLocation(
          latitude: 50,
          longitude: .02 * (.40 + i * .05),
          accuracyMeters: 10,
          monotonicNanos: nanos,
          nowMonotonicNanos: nanos,
          nowMillis: time,
          hasSpeed: true,
          speedMetersPerSecond: 12,
        );
        if (i < 2) expect(snapshot!.gpsTimes, isNull);
      }
      expect(snapshot!.gpsTimes, isNotNull);
      expect(snapshot.gpsTimes!.updatedAtMillis, lessThan(before));
      expect(snapshot.etaReason, isNull);
    },
  );
  test(
    'edited destination reopens completed journey with concrete current visit and speech keys',
    () {
      final tracker = TripTracker(), original = stops();
      tracker.start(
        journey(original),
        progress: const TrackingProgress(
          nextIndex: 2,
          nextStopKey: 'destination',
          gpsEstablished: true,
          announcedKeys: {'middle', 'outside'},
        ),
      );
      tracker.update(nowMillis: base + 270000, fix: fix(.04, base + 270000));
      final completed = tracker.update(
        nowMillis: base + 273000,
        fix: fix(.04, base + 273000),
      )!;
      expect(completed.progress.completed, isTrue);
      final extended = List<TrackingStop>.of(original);
      extended[2] = extended[2].copyWith(isDestination: false);
      extended.add(
        TrackingStop(
          key: 'new-destination',
          name: 'New Destination',
          stationId: 4,
          latitude: 50,
          longitude: .06,
          isDestination: true,
          plannedArrivalMillis: base + 420000,
          effectiveArrivalMillis: base + 420000,
        ),
      );
      expect(tracker.updateRoute(journey(extended, revision: 1)), isTrue);
      final reopened = tracker.update(nowMillis: base + 274000)!;
      expect(reopened.progress.completed, isFalse);
      expect(reopened.update.destinationReached, isFalse);
      expect(reopened.progress.nextStopKey, 'destination');
      expect(reopened.progress.arrivedAtCurrent, isTrue);
      expect(reopened.progress.announcedKeys.contains('middle'), isTrue);
      expect(reopened.progress.announcedKeys.contains('outside'), isFalse);
      expect(reopened.gpsTimes, isNull);
      expect(tracker.updateRoute(journey(original, revision: 0)), isFalse);
    },
  );
  test(
    'changed trip on same status resets old ordered progress and no cache crosses sessions',
    () {
      final tracker = TripTracker();
      tracker.start(
        journey(stops()),
        progress: const TrackingProgress(
          nextIndex: 2,
          nextStopKey: 'destination',
          completed: true,
          gpsEstablished: true,
          announcedKeys: {'middle'},
        ),
      );
      expect(
        tracker.updateRoute(
          journey(stops(), trip: 'different-trip', revision: 1),
        ),
        isTrue,
      );
      final state = tracker.checkpoint()!['progress'] as Map;
      expect(state['nextIndex'], 0);
      expect(state['completed'], isFalse);
      expect(state['announcedKeys'], isEmpty);
      final restored = TripTracker();
      expect(
        restored.restore(
          tracker.checkpoint()!,
          expectedSessionRevision: 'other-session',
          expectedStatusId: 42,
        ),
        isFalse,
      );
      expect(
        restored.restore(
          tracker.checkpoint()!,
          expectedSessionRevision: 'session',
          expectedStatusId: 42,
        ),
        isTrue,
      );
      expect(restored.snapshot, isNull);
    },
  );
  test('manual event edits invalidate GPS while retaining visit cursor', () {
    final tracker = TripTracker();
    tracker.start(
      journey(stops()),
      progress: const TrackingProgress(
        nextIndex: 1,
        nextStopKey: 'middle',
        gpsEstablished: true,
      ),
    );
    for (final fraction in [.25, .30, .35]) {
      final time = base + (120000 * fraction).round();
      tracker.update(nowMillis: time, fix: fix(.02 * fraction, time));
    }
    expect(tracker.snapshot!.gpsTimes, isNotNull);
    tracker.updateRoute(journey(stops(), revision: 1, manual: base + 900000));
    final updated = tracker.update(nowMillis: base + 43000)!;
    expect(updated.progress.nextStopKey, 'middle');
    expect(updated.gpsTimes, isNull);
    expect(updated.etaReason, GpsTimeUnavailableReason.noFreshLocation);
  });
  test(
    'endpoint removal follows old surviving visit rather than matching station ID',
    () {
      final tracker = TripTracker();
      tracker.start(
        journey(stops()),
        progress: const TrackingProgress(
          nextIndex: 1,
          nextStopKey: 'middle',
          gpsEstablished: true,
        ),
      );
      final newStops = [stops().first, stops().last];
      newStops[0] = newStops[0].copyWith(isOrigin: false);
      newStops[1] = newStops[1].copyWith(isOrigin: true);
      tracker.updateRoute(journey(newStops, revision: 1));
      final cached = tracker.checkpoint()!['progress'] as Map;
      expect(cached['nextStopKey'], 'destination');
      expect(cached['nextIndex'], 1);
      expect(cached['arrivedAtCurrent'], isFalse);
    },
  );
}
