import '../../data/models.dart';

/// An ISO marker identifies a concrete scheduled visit, including its zone.
DateTime? parseZonedTime(String? value) {
  if (value == null) return null;
  final match = RegExp(
    r'^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$',
  ).firstMatch(value.trim());
  if (match == null) return null;
  final year = int.parse(match[1]!);
  final month = int.parse(match[2]!);
  final day = int.parse(match[3]!);
  final hour = int.parse(match[4]!);
  final minute = int.parse(match[5]!);
  final second = int.parse(match[6] ?? '0');
  final date = DateTime.utc(year, month, day);
  if (date.year != year ||
      date.month != month ||
      date.day != day ||
      hour > 23 ||
      minute > 59 ||
      second > 59) {
    return null;
  }
  final zone = match[7]!;
  if (zone != 'Z') {
    final zoneHour = int.parse(zone.substring(1, 3));
    final zoneMinute = int.parse(zone.substring(4, 6));
    if (zoneHour > 18 || zoneMinute > 59 || zoneHour == 18 && zoneMinute != 0) {
      return null;
    }
  }
  return DateTime.tryParse(value.trim())?.toUtc();
}

bool samePlannedInstant(String? first, String? second) {
  final a = parseZonedTime(first);
  final b = parseZonedTime(second);
  return a != null && b != null && a.isAtSameMomentAs(b);
}

int resolveCheckInOriginIndex(
  List<Stop> stops,
  Station? origin,
  Departure departure,
) {
  if (origin == null) return -1;
  final ibnr = origin.identifier('de_db_ibnr');
  final matches = <int>[];
  final timed = <int>[];
  for (var index = 0; index < stops.length; index++) {
    final stop = stops[index];
    if (origin.id != null && origin.id == stop.stationId ||
        ibnr?.isNotEmpty == true &&
            ibnr == stop.stationIdentifier('de_db_ibnr')) {
      matches.add(index);
      if (samePlannedInstant(stop.departurePlanned, departure.plannedWhen) ||
          samePlannedInstant(
            stop.departureReal ?? stop.departurePlanned,
            departure.realWhen,
          )) {
        timed.add(index);
      }
    }
  }
  if (timed.length == 1) return timed.single;
  return matches.length == 1 ? matches.single : -1;
}

List<Stop> validCheckInDestinations(List<Stop> stops, int originIndex) {
  if (originIndex < 0 ||
      originIndex >= stops.length ||
      stops[originIndex].cancelled) {
    return [];
  }
  return stops
      .skip(originIndex + 1)
      .where(
        (stop) =>
            !stop.cancelled &&
            (stop.stationId ?? 0) > 0 &&
            parseZonedTime(stop.arrivalPlanned ?? stop.arrivalReal) != null,
      )
      .toList();
}

class CheckInSubmission {
  const CheckInSubmission(this.request, this.timeCorrection);
  final CheckInRequest request;
  final UpdateStatusRequest? timeCorrection;
}

CheckInSubmission buildCheckInSubmission({
  required Departure departure,
  required Station station,
  required Stop origin,
  required Stop destination,
  String body = '',
  int business = 0,
  String manualDeparture = '',
  String manualArrival = '',
}) {
  final startId = origin.stationId ?? departure.station?.id ?? station.id;
  final endId = destination.stationId;
  if ((startId ?? 0) <= 0 ||
      (endId ?? 0) <= 0 ||
      departure.tripId.isEmpty ||
      destination.cancelled) {
    throw const FormatException('Start, Fahrt oder Zielhalt ist nicht gültig.');
  }
  final plannedDeparture = origin.departurePlanned ?? departure.plannedWhen;
  final plannedArrival = destination.arrivalPlanned;
  final departureMarker = parseZonedTime(plannedDeparture);
  final arrivalMarker = parseZonedTime(plannedArrival);
  if (departureMarker == null || arrivalMarker == null) {
    throw const FormatException(
      'Die geplante Abfahrts- oder Ankunftszeit fehlt oder ist ungültig.',
    );
  }
  if (arrivalMarker.isBefore(departureMarker)) {
    throw const FormatException('Die Ankunft muss nach der Abfahrt liegen.');
  }
  DateTime? manual(String value, String label) {
    if (value.trim().isEmpty) return null;
    final parsed = parseZonedTime(value);
    if (parsed == null) {
      throw FormatException(
        '$label: Bitte ISO-Zeit mit Zeitzone eingeben, z. B. 2026-10-07T18:05:00+02:00.',
      );
    }
    return parsed;
  }

  final correctedDeparture = manual(manualDeparture, 'Abfahrt');
  final correctedArrival = manual(manualArrival, 'Ankunft');
  if (correctedDeparture != null || correctedArrival != null) {
    final realDeparture =
        correctedDeparture ??
        parseZonedTime(origin.departureReal) ??
        departureMarker;
    final realArrival =
        correctedArrival ??
        parseZonedTime(destination.arrivalReal) ??
        arrivalMarker;
    if (realArrival.isBefore(realDeparture)) {
      throw const FormatException(
        'Die korrigierte Ankunft muss nach der Abfahrt liegen.',
      );
    }
  }
  return CheckInSubmission(
    CheckInRequest(
      tripId: departure.tripId,
      lineName: departure.lineName,
      startStationId: startId!,
      destinationStationId: endId!,
      departure: plannedDeparture!,
      arrival: plannedArrival!,
      body: body.trim().isEmpty ? null : body,
      business: business,
    ),
    correctedDeparture == null && correctedArrival == null
        ? null
        : UpdateStatusRequest(
            departure: correctedDeparture?.toIso8601String(),
            arrival: correctedArrival?.toIso8601String(),
          ),
  );
}

String checkInLocalTime(String? value) {
  final parsed = parseZonedTime(value)?.toLocal();
  if (parsed == null) return '–';
  return '${parsed.hour.toString().padLeft(2, '0')}:${parsed.minute.toString().padLeft(2, '0')}';
}

String departureTimeLabel(Departure departure) {
  final real = parseZonedTime(departure.realWhen);
  final planned = parseZonedTime(departure.plannedWhen);
  final displayed = real == null ? departure.plannedWhen : departure.realWhen;
  final result = [checkInLocalTime(displayed)];
  if (real != null && planned != null && !real.isAtSameMomentAs(planned)) {
    result.add('Plan ${checkInLocalTime(departure.plannedWhen)}');
    final delay = real.difference(planned).inMinutes;
    if (delay != 0) result.add('${delay > 0 ? '+' : '−'}${delay.abs()} min');
  }
  return result.join(' · ');
}
