import 'package:flutter_test/flutter_test.dart';
import 'package:routely/tracking/trip_change_monitor.dart';

TripChangeStop stop({
  String key = 'destination',
  int delay = 0,
  bool? cancelled = false,
  String? platform = '1',
  bool? live = true,
  bool destination = true,
}) => TripChangeStop(
  key: key,
  name: key,
  arrivalPlannedMillis: 1000000,
  arrivalRealMillis: 1000000 + delay * 60000,
  arrivalPlatform: platform,
  arrivalPlatformIsLive: live,
  cancelled: cancelled,
  isDestination: destination,
);
TripChangeSnapshot snap(
  int time,
  List<TripChangeStop> stops, {
  int statusId = 1,
  int next = 0,
  int? manual,
}) => TripChangeSnapshot(
  statusId: statusId,
  observedAtMillis: time,
  stops: stops,
  nextIndex: next,
  manualArrivalMillis: manual,
);
void main() {
  test('first successful load and restored baseline stay silent', () {
    final monitor = TripChangeMonitor();
    expect(monitor.observe(snap(1, [stop()])), isEmpty);
    final saved = monitor.getState();
    monitor.reset(1, state: saved);
    expect(monitor.observe(snap(2, [stop(delay: 10, platform: '7')])), isEmpty);
  });
  test('changed platform alerts only next served and destination visits', () {
    final monitor = TripChangeMonitor();
    monitor.observe(
      snap(1, [
        stop(key: 'next', destination: false),
        stop(key: 'later', destination: false),
        stop(),
      ]),
    );
    final events = monitor.observe(
      snap(2, [
        stop(key: 'next', destination: false, platform: '2'),
        stop(key: 'later', destination: false, platform: '2'),
        stop(platform: '2'),
      ]),
    );
    expect(events.map((e) => e.stopKey).toSet(), {'next', 'destination'});
    expect(events.every((e) => e.kind == TripChangeKind.platform), isTrue);
    expect(
      monitor.observe(
        snap(3, [
          stop(key: 'next', destination: false, platform: '2'),
          stop(key: 'later', destination: false, platform: '2'),
          stop(platform: '2'),
        ]),
      ),
      isEmpty,
    );
  });
  test('realtime platform gaps silently reestablish live baseline', () {
    final monitor = TripChangeMonitor();
    monitor.observe(snap(1, [stop(platform: '8')]));
    expect(
      monitor.observe(snap(2, [stop(platform: '1', live: false)])),
      isEmpty,
    );
    expect(monitor.observe(snap(3, [stop(platform: '9')])), isEmpty);
    expect(
      monitor.observe(snap(4, [stop(platform: '10')])).single.kind,
      TripChangeKind.platform,
    );
  });
  test(
    'small delay increments accumulate to threshold relative to last announcement',
    () {
      final monitor = TripChangeMonitor();
      monitor.observe(snap(1, [stop()]));
      expect(monitor.observe(snap(2, [stop(delay: 3)])), isEmpty);
      expect(
        monitor.observe(snap(3, [stop(delay: 5)])).single.kind,
        TripChangeKind.delayIncrease,
      );
      expect(monitor.observe(snap(4, [stop(delay: 7)])), isEmpty);
      expect(
        monitor.observe(snap(5, [stop(delay: 0)])).single.kind,
        TripChangeKind.delayDecrease,
      );
    },
  );
  test(
    'manual event changes reset delay baseline rather than spoofing API alert',
    () {
      final monitor = TripChangeMonitor();
      monitor.observe(snap(1, [stop()]));
      expect(monitor.observe(snap(2, [stop(delay: 10)], manual: 123)), isEmpty);
      expect(monitor.observe(snap(3, [stop(delay: 12)], manual: 123)), isEmpty);
      expect(
        monitor.observe(snap(4, [stop(delay: 15)], manual: 123)).single.kind,
        TripChangeKind.delayIncrease,
      );
    },
  );
  test(
    'cancellation and restoration are explicit, past visits never alert',
    () {
      final monitor = TripChangeMonitor();
      monitor.observe(snap(1, [stop(key: 'past'), stop()]));
      final events = monitor.observe(
        snap(2, [
          stop(key: 'past', cancelled: true),
          stop(cancelled: true),
        ], next: 1),
      );
      expect(events.length, 1);
      expect(events.single.stopKey, 'destination');
      expect(events.single.kind, TripChangeKind.cancelled);
      expect(
        monitor
            .observe(snap(3, [stop(key: 'past'), stop()], next: 1))
            .single
            .kind,
        TripChangeKind.restored,
      );
    },
  );
  test(
    'older provider snapshot and duplicate visit identities never alter baseline',
    () {
      final monitor = TripChangeMonitor();
      monitor.observe(snap(10, [stop()]));
      expect(monitor.observe(snap(9, [stop(platform: '9')])), isEmpty);
      expect(monitor.observe(snap(11, [stop(), stop(platform: '9')])), isEmpty);
      expect(
        monitor.observe(snap(12, [stop(platform: '2')])).single.kind,
        TripChangeKind.platform,
      );
    },
  );
  test('another trip installs its own silent baseline', () {
    final monitor = TripChangeMonitor();
    monitor.observe(snap(1, [stop()]));
    expect(
      monitor.observe(snap(2, [stop(delay: 50, platform: '9')], statusId: 2)),
      isEmpty,
    );
  });
}
