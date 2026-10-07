import 'package:flutter_test/flutter_test.dart';
import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/trip_tracker.dart';

void main() {
  test(
    'new native clock origin consumes fresh fix while preserving established visit and speech',
    () {
      const now = 1800000000000;
      final tracker = TripTracker();
      tracker.start(
        TrackingJourney(
          statusId: 1,
          sessionRevision: 'session',
          stops: [
            const TrackingStop(
              key: 'origin',
              name: 'Start',
              stationId: 1,
              latitude: 0,
              longitude: 0,
              isOrigin: true,
              effectiveDepartureMillis: now + 60000,
              plannedDepartureMillis: now + 60000,
            ),
            const TrackingStop(
              key: 'destination',
              name: 'Ziel',
              stationId: 2,
              latitude: 0,
              longitude: 0.02,
              isDestination: true,
              effectiveArrivalMillis: now + 600000,
              plannedArrivalMillis: now + 600000,
            ),
          ],
        ),
      );
      TripTrackingSnapshot observe(int mono, int wall) =>
          tracker.observeNativeLocation(
            latitude: 0,
            longitude: 0,
            accuracyMeters: 10,
            monotonicNanos: mono,
            nowMonotonicNanos: mono,
            nowMillis: wall,
            hasSpeed: true,
            speedMetersPerSecond: 0,
          )!;
      observe(10000000000, now);
      final before = observe(13000000000, now + 3000);
      expect(before.update.announcement?.key, 'origin');
      tracker.acknowledgeAnnouncement('origin');
      tracker.resetNativeClock();
      final resumed = observe(1000000000, now + 4000);
      expect(resumed.progress.nextStopKey, 'origin');
      expect(resumed.progress.gpsEstablished, isTrue);
      expect(resumed.progress.announcedKeys, {'origin'});
      expect(resumed.update.source, TrackingSource.gps);
      expect(resumed.update.announcement, isNull);
    },
  );
}
