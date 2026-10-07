import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../data/app_store.dart';
import '../../data/models.dart';
import '../../data/routely_api.dart';
import 'checkin_selection.dart';

enum CheckInStep { station, departures, destination, confirm, success }

enum DepartureFilter { all, rail, subway, tram, bus, ferry }

class CheckInController extends ChangeNotifier {
  CheckInController({
    required this.searchStations,
    required this.nearbyStations,
    required this.loadDepartures,
    required this.loadTrip,
    required this.create,
    required this.correctTimes,
    required this.onCreated,
    required this.sessionRevision,
    this.searchDebounce = const Duration(milliseconds: 300),
  }) : _boundSession = sessionRevision();

  factory CheckInController.forStore(
    AppStore store,
    Future<void> Function(Status) onCreated,
  ) {
    final session = store.session!;
    final api = store.api;
    return CheckInController(
      searchStations: api.searchStations,
      nearbyStations: api.nearbyStations,
      loadDepartures: (id, when) => api.departures(id, when: when),
      loadTrip: api.trip,
      create: (request) => store.checkIn(request, expectedSession: session),
      correctTimes: (id, request) =>
          store.updateStatus(id, request, expectedSession: session),
      onCreated: onCreated,
      sessionRevision: () => store.session?.revision,
    );
  }

  final Future<List<Station>> Function(String) searchStations;
  final Future<List<Station>> Function(double, double) nearbyStations;
  final Future<List<Departure>> Function(int, String?) loadDepartures;
  final Future<Trip> Function(String, String) loadTrip;
  final Future<Status> Function(CheckInRequest) create;
  final Future<Status> Function(int, UpdateStatusRequest) correctTimes;
  final Future<void> Function(Status) onCreated;
  final String? Function() sessionRevision;
  final String? _boundSession;
  final Duration searchDebounce;
  Timer? _searchTimer;
  int _generation = 0;
  bool _disposed = false;
  bool _recognitionSource = false;
  bool submissionPending = false;
  CheckInStep step = CheckInStep.station;
  bool loading = false;
  String query = '';
  List<Station> stations = [];
  Station? station;
  List<Departure> departures = [];
  Departure? departure;
  Trip? trip;
  Stop? origin;
  List<Stop> destinations = [];
  Stop? destination;
  String body = '';
  int business = 0;
  String manualDeparture = '';
  String manualArrival = '';
  String? error;
  String? warning;
  Status? result;
  int? earnedPoints;
  DateTime? departuresWhen;
  DepartureFilter departureFilter = DepartureFilter.all;

  bool get _active =>
      !_disposed && _boundSession != null && sessionRevision() == _boundSession;
  bool get canGoBack =>
      !submissionPending &&
      step != CheckInStep.station &&
      step != CheckInStep.success;
  List<Departure> get visibleDepartures => departures.where((item) {
    final category = item.category ?? item.mode;
    return switch (departureFilter) {
      DepartureFilter.all => true,
      DepartureFilter.rail => const [
        'nationalExpress',
        'national',
        'regionalExp',
        'regional',
        'suburban',
        'train',
      ].contains(category),
      DepartureFilter.subway => category == 'subway',
      DepartureFilter.tram => category == 'tram',
      DepartureFilter.bus => category == 'bus',
      DepartureFilter.ferry => category == 'ferry',
    };
  }).toList();

  void updateQuery(String value) {
    if (!_active || submissionPending || step != CheckInStep.station) return;
    query = value;
    stations = [];
    error = null;
    loading = value.trim().length >= 2;
    final generation = ++_generation;
    _searchTimer?.cancel();
    notifyListeners();
    if (!loading) return;
    _searchTimer = Timer(
      searchDebounce,
      () => _search(value.trim(), generation),
    );
  }

  Future<void> _search(String value, int generation) async {
    try {
      final found = await searchStations(value);
      if (!_active || generation != _generation) return;
      stations = found;
    } catch (failure) {
      if (!_active || generation != _generation) return;
      error = 'Stationssuche fehlgeschlagen: $failure';
    } finally {
      if (_active && generation == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  Future<void> findNearby(
    Future<(double, double)> Function() requestLocation,
  ) async {
    if (!_active || submissionPending || step != CheckInStep.station) return;
    final generation = ++_generation;
    _searchTimer?.cancel();
    loading = true;
    error = null;
    stations = [];
    notifyListeners();
    try {
      final location = await requestLocation();
      if (!_active || generation != _generation) return;
      final found = await nearbyStations(location.$1, location.$2);
      if (!_active || generation != _generation) return;
      stations = found;
      query = 'Nahegelegene Stationen';
      loading = false;
      if (found.length == 1) {
        await selectStation(found.single);
        return;
      }
    } catch (failure) {
      if (!_active || generation != _generation) return;
      error =
          'Standortsuche fehlgeschlagen: $failure\nBitte suche die Station manuell.';
      loading = false;
    }
    if (_active && generation == _generation) notifyListeners();
  }

  Future<void> selectStation(Station selected) async {
    if (!_active || submissionPending || (selected.id ?? 0) <= 0) return;
    ++_generation;
    _searchTimer?.cancel();
    station = selected;
    query = selected.name ?? '';
    stations = [];
    departures = [];
    departure = null;
    trip = null;
    origin = destination = null;
    destinations = [];
    _recognitionSource = false;
    step = CheckInStep.departures;
    await refreshDepartures();
  }

  Future<void> refreshDepartures({
    DateTime? when,
    bool resetTime = false,
  }) async {
    if (!_active || submissionPending || station?.id == null) return;
    if (when != null || resetTime) departuresWhen = when;
    final generation = ++_generation;
    final id = station!.id!;
    loading = true;
    error = null;
    notifyListeners();
    try {
      final found = await loadDepartures(
        id,
        departuresWhen?.toUtc().toIso8601String(),
      );
      if (!_active || generation != _generation) return;
      departures = found;
    } catch (failure) {
      if (!_active || generation != _generation) return;
      error = 'Abfahrten konnten nicht geladen werden: $failure';
    } finally {
      if (_active && generation == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  void changeFilter(DepartureFilter value) {
    if (!_active || submissionPending) return;
    departureFilter = value;
    notifyListeners();
  }

  Future<void> selectDeparture(
    Departure selected, {
    Station? recognizedStation,
    Trip? recognizedTrip,
  }) async {
    if (!_active || submissionPending) return;
    if (selected.tripId.isEmpty || selected.cancelled) {
      error = selected.cancelled
          ? 'Diese Fahrt fällt aus und kann nicht ausgewählt werden.'
          : 'Für diese Abfahrt fehlt die Fahrt-ID.';
      notifyListeners();
      return;
    }
    final generation = ++_generation;
    _searchTimer?.cancel();
    loading = true;
    error = null;
    departure = selected;
    destination = null;
    destinations = [];
    notifyListeners();
    try {
      final loaded =
          recognizedTrip ?? await loadTrip(selected.tripId, selected.lineName);
      if (!_active || generation != _generation) return;
      final boardingStation = selected.station ?? recognizedStation ?? station;
      final stops = loaded.stopovers;
      final originIndex = resolveCheckInOriginIndex(
        stops,
        boardingStation,
        selected,
      );
      final validDestinations = validCheckInDestinations(stops, originIndex);
      if (validDestinations.isEmpty) {
        throw FormatException(
          originIndex < 0
              ? 'Der Einstiegshalt konnte nicht eindeutig bestimmt werden. Bitte wähle eine andere Abfahrt.'
              : 'Für diese Fahrt sind keine gültigen Ziele nach dem Einstieg verfügbar.',
        );
      }
      station = boardingStation;
      trip = loaded;
      origin = stops[originIndex];
      destinations = validDestinations;
      _recognitionSource = recognizedStation != null;
      step = CheckInStep.destination;
    } catch (failure) {
      if (!_active || generation != _generation) return;
      error = 'Halte konnten nicht geladen werden: $failure';
    } finally {
      if (_active && generation == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  void selectDestination(Stop selected) {
    if (!_active || loading || submissionPending) return;
    if (selected.cancelled ||
        (selected.stationId ?? 0) <= 0 ||
        !destinations.any((item) => item.matchesStopover(selected))) {
      error = 'Dieser Zielhalt ist für die ausgewählte Fahrt nicht verfügbar.';
      notifyListeners();
      return;
    }
    destination = selected;
    manualDeparture = manualArrival = '';
    step = CheckInStep.confirm;
    error = null;
    notifyListeners();
  }

  void updateDetails({
    String? statusBody,
    int? reason,
    String? realDeparture,
    String? realArrival,
  }) {
    if (!_active || submissionPending) return;
    if (statusBody != null) body = statusBody;
    if (reason != null && reason >= 0 && reason <= 2) business = reason;
    if (realDeparture != null) manualDeparture = realDeparture;
    if (realArrival != null) manualArrival = realArrival;
    error = null;
    notifyListeners();
  }

  Future<void> confirm() async {
    if (!_active ||
        submissionPending ||
        loading ||
        step != CheckInStep.confirm ||
        departure == null ||
        station == null ||
        origin == null ||
        destination == null) {
      return;
    }
    if (!destinations.any((item) => item.matchesStopover(destination)) ||
        destination!.cancelled) {
      return;
    }
    late final CheckInSubmission submission;
    try {
      submission = buildCheckInSubmission(
        departure: departure!,
        station: station!,
        origin: origin!,
        destination: destination!,
        body: body,
        business: business,
        manualDeparture: manualDeparture,
        manualArrival: manualArrival,
      );
    } catch (failure) {
      error = failure.toString();
      notifyListeners();
      return;
    }
    final generation = _generation;
    submissionPending = loading = true;
    error = warning = null;
    notifyListeners();
    try {
      final created = await create(submission.request);
      if (!_active || generation != _generation) return;
      if (created.id <= 0) throw const AcceptedMutationException();
      result = created;
      earnedPoints = created.earnedPoints ?? created.checkin?.points;
      step = CheckInStep.success;
      notifyListeners();
      final warnings = <String>[];
      if (submission.timeCorrection != null) {
        try {
          final corrected = await correctTimes(
            created.id,
            submission.timeCorrection!,
          );
          if (!_active || generation != _generation) return;
          result = corrected;
        } catch (failure) {
          if (!_active || generation != _generation) return;
          warnings.add(
            'Du bist eingecheckt. Die manuelle Zeitkorrektur konnte nicht gespeichert werden. Bearbeite die erstellte Fahrt im Profil; ein erneuter Check-in ist nicht nötig.',
          );
        }
      }
      try {
        await onCreated(result!);
      } catch (failure) {
        if (!_active || generation != _generation) return;
        warnings.add(
          'Du bist eingecheckt, aber die lokale Reisebegleitung konnte nicht aktiviert werden. Öffne die Fahrt im Profil.',
        );
      }
      if (!_active || generation != _generation) return;
      warning = warnings.isEmpty ? null : warnings.join('\n');
    } on AcceptedMutationException {
      if (!_active || generation != _generation) return;
      step = CheckInStep.success;
      result = null;
      warning =
          'Der Check-in wurde angenommen, aber die Antwort enthält keine bestätigte Fahrt-ID. Prüfe die Fahrt im Profil; ein erneuter Check-in ist nicht nötig.';
    } on CheckInConflictException catch (conflict) {
      if (!_active || generation != _generation) return;
      final descriptions = conflict.conflicts.map(
        (status) =>
            '${status.checkin?.lineName ?? 'Fahrt'} → ${status.checkin?.destination?.stationName ?? 'Ziel'} (Status ${status.id})',
      );
      error = descriptions.isEmpty
          ? conflict.message
          : '${conflict.message}\n${descriptions.join('\n')}';
    } catch (failure) {
      if (!_active || generation != _generation) return;
      if (step == CheckInStep.success) {
        warning =
            'Der Check-in wurde angenommen. Weitere Schritte sind fehlgeschlagen; prüfe die Fahrt im Profil. Ein erneuter Check-in ist nicht nötig.';
      } else {
        error = 'Check-in fehlgeschlagen: $failure';
      }
    } finally {
      if (_active && generation == _generation) {
        submissionPending = loading = false;
        notifyListeners();
      }
    }
  }

  void goBack() {
    if (!_active || submissionPending) return;
    ++_generation;
    _searchTimer?.cancel();
    loading = false;
    error = null;
    switch (step) {
      case CheckInStep.departures:
        final selected = station;
        query = selected?.name ?? '';
        stations = selected == null ? [] : [selected];
        station = null;
        departures = [];
        step = CheckInStep.station;
      case CheckInStep.destination:
        if (_recognitionSource) {
          reset();
        } else {
          step = CheckInStep.departures;
          departure = null;
          trip = null;
          origin = destination = null;
          destinations = [];
        }
      case CheckInStep.confirm:
        step = CheckInStep.destination;
        destination = null;
      case CheckInStep.station:
      case CheckInStep.success:
        return;
    }
    notifyListeners();
  }

  void reset() {
    if (!_active || submissionPending) return;
    ++_generation;
    _searchTimer?.cancel();
    step = CheckInStep.station;
    loading = false;
    query = body = manualDeparture = manualArrival = '';
    stations = [];
    departures = [];
    station = null;
    departure = null;
    trip = null;
    origin = destination = null;
    destinations = [];
    result = null;
    earnedPoints = null;
    error = warning = null;
    business = 0;
    departuresWhen = null;
    departureFilter = DepartureFilter.all;
    _recognitionSource = false;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    _searchTimer?.cancel();
    super.dispose();
  }
}
