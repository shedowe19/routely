import 'models.dart';
import 'gps_journey_time_estimator.dart';
import 'journey_time_resolver.dart';

/// Counts concrete visits after boarding, never duplicate physical station IDs.
class TripProgressModel {
  const TripProgressModel({
    required this.destinationName,
    this.nextStopName,
    required this.arrivedAtCurrent,
    this.remainingStops,
    required this.totalStops,
    this.passedStops,
    required this.completed,
    required this.approximate,
    required this.destinationCancelled,
    this.arrivalText,
    required this.sourceLabel,
  });
  final String destinationName, sourceLabel;
  final String? nextStopName, arrivalText;
  final bool arrivedAtCurrent, completed, approximate, destinationCancelled;
  final int? remainingStops, passedStops;
  final int totalStops;
  int get progressMax => (totalStops < 1 ? 1 : totalStops) * 100;
  int? get progress => passedStops == null
      ? null
      : (passedStops! * 100).clamp(
          0,
          completed ? progressMax : progressMax - 1,
        );
  bool get shouldPromote =>
      !completed && totalStops > 0 && remainingStops != null;
  String get remainingText => completed
      ? 'Ziel erreicht'
      : destinationCancelled
      ? 'Zielhalt entfällt'
      : remainingStops == null
      ? 'Fortschritt wird ermittelt'
      : remainingStops == 0
      ? 'Am Ziel · Ankunft wird geprüft'
      : remainingStops == 1
      ? 'Noch 1 Halt bis zum Ziel'
      : 'Noch $remainingStops Halte bis zum Ziel';
  String get expandedText => [
    if (nextStopName != null)
      '${arrivedAtCurrent ? 'Aktuell' : 'Nächster Halt'}: $nextStopName',
    remainingText,
    ?arrivalText,
    sourceLabel,
  ].join('\n');
  Map<String, Object?> toJson() => {
    'destinationName': destinationName,
    'nextStopName': nextStopName,
    'arrivedAtCurrent': arrivedAtCurrent,
    'remainingStops': remainingStops,
    'totalStops': totalStops,
    'passedStops': passedStops,
    'completed': completed,
    'approximate': approximate,
    'destinationCancelled': destinationCancelled,
    'arrivalText': arrivalText,
    'sourceLabel': sourceLabel,
    'progressMax': progressMax,
    'progress': progress,
    'shouldPromote': shouldPromote,
    'remainingText': remainingText,
    'expandedText': expandedText,
  };
  static TripProgressModel from({
    required List<TrackingStop> stops,
    required TrackingProgress progress,
    required TrackingSource source,
    required int nowMillis,
    GpsJourneyTimes? gpsTimes,
    String? destinationName,
    int? manualDestinationArrival,
    DateTime Function(int millis)? localTime,
  }) {
    final destination = stops.isEmpty ? null : stops.last, counted = <int>[];
    for (var i = 1; i < stops.length; i++) {
      if (!stops[i].cancelled) counted.add(i);
    }
    final matches = <int>[];
    if (progress.nextStopKey != null) {
      for (var i = 0; i < stops.length; i++) {
        if (stops[i].key == progress.nextStopKey) matches.add(i);
      }
    }
    final match = matches.length == 1 ? matches.single : null;
    int? visitIndex;
    if (match != null) {
      for (var i = match; i < stops.length; i++) {
        if (!stops[i].cancelled) {
          visitIndex = i;
          break;
        }
      }
    }
    final arrived = progress.arrivedAtCurrent && visitIndex == match;
    final passed = progress.completed
        ? counted.length
        : visitIndex == null
        ? null
        : counted
              .where((i) => i < visitIndex! || (arrived && i == visitIndex))
              .length;
    final remaining = progress.completed
        ? 0
        : passed == null
        ? null
        : counted.length - passed;
    String? arrivalText;
    if (destination != null && !destination.cancelled) {
      final gps = source == TrackingSource.gps ? gpsTimes : null,
          manual = manualDestinationArrival == null
              ? null
              : DateTime.fromMillisecondsSinceEpoch(
                  manualDestinationArrival,
                  isUtc: true,
                ).toIso8601String();
      final resolved =
          JourneyTimeResolver.arrival(
            destination,
            gps,
            nowMillis,
            manualTime: manual,
          ) ??
          JourneyTimeResolver.departure(destination, gps, nowMillis);
      if (resolved != null) {
        final time =
            (localTime ??
            (millis) =>
                DateTime.fromMillisecondsSinceEpoch(millis))(resolved.millis);
        arrivalText =
            'Ankunft Ziel: ${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')} (${resolved.sourceLabel})';
      }
    }
    return TripProgressModel(
      destinationName: destinationName?.trim().isNotEmpty == true
          ? destinationName!
          : destination?.name ?? 'Ziel',
      nextStopName: visitIndex == null ? null : stops[visitIndex].name,
      arrivedAtCurrent: arrived,
      remainingStops: remaining,
      totalStops: counted.length,
      passedStops: passed,
      completed: progress.completed,
      approximate: source != TrackingSource.gps,
      destinationCancelled: destination?.cancelled == true,
      arrivalText: arrivalText,
      sourceLabel: source == TrackingSource.gps ? 'GPS' : 'Fahrplan · ungefähr',
    );
  }
}
