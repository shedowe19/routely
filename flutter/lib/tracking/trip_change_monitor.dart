enum TripChangeKind {
  platform,
  cancelled,
  restored,
  delayIncrease,
  delayDecrease,
}

class TripChangeEvent {
  const TripChangeEvent({
    required this.key,
    required this.kind,
    required this.stopKey,
    required this.title,
    required this.message,
  });
  final String key, stopKey, title, message;
  final TripChangeKind kind;
}

class TripChangeMonitorState {
  const TripChangeMonitorState({
    required this.statusId,
    this.lastEvents = const {},
  });
  final int statusId;
  final Map<String, String> lastEvents;
  Map<String, Object> toJson() => {
    'statusId': statusId,
    'lastEvents': lastEvents,
  };
  factory TripChangeMonitorState.fromJson(Map<String, dynamic> j) =>
      TripChangeMonitorState(
        statusId: j['statusId'] as int,
        lastEvents: Map<String, String>.from(j['lastEvents'] as Map? ?? {}),
      );
}

class TripChangeStop {
  const TripChangeStop({
    required this.key,
    required this.name,
    this.arrivalPlannedMillis,
    this.arrivalRealMillis,
    this.departurePlannedMillis,
    this.departureRealMillis,
    this.arrivalPlatform,
    this.departurePlatform,
    this.cancelled,
    this.isOrigin = false,
    this.isDestination = false,
    this.arrivalPlatformIsLive,
    this.departurePlatformIsLive,
  });
  final String key, name;
  final int? arrivalPlannedMillis,
      arrivalRealMillis,
      departurePlannedMillis,
      departureRealMillis;
  final String? arrivalPlatform, departurePlatform;
  final bool? cancelled, arrivalPlatformIsLive, departurePlatformIsLive;
  final bool isOrigin, isDestination;
}

class TripChangeSnapshot {
  const TripChangeSnapshot({
    required this.statusId,
    required this.observedAtMillis,
    required this.stops,
    required this.nextIndex,
    this.manualDepartureMillis,
    this.manualArrivalMillis,
  });
  final int statusId, observedAtMillis, nextIndex;
  final List<TripChangeStop> stops;
  final int? manualDepartureMillis, manualArrivalMillis;
}

class _DelayObservation {
  const _DelayObservation(this.plannedMillis, this.minutes, this.arrival);
  final int plannedMillis, minutes;
  final bool arrival;
}

/// Compares only consecutive successful provider snapshots. Clock/GPS ticks
/// cannot produce events. Restored routes deliberately establish a silent baseline.
class TripChangeMonitor {
  TripChangeMonitor({this.delayThresholdMinutes = 5});
  final int delayThresholdMinutes;
  int? _statusId;
  TripChangeSnapshot? _baseline;
  final Map<String, String> _lastEvents = {};
  final Map<String, _DelayObservation> _delayReferences = {};
  final Set<String> _platformLiveGaps = {};
  void reset(int statusId, {TripChangeMonitorState? state}) {
    _statusId = statusId;
    _baseline = null;
    _delayReferences.clear();
    _platformLiveGaps.clear();
    _lastEvents.clear();
    if (state?.statusId == statusId) _lastEvents.addAll(state!.lastEvents);
  }

  TripChangeMonitorState? getState() => _statusId == null
      ? null
      : TripChangeMonitorState(
          statusId: _statusId!,
          lastEvents: Map.unmodifiable(_lastEvents),
        );
  List<TripChangeEvent> observe(TripChangeSnapshot snapshot) {
    if (_statusId != snapshot.statusId) reset(snapshot.statusId);
    if (snapshot.stops.isEmpty ||
        snapshot.stops.map((s) => s.key).toSet().length !=
            snapshot.stops.length) {
      return [];
    }
    final previous = _baseline;
    if (previous != null &&
        snapshot.observedAtMillis <= previous.observedAtMillis) {
      return [];
    }
    _baseline = snapshot;
    if (previous == null) {
      for (final stop in snapshot.stops) {
        final delay = _delay(stop);
        if (delay != null) _delayReferences[stop.key] = delay;
      }
      _synchronizeValues(snapshot);
      return [];
    }
    final previousByKey = {for (final stop in previous.stops) stop.key: stop};
    final remaining = snapshot.stops
        .skip(snapshot.nextIndex.clamp(0, snapshot.stops.length))
        .toList();
    String? nextServed;
    for (final stop in remaining) {
      if (stop.cancelled != true) {
        nextServed = stop.key;
        break;
      }
    }
    final events = <TripChangeEvent>[];
    for (var index = 0; index < remaining.length; index++) {
      final stop = remaining[index], old = previousByKey[remaining[index].key];
      if (old == null) continue;
      if (old.cancelled != null &&
          stop.cancelled != null &&
          old.cancelled != stop.cancelled) {
        final cancelled = stop.cancelled!;
        final subject = stop.isDestination
            ? 'Deine Ausstiegshaltestelle ${stop.name}'
            : stop.isOrigin
            ? 'Deine Einstiegshaltestelle ${stop.name}'
            : index == 0
            ? 'Der nächste Halt ${stop.name}'
            : 'Der Halt ${stop.name}';
        _emit(
          events,
          stop,
          'cancelled',
          '$cancelled',
          cancelled ? TripChangeKind.cancelled : TripChangeKind.restored,
          cancelled ? 'Halt entfällt' : 'Halt wieder vorgesehen',
          cancelled
              ? '$subject entfällt laut aktueller Meldung. Prüfe deine Weiterreise.'
              : '$subject ist laut aktueller Meldung wieder vorgesehen.',
        );
      }
      if (stop.cancelled != true &&
          (stop.key == nextServed || stop.isDestination)) {
        final oldPlatform = _platform(old),
            newPlatform = _platform(stop),
            oldLive = _platformIsLive(old),
            newLive = _platformIsLive(stop);
        final liveLost = oldLive == true && newLive != true;
        if (liveLost) _platformLiveGaps.add(stop.key);
        final liveRestored =
            newLive == true && _platformLiveGaps.remove(stop.key);
        if (!liveLost &&
            !liveRestored &&
            oldPlatform != null &&
            newPlatform != null &&
            oldPlatform.toLowerCase() != newPlatform.toLowerCase()) {
          _emit(
            events,
            stop,
            'platform',
            newPlatform.toUpperCase(),
            TripChangeKind.platform,
            'Gleiswechsel in ${stop.name}',
            '${stop.name}: Gleis $newPlatform statt Gleis $oldPlatform.',
          );
        }
      }
    }
    final delayCandidates = remaining
        .where((s) => s.cancelled != true)
        .toList();
    // Stable partition: the original Kotlin sort preserves visit order within roles.
    final ordered = [
      ...delayCandidates.where((s) => s.isDestination),
      ...delayCandidates.where((s) => !s.isDestination),
    ];
    TripChangeStop? delayStop;
    for (final stop in ordered) {
      if (_delay(stop) != null &&
          previousByKey[stop.key] != null &&
          _delay(previousByKey[stop.key]!) != null) {
        delayStop = stop;
        break;
      }
    }
    if (delayStop != null) {
      final current = _delay(delayStop)!,
          prior = _delay(previousByKey[delayStop.key]!)!,
          reference = _delayReferences[delayStop.key];
      final manualChanged =
          (delayStop.isOrigin &&
              !current.arrival &&
              previous.manualDepartureMillis !=
                  snapshot.manualDepartureMillis) ||
          (delayStop.isDestination &&
              current.arrival &&
              previous.manualArrivalMillis != snapshot.manualArrivalMillis);
      if (manualChanged ||
          prior.plannedMillis != current.plannedMillis ||
          prior.arrival != current.arrival ||
          reference == null ||
          reference.plannedMillis != current.plannedMillis ||
          reference.arrival != current.arrival) {
        _delayReferences[delayStop.key] = current;
      } else if ((current.minutes - reference.minutes).abs() >=
          delayThresholdMinutes) {
        final increased = current.minutes > reference.minutes;
        final description = current.minutes > 0
            ? '${current.minutes} ${current.minutes == 1 ? 'Minute' : 'Minuten'} Verspätung'
            : current.minutes < 0
            ? '${-current.minutes} ${current.minutes == -1 ? 'Minute' : 'Minuten'} vor dem Fahrplan'
            : 'pünktlich';
        final title = increased && current.minutes > 0
            ? 'Mehr Verspätung'
            : !increased && reference.minutes > 0
            ? 'Verspätung verringert'
            : '${current.arrival ? 'Ankunft' : 'Abfahrt'} ${increased ? 'später' : 'früher'}';
        _emit(
          events,
          delayStop,
          'delay',
          '${current.minutes}',
          increased
              ? TripChangeKind.delayIncrease
              : TripChangeKind.delayDecrease,
          title,
          '${delayStop.name}: jetzt $description; ${(current.minutes - reference.minutes).abs()} Minuten ${increased ? 'später' : 'früher'} als zuletzt gemeldet.',
        );
        _delayReferences[delayStop.key] = current;
      }
    }
    for (final stop in snapshot.stops) {
      final observation = _delay(stop);
      if (observation == null) {
        _delayReferences.remove(stop.key);
      } else if (!_delayReferences.containsKey(stop.key) ||
          stop.key != delayStop?.key) {
        _delayReferences[stop.key] = observation;
      }
    }
    _synchronizeValues(snapshot);
    return events;
  }

  void _synchronizeValues(TripChangeSnapshot snapshot) {
    for (final stop in snapshot.stops) {
      final fields = {
        'platform': _platform(stop)?.toUpperCase(),
        'cancelled': stop.cancelled?.toString(),
        'delay': _delay(stop)?.minutes.toString(),
      };
      for (final field in fields.entries) {
        final key = '${stop.key}:${field.key}';
        if (field.value == null) {
          _lastEvents.remove(key);
        } else {
          _lastEvents[key] = field.value!;
        }
      }
    }
    _trim();
  }

  void _trim() {
    while (_lastEvents.length > 96) {
      _lastEvents.remove(_lastEvents.keys.first);
    }
  }

  String? _platform(TripChangeStop stop) {
    final value =
        (stop.isOrigin ? stop.departurePlatform : stop.arrivalPlatform)?.trim();
    return value == null || value.isEmpty ? null : value;
  }

  bool? _platformIsLive(TripChangeStop stop) =>
      stop.isOrigin ? stop.departurePlatformIsLive : stop.arrivalPlatformIsLive;
  _DelayObservation? _delay(TripChangeStop stop) {
    final arrival =
        !stop.isOrigin &&
        stop.arrivalPlannedMillis != null &&
        stop.arrivalRealMillis != null;
    final planned = arrival
            ? stop.arrivalPlannedMillis
            : stop.departurePlannedMillis,
        real = arrival ? stop.arrivalRealMillis : stop.departureRealMillis;
    return planned == null || real == null
        ? null
        : _DelayObservation(planned, (real - planned) ~/ 60000, arrival);
  }

  void _emit(
    List<TripChangeEvent> events,
    TripChangeStop stop,
    String field,
    String value,
    TripChangeKind kind,
    String title,
    String message,
  ) {
    final fieldKey = '${stop.key}:$field';
    if (_lastEvents[fieldKey] == value) return;
    _lastEvents[fieldKey] = value;
    _trim();
    events.add(
      TripChangeEvent(
        key: '$fieldKey:$value',
        kind: kind,
        stopKey: stop.key,
        title: title,
        message: message,
      ),
    );
  }
}
