import 'package:flutter_test/flutter_test.dart';
import 'package:routely/tracking/models.dart';
import 'package:routely/tracking/trip_progress.dart';

TrackingStop stop(
  String key, {
  bool origin = false,
  bool destination = false,
  bool cancelled = false,
}) => TrackingStop(
  key: key,
  name: key,
  stationId: 1,
  isOrigin: origin,
  isDestination: destination,
  cancelled: cancelled,
  plannedArrivalMillis: 1000000,
);
TripProgressModel model(
  List<TrackingStop> route,
  TrackingProgress progress, {
  TrackingSource source = TrackingSource.gps,
}) => TripProgressModel.from(
  stops: route,
  progress: progress,
  source: source,
  nowMillis: 1000000,
  localTime: (millis) =>
      DateTime.fromMillisecondsSinceEpoch(millis, isUtc: true),
);
void main() {
  final route = [
    stop('origin', origin: true),
    stop('middle'),
    stop('destination', destination: true),
  ];
  test('origin excluded from remaining halt count', () {
    final progress = model(
      route,
      const TrackingProgress(nextStopKey: 'origin'),
    );
    expect(progress.totalStops, 2);
    expect(progress.remainingStops, 2);
    expect(progress.progress, 0);
  });
  test(
    'current arrival counts that visit while unconfirmed destination stays below maximum',
    () {
      final progress = model(
        route,
        const TrackingProgress(
          nextIndex: 2,
          nextStopKey: 'destination',
          arrivedAtCurrent: true,
        ),
      );
      expect(progress.remainingStops, 0);
      expect(progress.remainingText, 'Am Ziel · Ankunft wird geprüft');
      expect(progress.progress, 199);
      expect(progress.progressMax, 200);
      expect(progress.completed, isFalse);
    },
  );
  test(
    'clock cursor at destination is approximate and cannot claim completion',
    () {
      final progress = model(
        route,
        const TrackingProgress(nextIndex: 2, nextStopKey: 'destination'),
        source: TrackingSource.timetable,
      );
      expect(progress.remainingStops, 1);
      expect(progress.approximate, isTrue);
      expect(progress.completed, isFalse);
      expect(progress.progress, lessThan(progress.progressMax));
    },
  );
  test('confirmed destination allows complete progress maximum', () {
    final progress = model(
      route,
      const TrackingProgress(
        nextIndex: 2,
        nextStopKey: 'destination',
        completed: true,
      ),
    );
    expect(progress.remainingStops, 0);
    expect(progress.progress, progress.progressMax);
    expect(progress.shouldPromote, isFalse);
  });
  test(
    'cancelled visits skipped and cancelled destination marked separately',
    () {
      final progress = model([
        stop('origin', origin: true),
        stop('cancelled', cancelled: true),
        stop('destination', destination: true, cancelled: true),
      ], const TrackingProgress(nextStopKey: 'cancelled'));
      expect(progress.totalStops, 0);
      expect(progress.remainingStops, isNull);
      expect(progress.destinationCancelled, isTrue);
      expect(progress.remainingText, 'Zielhalt entfällt');
      expect(progress.arrivalText, isNull);
    },
  );
  test(
    'bare numeric index and ambiguous visit key cannot identify progress',
    () {
      expect(
        model(route, const TrackingProgress(nextIndex: 1)).remainingStops,
        isNull,
      );
      expect(
        model([
          stop('origin'),
          stop('middle'),
          stop('middle'),
        ], const TrackingProgress(nextStopKey: 'middle')).remainingStops,
        isNull,
      );
    },
  );
  test(
    'insertion preserves exact visit identity and excludes skipped cancelled stop',
    () {
      final progress = model([
        stop('origin'),
        stop('inserted'),
        stop('cancelled', cancelled: true),
        stop('middle'),
        stop('destination'),
      ], const TrackingProgress(nextIndex: 1, nextStopKey: 'middle'));
      expect(progress.passedStops, 1);
      expect(progress.remainingStops, 2);
      expect(progress.nextStopName, 'middle');
    },
  );
  test('separate return visits to one physical station remain counted', () {
    final progress = model([
      stop('origin'),
      stop('middle'),
      stop('origin-return'),
    ], const TrackingProgress(nextStopKey: 'middle'));
    expect(progress.totalStops, 2);
    expect(progress.remainingStops, 2);
  });
}
