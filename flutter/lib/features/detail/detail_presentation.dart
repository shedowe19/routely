import '../../data/models.dart';
import '../../tracking/gps_journey_time_estimator.dart';
import '../../tracking/models.dart';
import '../../tracking/tracking_api_mapper.dart';

enum TimelineSource { gps, timetable, waiting }

class TimelineProgress {
  const TimelineProgress({
    this.currentIndex,
    this.nextIndex,
    this.passedThroughIndex = -1,
    this.source = TimelineSource.timetable,
  });
  final int? currentIndex, nextIndex;
  final int passedThroughIndex;
  final TimelineSource source;
  String? badgeFor(int index) => index == currentIndex
      ? (source == TimelineSource.gps ? 'AKTUELL' : 'LAUT FAHRPLAN')
      : index == nextIndex
      ? (source == TimelineSource.gps ? 'ALS NÄCHSTES' : 'NÄCHSTER HALT · CA.')
      : null;
}

/// A section cursor is matched by visit; its integer index is never used in the full trip.
TimelineProgress resolveTimelineProgress(
  List<Stop> fullRoute,
  List<TrackingStop> timeline,
  int nowMillis,
  Map<String, dynamic>? tracking, {
  int? destinationIndex,
}) {
  if (tracking != null) {
    final source = tracking['source'] == 'gps'
        ? TimelineSource.gps
        : TimelineSource.timetable;
    final serialized = tracking['stop'];
    TrackingStop? visit;
    try {
      if (serialized is Map) {
        visit = TrackingStop.fromJson(Map<String, dynamic>.from(serialized));
      }
    } catch (_) {
      /* Malformed native state has no cursor. */
    }
    final key = tracking['nextStopKey'];
    final indices = <int>[];
    for (var i = 0; i < fullRoute.length; i++) {
      final stop = fullRoute[i];
      final uuidMatch =
          stop.uuid != null && (stop.uuid == visit?.key || stop.uuid == key);
      final markerMatch =
          visit != null &&
          visit.stationId != null &&
          visit.stationId == stop.stationId &&
          (visit.plannedArrivalMillis != null ||
              visit.plannedDepartureMillis != null) &&
          visit.plannedArrivalMillis == parseMillis(stop.arrivalPlanned) &&
          visit.plannedDepartureMillis == parseMillis(stop.departurePlanned);
      if (uuidMatch || markerMatch) indices.add(i);
    }
    final index = indices.length == 1 ? indices.single : null;
    if (tracking['completed'] == true) {
      return TimelineProgress(
        passedThroughIndex: destinationIndex ?? index ?? -1,
        source: source,
      );
    }
    if (index == null || fullRoute[index].cancelled) {
      return const TimelineProgress(source: TimelineSource.waiting);
    }
    if (tracking['arrivedAtCurrent'] == true) {
      return TimelineProgress(
        currentIndex: index,
        passedThroughIndex: index,
        source: source,
      );
    }
    return TimelineProgress(
      nextIndex: index,
      passedThroughIndex: index - 1,
      source: source,
    );
  }
  int? current, next;
  var passed = -1;
  for (var i = 0; i < timeline.length; i++) {
    if (destinationIndex != null && i > destinationIndex) break;
    final stop = timeline[i];
    if (stop.cancelled) continue;
    final arrival =
        stop.effectiveArrivalMillis ?? stop.effectiveDepartureMillis;
    if (arrival == null) continue;
    final departure = (stop.effectiveDepartureMillis ?? arrival).clamp(
      arrival,
      1 << 62,
    );
    if (nowMillis >= arrival && nowMillis <= departure) current = i;
    if (arrival > nowMillis) next ??= i;
    if (departure < nowMillis) passed = i;
  }
  return current == null
      ? TimelineProgress(nextIndex: next, passedThroughIndex: passed)
      : TimelineProgress(currentIndex: current, passedThroughIndex: current);
}

GpsJourneyTimes? decodeGpsTimes(Map<String, dynamic>? tracking) {
  final json = tracking?['gpsTimes'];
  if (json is! Map ||
      json['updatedAtMillis'] is! int ||
      json['validUntilMillis'] is! int ||
      json['stopTimes'] is! List) {
    return null;
  }
  try {
    return GpsJourneyTimes(
      updatedAtMillis: json['updatedAtMillis'] as int,
      validUntilMillis: json['validUntilMillis'] as int,
      stopTimes: (json['stopTimes'] as List).map((raw) {
        final time = Map<String, dynamic>.from(raw as Map);
        return GpsStopTime(
          stopKey: time['stopKey'] as String,
          stationId: time['stationId'] as int?,
          plannedArrivalMillis: time['plannedArrivalMillis'] as int?,
          plannedDepartureMillis: time['plannedDepartureMillis'] as int?,
          arrivalMillis: time['arrivalMillis'] as int?,
          departureMillis: time['departureMillis'] as int?,
          arrivalObserved: time['arrivalObserved'] == true,
          departureObserved: time['departureObserved'] == true,
        );
      }).toList(),
    );
  } catch (_) {
    return null;
  }
}

Map<String, dynamic>? acceptedTracking(
  Map<String, dynamic>? snapshot, {
  required int statusId,
  required String sessionRevision,
  required bool own,
  required int? activeStatusId,
}) =>
    own &&
        activeStatusId == statusId &&
        snapshot?['statusId'] == statusId &&
        snapshot?['sessionRevision'] == sessionRevision
    ? snapshot
    : null;

String? gpsUnavailableMessage(String? reason) => switch (reason) {
  'noFreshLocation' => 'Zeitprognose wartet auf frisches GPS.',
  'inaccurateLocation' => 'GPS ist für eine Zeitprognose zu ungenau.',
  'visitUnconfirmed' => 'Der GPS-Halt ist noch nicht sicher zugeordnet.',
  'routeUnsupported' => 'Für die Zeitprognose fehlen passende Streckendaten.',
  'routeGeometryUnavailable' =>
    'Für die GPS-Prognose ist noch keine passende Straßenroute verfügbar.',
  'replacementStopUnconfirmed' =>
    'Für die GPS-Prognose fehlen noch eindeutig bestätigte SEV-Haltpositionen.',
  'ambiguousRoute' =>
    'Der GPS-Fortschritt ist auf den möglichen Fahrwegen noch nicht eindeutig.',
  'waitingAtOrigin' =>
    'Die Zeitprognose wartet auf bestätigte Fahrbewegung nach dem Einstieg.',
  'outsideCorridor' =>
    'Die GPS-Position lässt sich dem aktuellen Streckenabschnitt noch nicht sicher zuordnen.',
  'insufficientMovement' =>
    'Für die Zeitprognose wird weitere Fahrbewegung benötigt.',
  'unplausibleMovement' =>
    'Die GPS-Bewegung ist für eine Zeitprognose noch nicht eindeutig.',
  _ => null,
};

String? trackingPlatform(Stop stop, bool isOrigin) {
  for (final value
      in isOrigin
          ? [
              stop.departurePlatformReal,
              stop.departurePlatformPlanned,
              stop.platform,
            ]
          : [
              stop.arrivalPlatformReal,
              stop.arrivalPlatformPlanned,
              stop.platform,
            ]) {
    if (value?.trim().isNotEmpty == true) return value!.trim();
  }
  return null;
}

List<TrackingStop> displayStops(List<Stop> route, Checkin checkin) =>
    toTrackingStops(route, checkin);

Uri? safeSevMapUri(String value) {
  final uri = Uri.tryParse(value);
  return uri != null &&
          uri.scheme == 'https' &&
          uri.port == 443 &&
          uri.userInfo.isEmpty &&
          {'www.bahnhof.de', 'bahnhof.de'}.contains(uri.host) &&
          RegExp(r'^/[a-z0-9]+(?:-[a-z0-9]+)*/karte$').hasMatch(uri.path) &&
          !uri.hasQuery &&
          !uri.hasFragment
      ? uri
      : null;
}
