import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../../data/app_store.dart';
import '../../data/models.dart';
import '../../data/routely_api.dart';
import '../../tracking/sev_enrichment.dart';
import 'status_edit_draft.dart';

/// Publishes the status and complete ordered stop list as one revision.
class StatusDetailController extends ChangeNotifier {
  StatusDetailController(
    this.store,
    this.statusId, {
    BahnhofSevRepository? sevRepository,
  }) : _repository = sevRepository ?? BahnhofSevRepository(),
       _ownsRepository = sevRepository == null {
    _session = store.session;
    _enricher = SevJourneyEnricher(_repository);
    store.addListener(_storeChanged);
    _mutations = store.mutations.listen(_onMutation);
  }
  final AppStore store;
  final int statusId;
  final BahnhofSevRepository _repository;
  final bool _ownsRepository;
  late final SevJourneyEnricher _enricher;
  late final StreamSubscription<StatusMutation> _mutations;
  AuthSession? _session;
  Timer? _refreshTimer;
  int _generation = 0, _sevGeneration = 0;
  bool _disposed = false, _observed = true;
  String? _sevFingerprint;
  Status? status;
  List<Stop> stops = const [];
  Map<String, SevStopInfo> sevStops = const {};
  bool loading = false, updating = false, deleting = false, loadingSev = false;
  String? error;
  int? lastUpdatedMillis;
  StatusEditDraft? draft;
  bool get busy => updating || deleting;
  bool get isOwn =>
      status?.user?.id != null && status?.user?.id == store.user?.id;
  bool get isCurrent =>
      !_disposed && _session != null && store.isCurrent(_session!);

  void start() {
    unawaited(refresh());
    _refreshTimer ??= Timer.periodic(const Duration(seconds: 30), (_) {
      if (_observed && !busy && !loading) unawaited(refresh(silent: true));
    });
  }

  void setObserved(bool value) {
    final resumed = value && !_observed;
    _observed = value;
    if (resumed && !busy) unawaited(refresh(silent: true));
  }

  void _storeChanged() {
    if (!isCurrent) {
      ++_generation;
      ++_sevGeneration;
      _enricher.cancel();
      _refreshTimer?.cancel();
      status = null;
      stops = const [];
      sevStops = const {};
      draft = null;
      loading = updating = deleting = loadingSev = false;
      if (!_disposed) notifyListeners();
    }
  }

  void _onMutation(StatusMutation mutation) {
    if (!isCurrent ||
        mutation.sessionRevision != _session!.revision ||
        mutation.statusId != statusId ||
        busy) {
      return;
    }
    ++_generation;
    loading = false;
    if (mutation.kind == StatusMutationKind.deleted) {
      status = null;
      stops = const [];
      sevStops = const {};
      _refreshTimer?.cancel();
      error = 'Diese Fahrt wurde gelöscht.';
      notifyListeners();
    } else if (mutation.kind == StatusMutationKind.like && status != null) {
      final liked = mutation.liked;
      if (liked != null && liked != status!.liked) {
        status = status!.copyWith(
          liked: liked,
          likes: (status!.likes + (liked ? 1 : -1)).clamp(0, 1 << 31),
        );
      }
      notifyListeners();
    } else {
      unawaited(refresh(silent: true));
    }
  }

  Future<void> refresh({bool silent = false}) async {
    if (!isCurrent || busy) return;
    final generation = ++_generation;
    if (!silent) {
      loading = true;
      error = null;
      notifyListeners();
    }
    try {
      for (var attempt = 0; attempt < 2; attempt++) {
        final revision = store.contentRevision;
        final incoming = await store.status(statusId);
        if (!_accepts(generation)) return;
        List<Stop>? fetched;
        Object? stopError;
        if (incoming.checkin?.trip case final int trip) {
          final api = store.api;
          try {
            fetched = await api.stopovers(trip);
          } catch (e) {
            stopError = e;
          } finally {
            api.close();
          }
        } else {
          fetched = const [];
        }
        if (!_accepts(generation)) return;
        if (revision != store.contentRevision) continue;
        final route =
            fetched ?? compatibleExistingStops(incoming, status, stops);
        status = hydrateBoundaries(incoming, route);
        stops = List.unmodifiable(route);
        loading = false;
        lastUpdatedMillis = DateTime.now().millisecondsSinceEpoch;
        if (!silent && stopError != null) {
          error =
              'Halte konnten nicht geladen werden. Bitte erneut aktualisieren.';
        }
        notifyListeners();
        _enrich();
        return;
      }
      throw const StaleRequestException();
    } catch (e) {
      if (!_accepts(generation)) return;
      loading = false;
      if (!silent || status == null) {
        error = safeDetailError(e, 'Fahrt konnte nicht geladen werden.');
      }
      notifyListeners();
    }
  }

  bool _accepts(int generation) =>
      isCurrent && generation == _generation && !busy;

  void _enrich() {
    final checkin = status?.checkin;
    if (checkin == null ||
        stops.isEmpty ||
        !SevStopResolver.isReplacementBus(checkin)) {
      ++_sevGeneration;
      _enricher.cancel();
      _sevFingerprint = null;
      sevStops = const {};
      loadingSev = false;
      return;
    }
    // Realtime-only refreshes retain slow, already-running public requests.
    final fingerprint = jsonEncode({
      'id': statusId,
      'trip': checkin.trip,
      'tripUuid': checkin.tripUuid,
      'category': checkin.category,
      'mode': checkin.mode,
      'line': checkin.lineName,
      'origin': checkin.origin == null
          ? null
          : SevStopResolver.visitKey(checkin.origin!),
      'destination': checkin.destination == null
          ? null
          : SevStopResolver.visitKey(checkin.destination!),
      'stops': [
        for (final stop in stops)
          {
            'key': SevStopResolver.visitKey(stop),
            'station': stop.station?.toJson(),
            'arrival': stop.arrivalPlanned,
            'departure': stop.departurePlanned,
            'cancelled': stop.cancelled,
          },
      ],
    });
    if (fingerprint == _sevFingerprint) return;
    _sevFingerprint = fingerprint;
    final generation = ++_sevGeneration, route = stops;
    sevStops = const {};
    loadingSev = true;
    notifyListeners();
    unawaited(() async {
      try {
        final maps = await _enricher.loadMaps(checkin, route);
        if (!isCurrent ||
            generation != _sevGeneration ||
            fingerprint != _sevFingerprint) {
          return;
        }
        sevStops = SevStopResolver.resolve(
          checkin,
          route,
          maps,
          DateTime.now().millisecondsSinceEpoch,
        );
      } catch (_) {
        if (!isCurrent || generation != _sevGeneration) return;
        sevStops = const {};
      }
      if (!isCurrent || generation != _sevGeneration) return;
      loadingSev = false;
      notifyListeners();
    }());
  }

  bool beginEditing() {
    if (!isOwn || busy || status == null) return false;
    draft = StatusEditDraft(status!);
    error = null;
    notifyListeners();
    return true;
  }

  void cancelEditing() {
    if (updating) return;
    draft = null;
    notifyListeners();
  }

  void changedDraft() {
    if (!updating) notifyListeners();
  }

  Future<bool> save() async {
    if (!isCurrent || !isOwn || busy || draft == null || status == null) {
      return false;
    }
    UpdateStatusRequest request;
    try {
      validateEditedDestination(draft!, status!, stops);
      request = draft!.build(status!);
    } catch (e) {
      error = safeDetailError(e, 'Bitte Eingaben prüfen.');
      notifyListeners();
      return false;
    }
    if (request.toJson().isEmpty) {
      draft = null;
      notifyListeners();
      return true;
    }
    final expected = _session!;
    ++_generation;
    updating = true;
    loading = false;
    error = null;
    notifyListeners();
    try {
      final updated = await store.updateStatus(
        statusId,
        request,
        expectedSession: expected,
      );
      if (!isCurrent) return false;
      final compatible = compatibleExistingStops(updated, status, stops);
      status = hydrateBoundaries(updated, compatible);
      stops = compatible;
      draft = null;
      updating = false;
      ++_sevGeneration;
      _enricher.cancel();
      _sevFingerprint = null;
      sevStops = const {};
      notifyListeners();
      await refresh(silent: true);
      return true;
    } on AcceptedMutationException catch (e) {
      if (!isCurrent) return false;
      updating = false;
      draft = null;
      error = e.message;
      notifyListeners();
      await refresh(silent: true);
      return true;
    } catch (e) {
      if (!isCurrent) return false;
      updating = false;
      error = safeDetailError(e, 'Änderung fehlgeschlagen.');
      notifyListeners();
      return false;
    }
  }

  Future<bool> delete({Future<void> Function()? cleanup}) async {
    if (!isCurrent || !isOwn || busy) return false;
    final expected = _session!;
    final wasActive = store.activeStatusId == statusId;
    ++_generation;
    deleting = true;
    loading = false;
    error = null;
    notifyListeners();
    try {
      await store.deleteStatus(statusId, expectedSession: expected);
    } catch (e) {
      if (isCurrent) {
        deleting = false;
        error = safeDetailError(e, 'Löschen fehlgeschlagen.');
        notifyListeners();
      }
      return false;
    }
    // Confirmed DELETE remains success even if the independent native cleanup fails.
    if (!isCurrent) return true;
    if (wasActive &&
        cleanup != null &&
        (store.activeStatusId == null || store.activeStatusId == statusId)) {
      try {
        await cleanup().timeout(const Duration(seconds: 15));
      } catch (_) {
        if (isCurrent) {
          error =
              'Fahrt gelöscht; lokale Begleitung konnte nicht beendet werden. Bitte Begleitung stoppen.';
        }
      }
    }
    if (isCurrent) {
      deleting = false;
      status = null;
      stops = const [];
      _refreshTimer?.cancel();
      ++_sevGeneration;
      _enricher.cancel();
      notifyListeners();
    }
    return true;
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    ++_sevGeneration;
    _refreshTimer?.cancel();
    _enricher.cancel();
    _mutations.cancel();
    store.removeListener(_storeChanged);
    if (_ownsRepository) _repository.close();
    super.dispose();
  }
}

Status hydrateBoundaries(Status status, List<Stop> stops) {
  final checkin = status.checkin;
  if (checkin == null) return status;
  final origin = uniqueVisitIndex(stops, checkin.origin),
      destination = uniqueVisitIndex(stops, checkin.destination);
  return Status.fromJson({
    ...status.toJson(),
    'checkin': {
      ...checkin.toJson(),
      if (origin != null) 'origin': stops[origin].toJson(),
      if (destination != null) 'destination': stops[destination].toJson(),
    },
  });
}

List<Stop> compatibleExistingStops(
  Status incoming,
  Status? previous,
  List<Stop> stops,
) {
  final checkin = incoming.checkin, old = previous?.checkin;
  if (incoming.id != previous?.id ||
      checkin?.trip == null ||
      checkin?.trip != old?.trip ||
      (checkin?.tripUuid != null &&
          old?.tripUuid != null &&
          checkin?.tripUuid != old?.tripUuid)) {
    return const [];
  }
  for (final boundary in [checkin?.origin, checkin?.destination]) {
    if (boundary == null) return const [];
    final matches = stops.where(
      (stop) =>
          stop.matchesStopover(boundary) &&
          stop.stationId == boundary.stationId &&
          _sameInstant(stop.arrivalPlanned, boundary.arrivalPlanned) &&
          _sameInstant(stop.departurePlanned, boundary.departurePlanned),
    );
    if (matches.length != 1) return const [];
  }
  return stops;
}

bool _sameInstant(String? a, String? b) =>
    a == b ||
    (a != null &&
        b != null &&
        DateTime.tryParse(a) != null &&
        DateTime.tryParse(a) == DateTime.tryParse(b));
String safeDetailError(Object error, String fallback) => switch (error) {
  ApiException e => e.message,
  StaleRequestException _ =>
    'Die Sitzung oder Fahrt wurde geändert. Bitte erneut aktualisieren.',
  FormatException e => e.message,
  _ => fallback,
};

void validateEditedDestination(
  StatusEditDraft draft,
  Status current,
  List<Stop> route,
) {
  if (!draft.destinationChanged) return;
  final origin = uniqueVisitIndex(route, current.checkin?.origin);
  final destination = uniqueVisitIndex(route, draft.destination);
  if (origin == null ||
      destination == null ||
      destination <= origin ||
      route[destination].cancelled ||
      !_sameInstant(
        route[destination].arrivalPlanned,
        draft.destination?.arrivalPlanned,
      ) ||
      !_sameInstant(
        route[destination].departurePlanned,
        draft.destination?.departurePlanned,
      )) {
    throw const FormatException(
      'Der gewählte Ausstieg wurde inzwischen geändert. Bitte das Ziel erneut auswählen.',
    );
  }
}
