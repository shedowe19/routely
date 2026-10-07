import 'dart:math' as math;

enum TrackingSource { gps, timetable }

/// A concrete ordered visit; [key] is never just a station identifier.
class TrackingStop {
  const TrackingStop({
    required this.key,
    this.stationId,
    required this.name,
    this.latitude,
    this.longitude,
    this.plannedArrivalMillis,
    this.effectiveArrivalMillis,
    this.effectiveDepartureMillis,
    this.cancelled = false,
    this.isOrigin = false,
    this.isDestination = false,
    this.plannedDepartureMillis,
    this.arrivalRealMillis,
    this.departureRealMillis,
    this.arrivalPlatform,
    this.departurePlatform,
  });
  final String key, name;
  final int? stationId;
  final double? latitude, longitude;
  final int? plannedArrivalMillis,
      effectiveArrivalMillis,
      effectiveDepartureMillis,
      plannedDepartureMillis,
      arrivalRealMillis,
      departureRealMillis;
  final String? arrivalPlatform, departurePlatform;
  final bool cancelled, isOrigin, isDestination;
  TrackingStop copyWith({
    String? key,
    String? name,
    int? stationId,
    double? latitude,
    double? longitude,
    Object? plannedArrivalMillis = _unset,
    Object? plannedDepartureMillis = _unset,
    Object? effectiveArrivalMillis = _unset,
    Object? effectiveDepartureMillis = _unset,
    Object? arrivalRealMillis = _unset,
    Object? departureRealMillis = _unset,
    bool? cancelled,
    bool? isOrigin,
    bool? isDestination,
  }) => TrackingStop(
    key: key ?? this.key,
    name: name ?? this.name,
    stationId: stationId ?? this.stationId,
    latitude: latitude ?? this.latitude,
    longitude: longitude ?? this.longitude,
    plannedArrivalMillis: identical(plannedArrivalMillis, _unset)
        ? this.plannedArrivalMillis
        : plannedArrivalMillis as int?,
    plannedDepartureMillis: identical(plannedDepartureMillis, _unset)
        ? this.plannedDepartureMillis
        : plannedDepartureMillis as int?,
    effectiveArrivalMillis: identical(effectiveArrivalMillis, _unset)
        ? this.effectiveArrivalMillis
        : effectiveArrivalMillis as int?,
    effectiveDepartureMillis: identical(effectiveDepartureMillis, _unset)
        ? this.effectiveDepartureMillis
        : effectiveDepartureMillis as int?,
    arrivalRealMillis: identical(arrivalRealMillis, _unset)
        ? this.arrivalRealMillis
        : arrivalRealMillis as int?,
    departureRealMillis: identical(departureRealMillis, _unset)
        ? this.departureRealMillis
        : departureRealMillis as int?,
    arrivalPlatform: arrivalPlatform,
    departurePlatform: departurePlatform,
    cancelled: cancelled ?? this.cancelled,
    isOrigin: isOrigin ?? this.isOrigin,
    isDestination: isDestination ?? this.isDestination,
  );
  bool get hasCoordinates =>
      latitude != null &&
      longitude != null &&
      validCoordinates(latitude!, longitude!);
  Map<String, Object?> toJson() => {
    'key': key,
    'stationId': stationId,
    'name': name,
    'latitude': latitude,
    'longitude': longitude,
    'plannedArrivalMillis': plannedArrivalMillis,
    'effectiveArrivalMillis': effectiveArrivalMillis,
    'effectiveDepartureMillis': effectiveDepartureMillis,
    'plannedDepartureMillis': plannedDepartureMillis,
    'arrivalRealMillis': arrivalRealMillis,
    'departureRealMillis': departureRealMillis,
    'arrivalPlatform': arrivalPlatform,
    'departurePlatform': departurePlatform,
    'cancelled': cancelled,
    'isOrigin': isOrigin,
    'isDestination': isDestination,
  };
  factory TrackingStop.fromJson(Map<String, dynamic> j) => TrackingStop(
    key: j['key'] as String,
    name: j['name'] as String,
    stationId: j['stationId'] as int?,
    latitude: (j['latitude'] as num?)?.toDouble(),
    longitude: (j['longitude'] as num?)?.toDouble(),
    plannedArrivalMillis: j['plannedArrivalMillis'] as int?,
    effectiveArrivalMillis: j['effectiveArrivalMillis'] as int?,
    effectiveDepartureMillis: j['effectiveDepartureMillis'] as int?,
    plannedDepartureMillis: j['plannedDepartureMillis'] as int?,
    arrivalRealMillis: j['arrivalRealMillis'] as int?,
    departureRealMillis: j['departureRealMillis'] as int?,
    arrivalPlatform: j['arrivalPlatform'] as String?,
    departurePlatform: j['departurePlatform'] as String?,
    cancelled: j['cancelled'] == true,
    isOrigin: j['isOrigin'] == true,
    isDestination: j['isDestination'] == true,
  );
}

class LocationFix {
  const LocationFix({
    required this.latitude,
    required this.longitude,
    required this.accuracyMeters,
    required this.timeMillis,
    this.speedMetersPerSecond,
    this.monotonicNanos,
  });
  final double latitude, longitude, accuracyMeters;
  final int timeMillis;
  final double? speedMetersPerSecond;
  final int? monotonicNanos;
  LocationFix copyWith({
    double? latitude,
    double? longitude,
    double? accuracyMeters,
    int? timeMillis,
    Object? speedMetersPerSecond = _unset,
    int? monotonicNanos,
  }) => LocationFix(
    latitude: latitude ?? this.latitude,
    longitude: longitude ?? this.longitude,
    accuracyMeters: accuracyMeters ?? this.accuracyMeters,
    timeMillis: timeMillis ?? this.timeMillis,
    speedMetersPerSecond: identical(speedMetersPerSecond, _unset)
        ? this.speedMetersPerSecond
        : speedMetersPerSecond as double?,
    monotonicNanos: monotonicNanos ?? this.monotonicNanos,
  );
}

/// Only visit identity/progress and acknowledged speech are persisted.
class TrackingProgress {
  const TrackingProgress({
    this.nextIndex = 0,
    this.nextStopKey,
    this.arrivedAtCurrent = false,
    this.announcedKeys = const {},
    this.completed = false,
    this.gpsEstablished = false,
  });
  final int nextIndex;
  final String? nextStopKey;
  final bool arrivedAtCurrent, completed, gpsEstablished;
  final Set<String> announcedKeys;
  TrackingProgress copyWith({
    int? nextIndex,
    Object? nextStopKey = _unset,
    bool? arrivedAtCurrent,
    Set<String>? announcedKeys,
    bool? completed,
    bool? gpsEstablished,
  }) => TrackingProgress(
    nextIndex: nextIndex ?? this.nextIndex,
    nextStopKey: identical(nextStopKey, _unset)
        ? this.nextStopKey
        : nextStopKey as String?,
    arrivedAtCurrent: arrivedAtCurrent ?? this.arrivedAtCurrent,
    announcedKeys: Set.unmodifiable(announcedKeys ?? this.announcedKeys),
    completed: completed ?? this.completed,
    gpsEstablished: gpsEstablished ?? this.gpsEstablished,
  );
  Map<String, Object?> toJson() => {
    'nextIndex': nextIndex,
    'nextStopKey': nextStopKey,
    'arrivedAtCurrent': arrivedAtCurrent,
    'announcedKeys': announcedKeys.toList(),
    'completed': completed,
    'gpsEstablished': gpsEstablished,
  };
  factory TrackingProgress.fromJson(Map<String, dynamic> j) => TrackingProgress(
    nextIndex: (j['nextIndex'] as int?) ?? 0,
    nextStopKey: j['nextStopKey'] as String?,
    arrivedAtCurrent: j['arrivedAtCurrent'] == true,
    announcedKeys: Set.unmodifiable(
      (j['announcedKeys'] as List? ?? []).whereType<String>(),
    ),
    completed: j['completed'] == true,
    gpsEstablished: j['gpsEstablished'] == true,
  );
}

const Object _unset = Object();

class TrackingUpdate {
  const TrackingUpdate(
    this.stop,
    this.source, {
    this.announcement,
    this.destinationReached = false,
  });
  final TrackingStop? stop, announcement;
  final TrackingSource source;
  final bool destinationReached;
}

bool validCoordinates(double latitude, double longitude) =>
    latitude.isFinite &&
    longitude.isFinite &&
    latitude >= -90 &&
    latitude <= 90 &&
    longitude >= -180 &&
    longitude <= 180;
double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
  final dLat = (lat2 - lat1) * math.pi / 180,
      dLon = (lon2 - lon1) * math.pi / 180;
  final a =
      math.sin(dLat / 2) * math.sin(dLat / 2) +
      math.cos(lat1 * math.pi / 180) *
          math.cos(lat2 * math.pi / 180) *
          math.sin(dLon / 2) *
          math.sin(dLon / 2);
  return 6371000 * 2 * math.asin(math.sqrt(a.clamp(0, 1)));
}

/// Parse the complete zoned ISO instant accepted by the Android time boundary.
/// DateTime.tryParse alone also normalizes invalid calendar dates and local time.
int? parseMillis(String? value) {
  if (value == null) return null;
  final match = _instantPattern.firstMatch(value);
  if (match == null) return null;
  try {
    final yearText = match.group(1)!;
    if (!yearText.startsWith('-') &&
        !yearText.startsWith('+') &&
        yearText.length != 4) {
      return null;
    }
    if (yearText.startsWith('+') && yearText.length <= 5) return null;
    if (yearText.startsWith('-') && BigInt.parse(yearText) == BigInt.zero) {
      return null;
    }
    final year = int.parse(yearText),
        month = int.parse(match.group(2)!),
        day = int.parse(match.group(3)!);
    final hour = int.parse(match.group(4)!),
        minute = int.parse(match.group(5)!);
    var second = int.parse(match.group(6)!);
    final fraction = match.group(7) ?? '';
    if (month < 1 ||
        month > 12 ||
        day < 1 ||
        minute > 59 ||
        second > 60 ||
        hour > 24) {
      return null;
    }
    final leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    final days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    if (day > days[month - 1]) return null;
    // ISO_INSTANT treats end-of-day as tomorrow and smooths a UTC leap second.
    if (hour == 24 &&
        (minute != 0 ||
            second != 0 ||
            fraction.replaceAll('0', '').isNotEmpty)) {
      return null;
    }
    if (second == 60) {
      if (hour != 23 || minute != 59) return null;
      second = 59;
    }
    var offsetSeconds = 0;
    if (match.group(8)!.toUpperCase() != 'Z') {
      final offsetHour = int.parse(match.group(10)!),
          offsetMinute = int.parse(match.group(11)!);
      final offsetSecond = int.parse(match.group(12) ?? '0');
      if (offsetHour > 18 ||
          offsetMinute > 59 ||
          offsetSecond > 59 ||
          (offsetHour == 18 && (offsetMinute != 0 || offsetSecond != 0))) {
        return null;
      }
      offsetSeconds =
          (offsetHour * 3600 + offsetMinute * 60 + offsetSecond) *
          (match.group(9) == '-' ? -1 : 1);
    }
    final millis = fraction.isEmpty
        ? 0
        : int.parse(fraction.padRight(3, '0').substring(0, 3));
    // Gregorian civil-day arithmetic also covers Java's extended year range,
    // beyond DateTime's platform-dependent construction limit.
    final adjustedYear = year - (month <= 2 ? 1 : 0);
    final era = (adjustedYear >= 0 ? adjustedYear : adjustedYear - 399) ~/ 400;
    final yearOfEra = adjustedYear - era * 400;
    final dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) ~/ 5 + day - 1;
    final dayOfEra =
        yearOfEra * 365 + yearOfEra ~/ 4 - yearOfEra ~/ 100 + dayOfYear;
    final epochDays = era * 146097 + dayOfEra - 719468;
    final result =
        BigInt.from(epochDays) * BigInt.from(86400000) +
        BigInt.from(
          hour * 3600000 +
              minute * 60000 +
              second * 1000 +
              millis -
              offsetSeconds * 1000,
        );
    if (result < _minInstantMillis || result > _maxInstantMillis) return null;
    return result.toInt();
  } catch (_) {
    return null;
  }
}

final RegExp _instantPattern = RegExp(
  r'^([+-]?\d{4,10})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d{0,9}))?([Zz]|([+-])(\d{2}):(\d{2})(?::(\d{2}))?)$',
);

final BigInt _minInstantMillis = BigInt.parse('-9223372036854775808');
final BigInt _maxInstantMillis = BigInt.parse('9223372036854775807');
