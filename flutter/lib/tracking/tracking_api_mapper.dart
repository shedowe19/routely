import '../data/models.dart' as api;
import 'models.dart';
import 'trip_change_monitor.dart';
import 'ride_recognition_engine.dart';

/// Unresolvable or ambiguous boundary visits never replace a valid active route.
List<api.Stop>? checkedInRoute(List<api.Stop> stops, api.Checkin checkin) {
  final origins = <int>[], destinations = <int>[];
  for (var i = 0; i < stops.length; i++) {
    if (stops[i].matchesStopover(checkin.origin)) origins.add(i);
    if (stops[i].matchesStopover(checkin.destination)) destinations.add(i);
  }
  if (origins.length != 1 ||
      destinations.length != 1 ||
      destinations.single < origins.single) {
    return null;
  }
  return List.unmodifiable(
    stops.sublist(origins.single, destinations.single + 1),
  );
}

List<TrackingStop> toTrackingStops(
  List<api.Stop> stops,
  api.Checkin checkin, {
  Map<String, (double, double)> replacementCoordinates = const {},
}) {
  return List.generate(stops.length, (index) {
    final stop = stops[index],
        origin = stop.matchesStopover(checkin.origin),
        destination = stop.matchesStopover(checkin.destination);
    final key = trackingVisitKey(stop, index),
        point = replacementCoordinates[key];
    return TrackingStop(
      key: key,
      stationId: stop.stationId,
      name: stop.stationName ?? 'Unbekannte Station',
      latitude: point?.$1 ?? stop.station?.latitude,
      longitude: point?.$2 ?? stop.station?.longitude,
      plannedArrivalMillis: parseMillis(stop.arrivalPlanned),
      plannedDepartureMillis: parseMillis(stop.departurePlanned),
      arrivalRealMillis: parseMillis(stop.arrivalReal),
      departureRealMillis: parseMillis(stop.departureReal),
      effectiveArrivalMillis:
          (destination ? parseMillis(checkin.manualArrival) : null) ??
          parseMillis(stop.effectiveArrival),
      effectiveDepartureMillis:
          (origin ? parseMillis(checkin.manualDeparture) : null) ??
          parseMillis(stop.effectiveDeparture),
      arrivalPlatform:
          _platform(stop.arrivalPlatformReal) ??
          _platform(stop.arrivalPlatformPlanned) ??
          _platform(stop.platform),
      departurePlatform:
          _platform(stop.departurePlatformReal) ??
          _platform(stop.departurePlatformPlanned) ??
          _platform(stop.platform),
      cancelled: stop.cancelled,
      isOrigin: origin,
      isDestination: destination,
    );
  }, growable: false);
}

String trackingVisitKey(api.Stop stop, int index) =>
    stop.uuid?.trim().isNotEmpty == true
    ? stop.uuid!
    : '${stop.stationId ?? 'unknown'}:${stop.arrivalPlanned}:${stop.departurePlanned}:$index';
TripChangeSnapshot tripChangeSnapshot({
  required int statusId,
  required int observedAtMillis,
  required List<api.Stop> stops,
  required api.Checkin checkin,
  required int nextIndex,
}) => TripChangeSnapshot(
  statusId: statusId,
  observedAtMillis: observedAtMillis,
  nextIndex: nextIndex,
  manualDepartureMillis: parseMillis(checkin.manualDeparture),
  manualArrivalMillis: parseMillis(checkin.manualArrival),
  stops: List.generate(stops.length, (index) {
    final stop = stops[index],
        arrival = parseMillis(stop.arrivalPlanned),
        departure = parseMillis(stop.departurePlanned);
    final stationKey = stop.station?.uuid ?? stop.stationId?.toString();
    final key = stop.uuid?.trim().isNotEmpty == true
        ? stop.uuid!
        : stationKey != null && (arrival != null || departure != null)
        ? '$stationKey:$arrival:$departure'
        : 'unresolved:$index';
    return TripChangeStop(
      key: key,
      name: stop.stationName ?? 'Unbekannte Station',
      arrivalPlannedMillis: arrival,
      departurePlannedMillis: departure,
      arrivalRealMillis: parseMillis(stop.arrivalReal),
      departureRealMillis: parseMillis(stop.departureReal),
      arrivalPlatform:
          _platform(stop.arrivalPlatformReal) ??
          _platform(stop.arrivalPlatformPlanned) ??
          _platform(stop.platform),
      departurePlatform:
          _platform(stop.departurePlatformReal) ??
          _platform(stop.departurePlatformPlanned) ??
          _platform(stop.platform),
      arrivalPlatformIsLive: _platform(stop.arrivalPlatformReal) != null
          ? true
          : _platform(stop.arrivalPlatformPlanned) != null
          ? false
          : null,
      departurePlatformIsLive: _platform(stop.departurePlatformReal) != null
          ? true
          : _platform(stop.departurePlatformPlanned) != null
          ? false
          : null,
      cancelled: stop.raw['cancelled'] == null ? null : stop.cancelled,
      isOrigin: stop.matchesStopover(checkin.origin),
      isDestination: stop.matchesStopover(checkin.destination),
    );
  }, growable: false),
);
RecognizableRide recognizableRide({
  required api.Departure departure,
  required api.Trip trip,
  required api.Station fallbackStation,
  required int fetchedAtMillis,
}) {
  final stops = trip.stopovers;
  final tracking = List.generate(stops.length, (i) {
    final stop = stops[i];
    return TrackingStop(
      key: trackingVisitKey(stop, i),
      stationId: stop.stationId,
      name: stop.stationName ?? 'nächster Halt',
      latitude: stop.station?.latitude,
      longitude: stop.station?.longitude,
      plannedArrivalMillis: parseMillis(stop.arrivalPlanned),
      plannedDepartureMillis: parseMillis(stop.departurePlanned),
      effectiveArrivalMillis: parseMillis(stop.effectiveArrival),
      effectiveDepartureMillis: parseMillis(stop.effectiveDeparture),
      arrivalRealMillis: parseMillis(stop.arrivalReal),
      departureRealMillis: parseMillis(stop.departureReal),
      cancelled: stop.cancelled,
    );
  });
  final planned = parseMillis(departure.plannedWhen),
      real = parseMillis(departure.realWhen);
  final origin = RideRecognitionEngine.resolveOriginIndex(
    tracking,
    stationId: (departure.station ?? fallbackStation).id,
    plannedDepartureMillis: planned,
    realDepartureMillis: real,
  );
  return RecognizableRide(
    tripId: departure.tripId,
    lineName: departure.lineName,
    stops: tracking,
    originIndex: origin,
    fetchedAtMillis: fetchedAtMillis,
    departureRealMillis: real,
    departurePlannedMillis: planned,
    cancelled: departure.cancelled,
    payload: (departure, trip),
  );
}

String? _platform(String? value) {
  final clean = value?.trim();
  return clean == null || clean.isEmpty ? null : clean;
}
