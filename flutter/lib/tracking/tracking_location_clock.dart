import 'models.dart';
import 'station_tracking_engine.dart';

class TrackingLocationObservation {
  const TrackingLocationObservation(
    this.fix, {
    required this.clockChanged,
    required this.isNew,
  });
  final LocationFix? fix;
  final bool clockChanged, isNew;
}

/// Generation-local clock adapter. Keeps the provider's monotonic watermark
/// through wall-clock corrections and never treats cached fixes as movement.
class TrackingLocationClock {
  int? _latestMonotonicNanos, _clockOffsetMillis;
  LocationFix? _cachedFix;
  TrackingLocationObservation observe({
    required double latitude,
    required double longitude,
    required double accuracyMeters,
    required int monotonicNanos,
    required int nowMonotonicNanos,
    required int nowMillis,
    required bool hasSpeed,
    required double speedMetersPerSecond,
  }) {
    if (nowMonotonicNanos <= 0 || nowMillis <= 0) {
      return const TrackingLocationObservation(
        null,
        clockChanged: false,
        isNew: false,
      );
    }
    final offset = nowMillis - nowMonotonicNanos ~/ 1000000;
    final changed =
        _clockOffsetMillis != null &&
        (offset - _clockOffsetMillis!).abs() > 1000;
    _clockOffsetMillis = offset;
    if (changed) _cachedFix = null;
    if (monotonicNanos <= 0 || monotonicNanos > nowMonotonicNanos) {
      return TrackingLocationObservation(
        null,
        clockChanged: changed,
        isNew: false,
      );
    }
    final age = nowMonotonicNanos - monotonicNanos;
    if (age > StationTrackingEngine.maxFixAgeMillis * 1000000) {
      return TrackingLocationObservation(
        null,
        clockChanged: changed,
        isNew: false,
      );
    }
    if (_latestMonotonicNanos != null &&
        monotonicNanos <= _latestMonotonicNanos!) {
      return TrackingLocationObservation(
        monotonicNanos == _latestMonotonicNanos ? _cachedFix : null,
        clockChanged: changed,
        isNew: false,
      );
    }
    final eventTime = nowMillis - age ~/ 1000000;
    if (eventTime <= 0) {
      return TrackingLocationObservation(
        null,
        clockChanged: changed,
        isNew: false,
      );
    }
    _latestMonotonicNanos = monotonicNanos;
    _cachedFix = LocationFix(
      latitude: latitude,
      longitude: longitude,
      accuracyMeters: accuracyMeters,
      timeMillis: eventTime,
      speedMetersPerSecond: hasSpeed ? speedMetersPerSecond : null,
      monotonicNanos: monotonicNanos,
    );
    return TrackingLocationObservation(
      _cachedFix,
      clockChanged: changed,
      isNew: true,
    );
  }
}
