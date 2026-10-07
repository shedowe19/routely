import '../../data/models.dart';
import '../../tracking/journey_time_resolver.dart';
import '../../tracking/tracking_api_mapper.dart';

/// Keeps the user's intent separate from provider refreshes and GPS estimates.
class StatusEditDraft {
  StatusEditDraft(this.opening)
    : body = opening.body ?? '',
      visibility = opening.visibility,
      destination = opening.checkin?.destination {
    final checkin = opening.checkin;
    if (checkin != null) {
      final boundaries = [
        if (checkin.origin != null) checkin.origin!,
        if (checkin.destination != null) checkin.destination!,
      ];
      final stops = toTrackingStops(boundaries, checkin);
      final origin = stops.where((s) => s.isOrigin).firstOrNull;
      final target = stops.where((s) => s.isDestination).lastOrNull;
      departure = _iso(
        JourneyTimeResolver.departure(
          origin,
          null,
          0,
          manualTime: checkin.manualDeparture,
        )?.millis,
      );
      arrival = _iso(
        JourneyTimeResolver.arrival(
          target,
          null,
          0,
          manualTime: checkin.manualArrival,
        )?.millis,
      );
    }
    _initialDeparture = departure;
    _initialArrival = arrival;
  }

  final Status opening;
  String body;
  int visibility;
  Stop? destination;
  String departure = '', arrival = '';
  String _initialDeparture = '', _initialArrival = '';
  bool visibilityChanged = false, arrivalChanged = false;

  void setArrival(String value) {
    arrival = value;
    arrivalChanged = value != _initialArrival;
  }

  void setVisibility(int value) {
    if (value < 0 || value > 5) return;
    visibility = value;
    visibilityChanged = true;
  }

  bool selectDestination(Stop stop, Status current, List<Stop> route) {
    if (stop.cancelled || stop.stationId == null) return false;
    final origin = uniqueVisitIndex(route, current.checkin?.origin);
    final selected = uniqueVisitIndex(route, stop);
    if (origin == null || selected == null || selected <= origin) return false;
    final original = opening.checkin;
    final manual = stop.matchesStopover(original?.destination)
        ? original?.manualArrival
        : null;
    final tracking = toTrackingStops([stop], current.checkin!);
    final initial = _iso(
      JourneyTimeResolver.arrival(
        tracking.single,
        null,
        0,
        manualTime: manual,
      )?.millis,
    );
    destination = stop;
    if (!arrivalChanged) arrival = initial;
    _initialArrival = initial;
    return true;
  }

  bool get destinationChanged =>
      destination != opening.checkin?.destination &&
      destination?.matchesStopover(opening.checkin?.destination) != true;

  UpdateStatusRequest build(Status current) {
    if (destinationChanged &&
        (destination?.stationId == null ||
            !_validInstant(destination?.arrivalPlanned))) {
      throw const FormatException(
        'Für das neue Ziel fehlen eine Stations-ID oder eine gültige geplante Ankunft.',
      );
    }
    if (departure != _initialDeparture &&
        departure.isNotEmpty &&
        !_validInstant(departure)) {
      throw const FormatException(
        'Die Abfahrt benötigt ein vollständiges Datum mit Zeitzone.',
      );
    }
    if (arrivalChanged && arrival.isNotEmpty && !_validInstant(arrival)) {
      throw const FormatException(
        'Die Ankunft benötigt ein vollständiges Datum mit Zeitzone.',
      );
    }
    final clearOldOverride =
        destinationChanged &&
        (opening.checkin?.manualArrival != null ||
            current.checkin?.manualArrival != null);
    return UpdateStatusRequest(
      body: body != (opening.body ?? '') ? body : null,
      visibility: visibilityChanged && visibility != opening.visibility
          ? visibility
          : null,
      destination: destinationChanged ? destination?.stationId : null,
      destinationArrivalPlanned: destinationChanged
          ? destination?.arrivalPlanned
          : null,
      departure: departure != _initialDeparture ? departure : null,
      arrival: arrivalChanged
          ? arrival
          : clearOldOverride
          ? ''
          : null,
    );
  }

  static bool _validInstant(String? value) {
    if (value == null) return false;
    final match = RegExp(
      r'^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$',
      caseSensitive: false,
    ).firstMatch(value);
    if (match == null) return false;
    final year = int.parse(match[1]!),
        month = int.parse(match[2]!),
        day = int.parse(match[3]!);
    final hour = int.parse(match[4]!),
        minute = int.parse(match[5]!),
        second = int.parse(match[6]!);
    if (year < 1 ||
        month < 1 ||
        month > 12 ||
        day < 1 ||
        hour > 23 ||
        minute > 59 ||
        second > 59) {
      return false;
    }
    final calendar = DateTime.utc(year, month, day);
    if (calendar.year != year ||
        calendar.month != month ||
        calendar.day != day) {
      return false;
    }
    final zone = match[7]!;
    if (zone.length > 1) {
      final offsetHours = int.parse(zone.substring(1, 3)),
          offsetMinutes = int.parse(zone.substring(4));
      if (offsetHours > 18 ||
          offsetMinutes > 59 ||
          offsetHours == 18 && offsetMinutes > 0) {
        return false;
      }
    }
    return DateTime.tryParse(value) != null;
  }

  static String _iso(int? millis) => millis == null
      ? ''
      : DateTime.fromMillisecondsSinceEpoch(
          millis,
          isUtc: true,
        ).toIso8601String();
}

int? uniqueVisitIndex(List<Stop> stops, Stop? stop) {
  final matches = [
    for (var i = 0; i < stops.length; i++)
      if (stops[i].matchesStopover(stop)) i,
  ];
  return matches.length == 1 ? matches.single : null;
}
