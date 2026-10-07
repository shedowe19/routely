import 'dart:async';

import '../data/models.dart';
import '../data/routely_api.dart';
import '../tracking/models.dart';
import '../tracking/ride_recognition_engine.dart';
import '../tracking/tracking_api_mapper.dart';

/// Bounded, session-fenced discovery. Successful cancellation data retires old
/// candidates before a later trip-detail failure or aggregate timeout.
class RideDiscovery {
  RideDiscovery({
    required this.api,
    required this.engine,
    required this.isCurrent,
    int Function()? nowMillis,
    this.timeout = const Duration(seconds: 45),
  }) : nowMillis = nowMillis ?? (() => DateTime.now().millisecondsSinceEpoch);
  final RoutelyApi api;
  final RideRecognitionEngine engine;
  final bool Function() isCurrent;
  final int Function() nowMillis;
  final Duration timeout;
  final Map<String, (Trip, int)> _tripCache = {};
  int _revision = 0;

  void clear() {
    _revision++;
    _tripCache.clear();
  }

  Future<List<RecognizableRide>> discover(LocationFix fix) {
    final revision = _revision;
    var cancelled = false;
    void requireCurrent() {
      if (cancelled || revision != _revision || !isCurrent()) {
        throw const StaleRequestException();
      }
    }

    final future = _discover(fix, requireCurrent);
    return future.timeout(
      timeout,
      onTimeout: () {
        cancelled = true;
        throw const ApiException(
          'Die Fahrtsuche dauerte zu lange. Neuer Versuch in 90 Sekunden.',
          transient: true,
        );
      },
    );
  }

  Future<List<RecognizableRide>> _discover(
    LocationFix fix,
    void Function() requireCurrent,
  ) async {
    requireCurrent();
    final now = nowMillis();
    if (!RideRecognitionEngine.isReliable(fix, now)) return [];
    final seenStations = <int>{};
    final stations = (await api.nearbyStations(fix.latitude, fix.longitude))
        .where((s) => s.id != null && s.id! > 0 && seenStations.add(s.id!))
        .take(2)
        .toList();
    requireCurrent();
    final departures = <(Station, Departure)>[];
    final cancelledTrips = <String>{};
    var successfulDepartures = 0;
    final when = DateTime.fromMillisecondsSinceEpoch(
      now - 300000,
      isUtc: true,
    ).toIso8601String();
    for (final station in stations) {
      requireCurrent();
      try {
        final values = await api.departures(station.id!, when: when);
        requireCurrent();
        successfulDepartures++;
        for (final departure in values) {
          if (departure.cancelled) {
            if (departure.tripId.isNotEmpty) {
              cancelledTrips.add(departure.tripId);
            }
          } else {
            final time =
                parseMillis(departure.realWhen) ??
                parseMillis(departure.plannedWhen);
            if (departure.tripId.isNotEmpty &&
                time != null &&
                time >= now - 480000 &&
                time <= now + 120000) {
              departures.add((station, departure));
            }
          }
        }
      } on StaleRequestException {
        rethrow;
      } catch (_) {
        requireCurrent();
      }
    }
    requireCurrent();
    if (stations.isNotEmpty && successfulDepartures == 0) {
      throw const ApiException(
        'Keine Stations- oder Fahrtdaten erhalten. Neuer Versuch in 90 Sekunden.',
        transient: true,
      );
    }
    engine.removeTrips(cancelledTrips);
    _tripCache.removeWhere(
      (key, value) =>
          now - value.$2 > RideRecognitionEngine.routeTtlMillis ||
          value.$2 > now,
    );
    final seenDepartures = <String>{};
    final selected = departures.where((entry) {
      final (station, departure) = entry;
      if (cancelledTrips.contains(departure.tripId)) return false;
      return seenDepartures.add(
        '${departure.tripId}|${departure.lineName}|${departure.station?.id ?? station.id}|${departure.plannedWhen}',
      );
    }).toList();
    selected.sort((a, b) {
      int distance(Departure d) =>
          ((parseMillis(d.realWhen) ?? parseMillis(d.plannedWhen) ?? now) - now)
              .abs();
      return distance(a.$2).compareTo(distance(b.$2));
    });
    final rides = <RecognizableRide>[];
    var successfulDetails = 0, requestedDetails = 0;
    for (final entry in selected.take(6)) {
      requireCurrent();
      final (station, departure) = entry;
      final key = '${departure.tripId}|${departure.lineName}';
      requestedDetails++;
      try {
        final cached = _tripCache[key];
        final fetchedAt = cached?.$2 ?? nowMillis();
        final trip =
            cached?.$1 ?? await api.trip(departure.tripId, departure.lineName);
        requireCurrent();
        successfulDetails++;
        if (cached == null) {
          _tripCache[key] = (trip, fetchedAt);
          while (_tripCache.length > RideRecognitionEngine.maxRoutes) {
            _tripCache.remove(_tripCache.keys.first);
          }
        }
        final ride = recognizableRide(
          departure: departure,
          trip: trip,
          fallbackStation: station,
          fetchedAtMillis: fetchedAt,
        );
        if (ride.originIndex >= 0) {
          rides.add(
            RecognizableRide(
              tripId: ride.tripId,
              lineName: ride.lineName,
              stops: ride.stops,
              originIndex: ride.originIndex,
              fetchedAtMillis: fetchedAt,
              departurePlannedMillis: ride.departurePlannedMillis,
              departureRealMillis: ride.departureRealMillis,
              cancelled: ride.cancelled,
              payload: (station, departure, trip),
            ),
          );
        }
      } on StaleRequestException {
        rethrow;
      } catch (_) {
        requireCurrent();
      }
    }
    requireCurrent();
    if (requestedDetails > 0 && successfulDetails == 0) {
      throw const ApiException(
        'Keine Fahrtdetails erhalten. Neuer Versuch in 90 Sekunden.',
        transient: true,
      );
    }
    return rides;
  }
}
