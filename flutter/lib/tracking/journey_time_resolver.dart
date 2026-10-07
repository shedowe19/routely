import 'models.dart';
import 'gps_journey_time_estimator.dart';

enum JourneyTimeSource {
  gpsObserved,
  gpsEstimate,
  apiRealtime,
  timetable,
  manual,
}

/// A display-only value; never an API stopover or persisted editor input.
class JourneyTime {
  const JourneyTime({
    required this.millis,
    required this.source,
    this.plannedMillis,
  });
  final int millis;
  final JourneyTimeSource source;
  final int? plannedMillis;
  String get sourceLabel => switch (source) {
    JourneyTimeSource.gpsObserved => 'GPS beobachtet',
    JourneyTimeSource.gpsEstimate => 'GPS-Schätzung',
    JourneyTimeSource.apiRealtime => 'API-Echtzeit',
    JourneyTimeSource.timetable => 'Fahrplan',
    JourneyTimeSource.manual => 'Manuell',
  };
  int? get delayMinutes =>
      plannedMillis == null ? null : (millis - plannedMillis!) ~/ 60000;
}

class JourneyTimeResolver {
  /// Historical/manual clock projection. GPS forecasts never advance this cursor.
  static List<TrackingStop> manualTimelineStops(
    List<TrackingStop> stops,
    TrackingStop? origin,
    TrackingStop? destination,
    String? manualDeparture,
    String? manualArrival,
  ) {
    final departure = parseMillis(manualDeparture),
        arrival = parseMillis(manualArrival);
    if (departure == null && arrival == null) return stops;
    final origins = <int>[], destinations = <int>[];
    for (var i = 0; i < stops.length; i++) {
      if (_matchesStopover(stops[i], origin)) origins.add(i);
      if (_matchesStopover(stops[i], destination)) destinations.add(i);
    }
    final originIndex = origins.length == 1 ? origins.single : null;
    final destinationIndex = destinations.length == 1
        ? destinations.single
        : null;
    return [
      for (var i = 0; i < stops.length; i++)
        stops[i].copyWith(
          departureRealMillis: i == originIndex && departure != null
              ? departure
              : stops[i].departureRealMillis,
          arrivalRealMillis: i == destinationIndex && arrival != null
              ? arrival
              : stops[i].arrivalRealMillis,
          effectiveDepartureMillis: i == originIndex && departure != null
              ? departure
              : stops[i].effectiveDepartureMillis,
          effectiveArrivalMillis: i == destinationIndex && arrival != null
              ? arrival
              : stops[i].effectiveArrivalMillis,
        ),
    ];
  }

  static JourneyTime? arrival(
    TrackingStop? stop,
    GpsJourneyTimes? gpsTimes,
    int nowMillis, {
    String? manualTime,
  }) => _resolve(stop, gpsTimes, nowMillis, manualTime, true);
  static JourneyTime? departure(
    TrackingStop? stop,
    GpsJourneyTimes? gpsTimes,
    int nowMillis, {
    String? manualTime,
  }) => _resolve(stop, gpsTimes, nowMillis, manualTime, false);
  static JourneyTime? _resolve(
    TrackingStop? stop,
    GpsJourneyTimes? gpsTimes,
    int nowMillis,
    String? manualTime,
    bool arrival,
  ) {
    if (stop == null) return null;
    final planned = arrival
        ? stop.plannedArrivalMillis
        : stop.plannedDepartureMillis;
    final gps = gpsTimes?.timeFor(stop, nowMillis);
    final gpsMillis = arrival ? gps?.arrivalMillis : gps?.departureMillis;
    if (gpsMillis != null && gpsMillis > 0) {
      final observed = arrival
          ? gps?.arrivalObserved == true
          : gps?.departureObserved == true;
      return JourneyTime(
        millis: gpsMillis,
        source: observed
            ? JourneyTimeSource.gpsObserved
            : JourneyTimeSource.gpsEstimate,
        plannedMillis: planned,
      );
    }
    final manual = parseMillis(manualTime);
    if (manual != null) {
      return JourneyTime(
        millis: manual,
        source: JourneyTimeSource.manual,
        plannedMillis: planned,
      );
    }
    final realtime = arrival
        ? stop.arrivalRealMillis
        : stop.departureRealMillis;
    if (realtime != null) {
      return JourneyTime(
        millis: realtime,
        source: JourneyTimeSource.apiRealtime,
        plannedMillis: planned,
      );
    }
    return planned == null
        ? null
        : JourneyTime(
            millis: planned,
            source: JourneyTimeSource.timetable,
            plannedMillis: planned,
          );
  }

  static bool _matchesStopover(TrackingStop first, TrackingStop? other) {
    if (other == null) return false;
    if (first.key.isNotEmpty &&
        other.key.isNotEmpty &&
        !first.key.contains(':') &&
        !other.key.contains(':')) {
      return first.key == other.key;
    }
    if (first.stationId == null || first.stationId != other.stationId) {
      return false;
    }
    return first.plannedDepartureMillis != null &&
            first.plannedDepartureMillis == other.plannedDepartureMillis ||
        first.plannedArrivalMillis != null &&
            first.plannedArrivalMillis == other.plannedArrivalMillis;
  }
}
