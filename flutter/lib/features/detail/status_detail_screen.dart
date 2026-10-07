import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../data/app_store.dart';
import '../../data/models.dart';
import '../../shared/theme.dart';
import '../../shared/widgets.dart';
import '../../tracking/gps_journey_time_estimator.dart';
import '../../tracking/journey_time_resolver.dart';
import '../../tracking/sev_enrichment.dart';
import 'detail_presentation.dart';
import 'status_detail_controller.dart';
import 'status_edit_draft.dart';

class StatusDetailScreen extends StatefulWidget {
  const StatusDetailScreen({
    super.key,
    required this.store,
    required this.statusId,
    this.tracking,
    this.onTrack,
    this.onStop,
    this.onDeleted,
    this.onUserTap,
  });
  final AppStore store;
  final int statusId;
  final ValueListenable<Map<String, dynamic>?>? tracking;
  final Future<void> Function(Status, List<Stop>)? onTrack;
  final Future<void> Function()? onStop;
  final VoidCallback? onDeleted;
  final ValueChanged<String>? onUserTap;
  @override
  State<StatusDetailScreen> createState() => _StatusDetailScreenState();
}

class _StatusDetailScreenState extends State<StatusDetailScreen>
    with WidgetsBindingObserver {
  late StatusDetailController _controller;
  Timer? _clock;
  bool _trackingBusy = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _controller = StatusDetailController(widget.store, widget.statusId)
      ..start();
    _clock = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void didUpdateWidget(StatusDetailScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.store != widget.store ||
        oldWidget.statusId != widget.statusId) {
      _controller.dispose();
      _controller = StatusDetailController(widget.store, widget.statusId)
        ..start();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _controller.setObserved(state == AppLifecycleState.resumed);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _clock?.cancel();
    _controller.dispose();
    super.dispose();
  }

  Future<void> _edit() async {
    if (!_controller.beginEditing()) return;
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _EditStatusDialog(controller: _controller),
    );
    if (mounted && !_controller.updating) _controller.cancelEditing();
  }

  Future<void> _delete() async {
    if (_controller.busy || !_controller.isOwn) return;
    final revision = widget.store.sessionRevision;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Fahrt löschen?'),
        content: const Text('Dieser Check-in wird bei Träwelling gelöscht.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Abbrechen'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Löschen'),
          ),
        ],
      ),
    );
    if (!mounted ||
        confirmed != true ||
        widget.store.sessionRevision != revision) {
      return;
    }
    final deleted = await _controller.delete(cleanup: widget.onStop);
    if (!mounted || widget.store.sessionRevision != revision || !deleted) {
      return;
    }
    if (_controller.error != null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(_controller.error!)));
    }
    if (widget.onDeleted != null) {
      widget.onDeleted!();
    } else {
      await Navigator.of(context).maybePop();
    }
  }

  Future<void> _changeTracking() async {
    final status = _controller.status, session = widget.store.session;
    if (_trackingBusy ||
        _controller.busy ||
        !_controller.isOwn ||
        status == null ||
        session == null) {
      return;
    }
    setState(() => _trackingBusy = true);
    try {
      if (widget.store.activeStatusId == widget.statusId) {
        await widget.onStop?.call();
      } else {
        await widget.onTrack?.call(status, _controller.stops);
      }
    } catch (_) {
      if (mounted && widget.store.isCurrent(session)) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Begleitung konnte nicht geändert werden. Bitte Berechtigungen prüfen.',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _trackingBusy = false);
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([
      _controller,
      widget.store,
      if (widget.tracking != null) widget.tracking!,
    ]),
    builder: (context, _) {
      final status = _controller.status;
      final snapshot = acceptedTracking(
        widget.tracking?.value,
        statusId: widget.statusId,
        sessionRevision: widget.store.sessionRevision,
        own: _controller.isOwn,
        activeStatusId: widget.store.activeStatusId,
      );
      // One current instant is used throughout this frame, including fresh GPS arriving between ticks.
      final now = DateTime.now().millisecondsSinceEpoch;
      return PopScope(
        canPop: !_controller.busy && !_trackingBusy,
        child: Scaffold(
          appBar: RoutelyAppBar(
            title: 'Fahrt-Details',
            actions: [
              if (_isLive(status))
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 17, horizontal: 4),
                  child: _Tag(label: '● LIVE', color: Colors.greenAccent),
                ),
              IconButton(
                tooltip: 'Aktualisieren',
                onPressed: _controller.busy || _controller.loading
                    ? null
                    : _controller.refresh,
                icon: const Icon(Icons.refresh),
              ),
              if (_controller.isOwn) ...[
                IconButton(
                  tooltip: 'Fahrt bearbeiten',
                  onPressed: _controller.busy ? null : _edit,
                  icon: const Icon(Icons.edit),
                ),
                IconButton(
                  tooltip: 'Fahrt löschen',
                  onPressed: _controller.busy ? null : _delete,
                  icon: const Icon(Icons.delete_outline),
                ),
              ],
            ],
          ),
          body: ResponsiveContent(
            child: _controller.loading && status == null
                ? const StateMessage(
                    icon: Icons.train,
                    title: 'Fahrt wird geladen',
                    loading: true,
                  )
                : status == null
                ? StateMessage(
                    icon: Icons.error_outline,
                    title: 'Fahrt nicht verfügbar',
                    message: _controller.error,
                    actionLabel: 'Erneut laden',
                    onAction: _controller.refresh,
                  )
                : RefreshIndicator(
                    onRefresh: _controller.refresh,
                    child: ListView(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.all(16),
                      children: [
                        if (_controller.loading || _controller.busy)
                          const LinearProgressIndicator(),
                        if (_controller.error != null)
                          _ErrorMessage(_controller.error!),
                        _UserHeader(
                          status: status,
                          onTap: status.user?.username.isNotEmpty == true
                              ? () => widget.onUserTap?.call(
                                  status.user!.username,
                                )
                              : null,
                        ),
                        const SizedBox(height: 8),
                        if (status.checkin != null)
                          _TripInformation(
                            status: status,
                            gps: decodeGpsTimes(snapshot),
                            now: now,
                            loadingSev: _controller.loadingSev,
                            hasSev: _controller.sevStops.isNotEmpty,
                          ),
                        if (snapshot != null &&
                            widget.store.settings.tripChangeAlertsEnabled)
                          _TripChanges(snapshot: snapshot),
                        if (_controller.isOwn &&
                            (widget.onTrack != null || widget.onStop != null))
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            child: FilledButton.tonalIcon(
                              onPressed:
                                  _controller.busy ||
                                      _trackingBusy ||
                                      (widget.store.activeStatusId !=
                                              widget.statusId &&
                                          _controller.stops.isEmpty)
                                  ? null
                                  : _changeTracking,
                              icon: _trackingBusy
                                  ? const SizedBox.square(
                                      dimension: 18,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : Icon(
                                      widget.store.activeStatusId ==
                                              widget.statusId
                                          ? Icons.stop_circle_outlined
                                          : Icons.navigation_outlined,
                                    ),
                              label: Text(
                                widget.store.activeStatusId == widget.statusId
                                    ? 'Begleitung beenden'
                                    : 'Fahrt begleiten',
                              ),
                            ),
                          ),
                        if (status.checkin != null &&
                            _controller.stops.isNotEmpty)
                          _StopTimeline(
                            status: status,
                            stops: _controller.stops,
                            tracking: snapshot,
                            now: now,
                            sev: _controller.sevStops,
                          ),
                        if (status.checkin != null && _controller.stops.isEmpty)
                          const StateMessage(
                            icon: Icons.timeline,
                            title: 'Kein Haltestellenverlauf verfügbar',
                            message:
                                'Die Fahrtzeiten bleiben im Fahrtkopf sichtbar.',
                          ),
                        const SizedBox(height: 24),
                      ],
                    ),
                  ),
          ),
        ),
      );
    },
  );
  bool _isLive(Status? status) {
    if (_controller.lastUpdatedMillis == null || status?.checkin == null) {
      return false;
    }
    final time = DateTime.tryParse(
      status!.checkin!.origin?.departurePlanned ?? '',
    )?.toLocal();
    final now = DateTime.now();
    return time != null &&
        time.year == now.year &&
        time.month == now.month &&
        time.day == now.day;
  }
}

class _UserHeader extends StatelessWidget {
  const _UserHeader({required this.status, this.onTap});
  final Status status;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(12),
            child: Row(
              children: [
                Avatar(user: status.user, size: 48),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        status.user?.displayName ??
                            status.user?.username ??
                            'Unbekannt',
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 17,
                        ),
                      ),
                      Text(
                        '@${status.user?.username ?? ''}',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          if (status.body?.trim().isNotEmpty == true) ...[
            const SizedBox(height: 12),
            SelectableText(status.body!),
          ],
        ],
      ),
    ),
  );
}

class _TripInformation extends StatelessWidget {
  const _TripInformation({
    required this.status,
    required this.gps,
    required this.now,
    required this.loadingSev,
    required this.hasSev,
  });
  final Status status;
  final GpsJourneyTimes? gps;
  final int now;
  final bool loadingSev, hasSev;
  @override
  Widget build(BuildContext context) {
    final checkin = status.checkin!,
        replacement = SevStopResolver.isReplacementBus(checkin);
    final boundaries = displayStops([
      if (checkin.origin != null) checkin.origin!,
      if (checkin.destination != null) checkin.destination!,
    ], checkin);
    final origin = boundaries.where((s) => s.isOrigin).firstOrNull;
    final destination = boundaries.where((s) => s.isDestination).lastOrNull;
    final departure = JourneyTimeResolver.departure(
      origin,
      gps,
      now,
      manualTime: checkin.manualDeparture,
    );
    final arrival = JourneyTimeResolver.arrival(
      destination,
      gps,
      now,
      manualTime: checkin.manualArrival,
    );
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                LineBadge(
                  line: checkin.lineName ?? '?',
                  category: checkin.category,
                  color: checkin.routeColor,
                  textColor: checkin.routeTextColor,
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _category(checkin.category),
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                      if (checkin.operatorName != null)
                        Text(
                          checkin.operatorName!,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                    ],
                  ),
                ),
              ],
            ),
            if (replacement)
              Padding(
                padding: const EdgeInsets.only(top: 14),
                child: _TintPanel(
                  color: RoutelyColors.amber,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Row(
                        children: [
                          Icon(
                            Icons.directions_bus,
                            size: 18,
                            color: RoutelyColors.amber,
                          ),
                          SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              'Schienenersatzverkehr',
                              style: TextStyle(
                                color: RoutelyColors.amber,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        hasSev
                            ? 'Ersatzhaltestellen und Wegbeschreibungen stehen bei den Halten.'
                            : loadingSev
                            ? 'Ersatzhaltestellen werden auf bahnhof.de gesucht.'
                            : 'Keine passenden Ersatzhaltestellen gefunden. Die Stationsdaten bleiben aktiv.',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ),
            const SizedBox(height: 14),
            _TintPanel(
              color: RoutelyColors.purple,
              child: Row(
                children: [
                  Icon(
                    status.business == 1
                        ? Icons.work
                        : status.business == 2
                        ? Icons.home
                        : Icons.person,
                    color: RoutelyColors.purple,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'Reisegrund: ${status.business == 1
                          ? 'Geschäftlich'
                          : status.business == 2
                          ? 'Arbeitsweg'
                          : 'Privat'}',
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            _MiniRoute(
              origin: checkin.origin?.stationName ?? '–',
              destination: checkin.destination?.stationName ?? '–',
            ),
            const SizedBox(height: 16),
            const Divider(),
            const SizedBox(height: 12),
            Wrap(
              spacing: 12,
              runSpacing: 10,
              children: [
                if (checkin.distanceMeters != null)
                  _Metric(
                    icon: Icons.route,
                    text:
                        '${(checkin.distanceMeters! / 1000).toStringAsFixed(1).replaceAll('.', ',')} km',
                    color: RoutelyColors.teal,
                  ),
                if (checkin.duration != null)
                  _Metric(
                    icon: Icons.schedule,
                    text: '${checkin.duration} min',
                    color: RoutelyColors.purple,
                  ),
                if (checkin.points != null &&
                    status.user?.pointsEnabled != false)
                  _Metric(
                    icon: Icons.stars,
                    text: '${checkin.points} Pkt',
                    color: RoutelyColors.amber,
                  ),
              ],
            ),
            const SizedBox(height: 12),
            const Divider(),
            const SizedBox(height: 12),
            _HeaderTime(label: 'Abfahrt', time: departure),
            const SizedBox(height: 12),
            _HeaderTime(label: 'Ankunft', time: arrival),
          ],
        ),
      ),
    );
  }
}

/// Only already-authorized active tracking snapshots reach this view. These are
/// the last confirmed change events, rather than guessed causes of a delay.
class _TripChanges extends StatelessWidget {
  const _TripChanges({required this.snapshot});
  final Map<String, dynamic> snapshot;
  @override
  Widget build(BuildContext context) {
    final raw = snapshot['changes'];
    if (raw is! List) return const SizedBox.shrink();
    final changes = <String, Map>{};
    for (final value in raw.whereType<Map>()) {
      if (value['key'] is! String ||
          value['title'] is! String ||
          value['message'] is! String) {
        continue;
      }
      final key = value['key'] as String,
          title = value['title'] as String,
          message = value['message'] as String;
      if (key.isEmpty ||
          key.length > 512 ||
          title.isEmpty ||
          title.length > 256 ||
          message.isEmpty ||
          message.length > 2048) {
        continue;
      }
      changes[key] = value;
      if (changes.length >= 4) break;
    }
    if (changes.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Row(
                children: [
                  Icon(Icons.campaign_outlined, color: RoutelyColors.amber),
                  SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      'Zuletzt gemeldete Änderungen',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                  ),
                ],
              ),
              for (final entry in changes.entries)
                Padding(
                  key: ValueKey('trip-change-${entry.key}'),
                  padding: const EdgeInsets.only(top: 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        entry.value['title'] as String,
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                      const SizedBox(height: 4),
                      Text(entry.value['message'] as String),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StopTimeline extends StatelessWidget {
  const _StopTimeline({
    required this.status,
    required this.stops,
    required this.tracking,
    required this.now,
    required this.sev,
  });
  final Status status;
  final List<Stop> stops;
  final Map<String, dynamic>? tracking;
  final int now;
  final Map<String, SevStopInfo> sev;
  @override
  Widget build(BuildContext context) {
    final checkin = status.checkin!, timeline = displayStops(stops, checkin);
    final origin = uniqueVisitIndex(stops, checkin.origin),
        destination = uniqueVisitIndex(stops, checkin.destination);
    final progress = resolveTimelineProgress(
      stops,
      timeline,
      now,
      tracking,
      destinationIndex: destination,
    );
    final gps = decodeGpsTimes(tracking),
        replacement = SevStopResolver.isReplacementBus(checkin);
    final expired =
        gps != null &&
        (now > gps.validUntilMillis ||
            now - gps.updatedAtMillis >
                GpsJourneyTimeEstimator.maxFixAgeMillis);
    final reason = expired
        ? 'noFreshLocation'
        : tracking?['etaReason'] as String?;
    final subtitle = progress.source == TimelineSource.gps
        ? 'Fortschritt per GPS'
        : progress.source == TimelineSource.waiting
        ? 'Position wird ermittelt'
        : 'Fahrplan-Schätzung · ungefähre Position';
    final geometry = tracking?['geometrySource'];
    final tripProgress = tracking?['tripProgress'];
    final remainingText =
        tripProgress is Map && tripProgress['remainingText'] is String
        ? tripProgress['remainingText'] as String
        : null;
    final liveSev = tracking?['sevStops'];
    SevStopInfo? stopInfo(Stop stop) {
      final key = SevStopResolver.visitKey(stop),
          value = liveSev is Map ? liveSev[key] : null;
      if (value is Map &&
          value['sourceUrl'] is String &&
          value['guidance'] is String) {
        return SevStopInfo(
          sourceUrl: value['sourceUrl'] as String,
          guidance: value['guidance'] as String,
          label: value['label'] is String ? value['label'] as String : null,
          reason: value['reason'] is String ? value['reason'] as String : null,
          latitude: value['latitude'] is num
              ? (value['latitude'] as num).toDouble()
              : null,
          longitude: value['longitude'] is num
              ? (value['longitude'] as num).toDouble()
              : null,
        );
      }
      return sev[key];
    }

    final realIndices = [
      for (var i = 0; i < stops.length; i++)
        if (!stops[i].cancelled) i,
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Row(
              children: [
                const Icon(Icons.timeline, color: RoutelyColors.purple),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Haltestellenverlauf',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      Text(
                        subtitle,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      if (remainingText != null)
                        Text(
                          remainingText,
                          style: const TextStyle(
                            fontWeight: FontWeight.w600,
                            fontSize: 12,
                          ),
                        ),
                      if (gpsUnavailableMessage(reason)
                          case final String message)
                        Padding(
                          padding: const EdgeInsets.only(top: 4),
                          child: Text(
                            message,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ),
                      if (geometry == 'tripPolyline')
                        Text(
                          'Träwelling-Streckenverlauf',
                          style: Theme.of(context).textTheme.labelSmall,
                        ),
                      if (geometry == 'roadModel')
                        Text(
                          'SEV-Straßenmodell',
                          style: Theme.of(context).textTheme.labelSmall,
                        ),
                      if (tracking?['reacquiring'] == true)
                        const Text(
                          'GPS wird nach Empfangsausfall wieder zugeordnet.',
                          style: TextStyle(fontSize: 12),
                        ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                _Tag(
                  label: '${stops.length} Halte',
                  color: RoutelyColors.purple,
                ),
              ],
            ),
          ),
        ),
        if (replacement) const _RoadAttribution(),
        const SizedBox(height: 16),
        for (var i = 0; i < stops.length; i++)
          _StopRow(
            key: ValueKey('stop-${SevStopResolver.visitKey(stops[i])}-$i'),
            stop: stops[i],
            index: i,
            progress: progress,
            first: i == realIndices.firstOrNull,
            last: i == realIndices.lastOrNull,
            actualFirst: i == 0,
            actualLast: i == stops.length - 1,
            origin: i == origin,
            destination: i == destination,
            inRange:
                origin != null &&
                destination != null &&
                i >= origin &&
                i <= destination,
            replacement: replacement,
            sev: stopInfo(stops[i]),
            arrival: JourneyTimeResolver.arrival(
              timeline[i],
              gps,
              now,
              manualTime: i == destination ? checkin.manualArrival : null,
            ),
            departure: JourneyTimeResolver.departure(
              timeline[i],
              gps,
              now,
              manualTime: i == origin ? checkin.manualDeparture : null,
            ),
          ),
      ],
    );
  }
}

class _StopRow extends StatelessWidget {
  const _StopRow({
    super.key,
    required this.stop,
    required this.index,
    required this.progress,
    required this.first,
    required this.last,
    required this.actualFirst,
    required this.actualLast,
    required this.origin,
    required this.destination,
    required this.inRange,
    required this.replacement,
    required this.sev,
    required this.arrival,
    required this.departure,
  });
  final Stop stop;
  final int index;
  final TimelineProgress progress;
  final bool first,
      last,
      actualFirst,
      actualLast,
      origin,
      destination,
      inRange,
      replacement;
  final SevStopInfo? sev;
  final JourneyTime? arrival, departure;
  @override
  Widget build(BuildContext context) {
    final badge = progress.badgeFor(index),
        highlighted = badge != null || origin || destination;
    final color = destination ? RoutelyColors.amber : RoutelyColors.teal;
    final platform = replacement ? null : trackingPlatform(stop, origin);
    final scheme = Theme.of(context).colorScheme;
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: 30,
            child: CustomPaint(
              painter: _TimelinePainter(
                index: index,
                progress: progress,
                first: actualFirst,
                last: actualLast,
                destination: destination,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 18),
              child: Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: highlighted
                      ? color.withValues(alpha: .08)
                      : scheme.surface.withValues(alpha: inRange ? .85 : .6),
                  borderRadius: BorderRadius.circular(22),
                  border: highlighted
                      ? Border.all(
                          color: color.withValues(alpha: .45),
                          width: 1.5,
                        )
                      : null,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: Text(
                            stop.stationName ?? 'Unbekannte Station',
                            style: TextStyle(
                              fontWeight: FontWeight.bold,
                              fontSize: 17,
                              color: stop.cancelled
                                  ? scheme.onSurface.withValues(alpha: .45)
                                  : null,
                              decoration: stop.cancelled
                                  ? TextDecoration.lineThrough
                                  : null,
                            ),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Flexible(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.end,
                            children: [
                              if (first && departure != null)
                                _TimeValue(time: departure)
                              else if (last && arrival != null)
                                _TimeValue(time: arrival)
                              else ...[
                                if (arrival != null)
                                  _TimeValue(time: arrival, prefix: 'An: '),
                                if (departure != null)
                                  Padding(
                                    padding: const EdgeInsets.only(top: 4),
                                    child: _TimeValue(
                                      time: departure,
                                      prefix: 'Ab: ',
                                    ),
                                  ),
                              ],
                            ],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 6,
                      runSpacing: 6,
                      children: [
                        if (stop.cancelled)
                          const _Tag(
                            label: 'HALT ENTFÄLLT',
                            color: RoutelyColors.error,
                          ),
                        if (!stop.cancelled && badge != null)
                          _Tag(label: badge, color: RoutelyColors.teal),
                        if (platform != null)
                          _Tag(
                            label: 'Gl. $platform',
                            color: scheme.onSurface.withValues(alpha: .45),
                          ),
                        if (first)
                          const _Tag(
                            label: 'Ⅰ‹ STARTHALTESTELLE',
                            color: RoutelyColors.teal,
                            filled: true,
                          ),
                        if (origin)
                          const _Tag(
                            label: '⇥ DEIN EINSTIEG',
                            color: RoutelyColors.amber,
                            filled: true,
                          ),
                        if (destination)
                          const _Tag(
                            label: '⇤ DEIN ZIEL',
                            color: RoutelyColors.amber,
                            filled: true,
                          ),
                        if (last)
                          const _Tag(
                            label: '›Ⅰ ENDSTATION',
                            color: RoutelyColors.teal,
                            filled: true,
                          ),
                      ],
                    ),
                    if (!stop.cancelled && sev != null)
                      _SevGuidance(info: sev!),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _TimelinePainter extends CustomPainter {
  _TimelinePainter({
    required this.index,
    required this.progress,
    required this.first,
    required this.last,
    required this.destination,
  });
  final int index;
  final TimelineProgress progress;
  final bool first, last, destination;
  @override
  void paint(Canvas canvas, Size size) {
    final x = size.width / 2, y = 30.0;
    final inactive = Paint()
      ..color = Colors.grey.withValues(alpha: .25)
      ..strokeWidth = 4;
    final active = Paint()
      ..color = RoutelyColors.teal
      ..strokeWidth = 4;
    if (!first) {
      canvas.drawLine(
        Offset(x, 0),
        Offset(x, y),
        index <= progress.passedThroughIndex ? active : inactive,
      );
    }
    if (!last) {
      canvas.drawLine(
        Offset(x, y),
        Offset(x, size.height),
        index < progress.passedThroughIndex ? active : inactive,
      );
    }
    final selected =
        progress.currentIndex == index || progress.nextIndex == index;
    final color = destination ? RoutelyColors.amber : RoutelyColors.teal;
    if (selected) {
      canvas.drawCircle(
        Offset(x, y),
        13,
        Paint()..color = color.withValues(alpha: .2),
      );
    }
    canvas.drawCircle(
      Offset(x, y),
      selected ? 8 : 6,
      Paint()
        ..color = color.withValues(
          alpha: index <= progress.passedThroughIndex || selected ? 1 : .4,
        ),
    );
    if (selected) {
      canvas.drawCircle(Offset(x, y), 3, Paint()..color = Colors.white);
    }
  }

  @override
  bool shouldRepaint(_TimelinePainter old) =>
      old.index != index ||
      old.progress != progress ||
      old.destination != destination ||
      old.first != first ||
      old.last != last;
}

class _SevGuidance extends StatefulWidget {
  const _SevGuidance({required this.info});
  final SevStopInfo info;
  @override
  State<_SevGuidance> createState() => _SevGuidanceState();
}

class _SevGuidanceState extends State<_SevGuidance> {
  bool expanded = false;
  @override
  void didUpdateWidget(_SevGuidance oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.info.guidance != widget.info.guidance) expanded = false;
  }

  @override
  Widget build(BuildContext context) {
    final info = widget.info,
        color = info.hasCoordinates ? RoutelyColors.teal : RoutelyColors.amber;
    final uri = safeSevMapUri(info.sourceUrl);
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.directions_bus_outlined, size: 18, color: color),
              const SizedBox(width: 6),
              Text(
                'SEV-Haltestelle',
                style: TextStyle(fontWeight: FontWeight.w600, color: color),
              ),
            ],
          ),
          if (info.label?.isNotEmpty == true)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                info.label!,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
          if (info.reason?.isNotEmpty == true)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                info.reason!,
                style: const TextStyle(
                  color: RoutelyColors.amber,
                  fontSize: 12,
                ),
              ),
            ),
          if (info.guidance.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              info.guidance,
              maxLines: expanded ? null : 3,
              overflow: expanded ? TextOverflow.visible : TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.bodySmall,
            ),
            TextButton(
              onPressed: () => setState(() => expanded = !expanded),
              child: Text(
                expanded
                    ? 'Wegbeschreibung einklappen'
                    : 'Wegbeschreibung anzeigen',
              ),
            ),
          ],
          if (uri != null)
            TextButton.icon(
              onPressed: () => _openExternal(context, uri),
              icon: const Icon(Icons.open_in_new, size: 16),
              label: const Text('Lageplan auf bahnhof.de'),
            ),
        ],
      ),
    );
  }
}

class _EditStatusDialog extends StatefulWidget {
  const _EditStatusDialog({required this.controller});
  final StatusDetailController controller;
  @override
  State<_EditStatusDialog> createState() => _EditStatusDialogState();
}

class _EditStatusDialogState extends State<_EditStatusDialog> {
  late final TextEditingController body, departure, arrival;
  @override
  void initState() {
    super.initState();
    final draft = widget.controller.draft!;
    body = TextEditingController(text: draft.body);
    departure = TextEditingController(text: draft.departure);
    arrival = TextEditingController(text: draft.arrival);
  }

  @override
  void dispose() {
    body.dispose();
    departure.dispose();
    arrival.dispose();
    super.dispose();
  }

  Future<void> _pick(bool isDeparture) async {
    final controller = widget.controller, draft = controller.draft;
    if (controller.updating || draft == null) return;
    final original =
        DateTime.tryParse(
          isDeparture ? draft.departure : draft.arrival,
        )?.toLocal() ??
        DateTime.now();
    final date = await showDatePicker(
      context: context,
      initialDate: original,
      firstDate: DateTime(1970),
      lastDate: DateTime(2100),
    );
    if (!mounted ||
        date == null ||
        controller.updating ||
        controller.draft != draft) {
      return;
    }
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(original),
    );
    if (!mounted ||
        time == null ||
        controller.updating ||
        controller.draft != draft) {
      return;
    }
    final value = DateTime(
      date.year,
      date.month,
      date.day,
      time.hour,
      time.minute,
    ).toUtc().toIso8601String();
    if (isDeparture) {
      departure.text = value;
      draft.departure = value;
    } else {
      arrival.text = value;
      draft.setArrival(value);
    }
    controller.changedDraft();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final controller = widget.controller, draft = controller.draft;
      if (draft == null) {
        return const AlertDialog(content: Text('Die Sitzung wurde geändert.'));
      }
      final origin = uniqueVisitIndex(
        controller.stops,
        controller.status?.checkin?.origin,
      );
      final available = <int>[
        if (origin != null)
          for (var i = origin + 1; i < controller.stops.length; i++)
            if (!controller.stops[i].cancelled &&
                controller.stops[i].stationId != null &&
                controller.stops[i].arrivalPlanned != null)
              i,
      ];
      final selected = uniqueVisitIndex(controller.stops, draft.destination);
      return PopScope(
        canPop: !controller.updating,
        child: AlertDialog(
          title: const Text('Fahrt bearbeiten'),
          content: SizedBox(
            width: 480,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (controller.error != null)
                    _ErrorMessage(controller.error!),
                  const Text('Ausstieg'),
                  const SizedBox(height: 8),
                  DropdownButton<int>(
                    isExpanded: true,
                    value: available.contains(selected) ? selected : null,
                    hint: Text(
                      draft.destination?.stationName ?? 'Ziel auswählen',
                    ),
                    items: [
                      for (final index in available)
                        DropdownMenuItem(
                          value: index,
                          child: Text(
                            '${controller.stops[index].stationName ?? '–'} · ${displayTime(controller.stops[index].arrivalPlanned)}',
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: controller.updating
                        ? null
                        : (index) {
                            if (index == null || controller.status == null) {
                              return;
                            }
                            if (draft.selectDestination(
                              controller.stops[index],
                              controller.status!,
                              controller.stops,
                            )) {
                              arrival.text = draft.arrival;
                            }
                            controller.changedDraft();
                          },
                  ),
                  const SizedBox(height: 16),
                  TextField(
                    controller: departure,
                    enabled: !controller.updating,
                    decoration: InputDecoration(
                      labelText: 'Abfahrt real (ISO-Zeit mit Datum)',
                      suffixIcon: IconButton(
                        tooltip: 'Datum und Uhrzeit wählen',
                        onPressed: controller.updating
                            ? null
                            : () => _pick(true),
                        icon: const Icon(Icons.calendar_month),
                      ),
                    ),
                    onChanged: (value) {
                      draft.departure = value;
                      controller.changedDraft();
                    },
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: arrival,
                    enabled: !controller.updating,
                    decoration: InputDecoration(
                      labelText: 'Ankunft real (ISO-Zeit mit Datum)',
                      suffixIcon: IconButton(
                        tooltip: 'Datum und Uhrzeit wählen',
                        onPressed: controller.updating
                            ? null
                            : () => _pick(false),
                        icon: const Icon(Icons.calendar_month),
                      ),
                    ),
                    onChanged: (value) {
                      draft.setArrival(value);
                      controller.changedDraft();
                    },
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Unveränderte Providerzeiten werden nicht gespeichert. Eine geleerte Zeit entfernt die manuelle Korrektur.',
                    style: TextStyle(fontSize: 12),
                  ),
                  const SizedBox(height: 16),
                  TextField(
                    controller: body,
                    enabled: !controller.updating,
                    minLines: 3,
                    maxLines: 6,
                    decoration: const InputDecoration(labelText: 'Status-Text'),
                    onChanged: (value) {
                      draft.body = value;
                      controller.changedDraft();
                    },
                  ),
                  const SizedBox(height: 16),
                  DropdownButtonFormField<int>(
                    initialValue: draft.visibility >= 0 && draft.visibility <= 5
                        ? draft.visibility
                        : null,
                    decoration: const InputDecoration(
                      labelText: 'Sichtbarkeit',
                    ),
                    items: const [
                      DropdownMenuItem(value: 0, child: Text('Öffentlich')),
                      DropdownMenuItem(value: 1, child: Text('Nicht gelistet')),
                      DropdownMenuItem(value: 2, child: Text('Follower')),
                      DropdownMenuItem(value: 3, child: Text('Privat')),
                      DropdownMenuItem(
                        value: 4,
                        child: Text('Nur angemeldete Nutzer'),
                      ),
                      DropdownMenuItem(
                        value: 5,
                        child: Text('Vertraute Personen'),
                      ),
                    ],
                    onChanged: controller.updating
                        ? null
                        : (value) {
                            if (value != null) draft.setVisibility(value);
                            controller.changedDraft();
                          },
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: controller.updating
                  ? null
                  : () => Navigator.pop(context),
              child: const Text('Abbrechen'),
            ),
            FilledButton(
              onPressed: controller.updating
                  ? null
                  : () async {
                      final saved = await controller.save();
                      if (!context.mounted) return;
                      if (saved) Navigator.pop(context);
                    },
              child: controller.updating
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Speichern'),
            ),
          ],
        ),
      );
    },
  );
}

class _HeaderTime extends StatelessWidget {
  const _HeaderTime({required this.label, required this.time});
  final String label;
  final JourneyTime? time;
  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(child: Text(label)),
      _TimeValue(time: time),
    ],
  );
}

class _TimeValue extends StatelessWidget {
  const _TimeValue({required this.time, this.prefix = ''});
  final JourneyTime? time;
  final String prefix;
  @override
  Widget build(BuildContext context) {
    final value = time;
    if (value == null) return Text('$prefix–');
    final changed =
        value.plannedMillis != null &&
        (value.millis - value.plannedMillis!).abs() >= 60000;
    final delay = value.delayMinutes ?? 0;
    final color = changed
        ? delay > 0
              ? RoutelyColors.warning
              : RoutelyColors.success
        : null;
    final gps =
        value.source == JourneyTimeSource.gpsEstimate ||
        value.source == JourneyTimeSource.gpsObserved;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Wrap(
          alignment: WrapAlignment.end,
          spacing: 5,
          runSpacing: 2,
          children: [
            if (prefix.isNotEmpty)
              Text(prefix, style: Theme.of(context).textTheme.bodySmall),
            if (changed)
              Text(
                _formatMillis(value.plannedMillis!),
                style: TextStyle(
                  color: Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: .45),
                  decoration: TextDecoration.lineThrough,
                  fontSize: 12,
                ),
              ),
            Text(
              _formatMillis(value.millis),
              style: TextStyle(fontWeight: FontWeight.w600, color: color),
            ),
          ],
        ),
        Text(
          value.sourceLabel,
          style: TextStyle(
            fontSize: 11,
            color: gps
                ? RoutelyColors.teal
                : Theme.of(
                    context,
                  ).colorScheme.onSurface.withValues(alpha: .55),
          ),
        ),
      ],
    );
  }
}

class _Tag extends StatelessWidget {
  const _Tag({required this.label, required this.color, this.filled = false});
  final String label;
  final Color color;
  final bool filled;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
    decoration: BoxDecoration(
      color: filled
          ? (color == RoutelyColors.amber
                ? RoutelyColors.amberLight
                : RoutelyColors.mint)
          : color.withValues(alpha: .12),
      borderRadius: BorderRadius.circular(6),
    ),
    child: Text(
      label,
      style: TextStyle(
        fontSize: 10,
        fontWeight: FontWeight.bold,
        color: filled
            ? color == RoutelyColors.amber
                  ? const Color(0xffb86400)
                  : const Color(0xff00665d)
            : color,
      ),
    ),
  );
}

class _Metric extends StatelessWidget {
  const _Metric({required this.icon, required this.text, required this.color});
  final IconData icon;
  final String text;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 10),
    decoration: BoxDecoration(
      color: color.withValues(alpha: .1),
      borderRadius: BorderRadius.circular(22),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 19, color: color),
        const SizedBox(width: 6),
        Text(
          text,
          style: TextStyle(color: color, fontWeight: FontWeight.bold),
        ),
      ],
    ),
  );
}

class _TintPanel extends StatelessWidget {
  const _TintPanel({required this.color, required this.child});
  final Color color;
  final Widget child;
  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: color.withValues(alpha: .09),
      borderRadius: BorderRadius.circular(14),
    ),
    child: child,
  );
}

class _MiniRoute extends StatelessWidget {
  const _MiniRoute({required this.origin, required this.destination});
  final String origin, destination;
  @override
  Widget build(BuildContext context) => Column(
    children: [
      Row(
        children: [
          const Icon(Icons.circle, size: 14, color: RoutelyColors.teal),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              origin,
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 17),
            ),
          ),
        ],
      ),
      const Align(
        alignment: Alignment.centerLeft,
        child: Padding(
          padding: EdgeInsets.only(left: 6),
          child: SizedBox(
            height: 26,
            child: VerticalDivider(
              color: RoutelyColors.teal,
              thickness: 3,
              width: 2,
            ),
          ),
        ),
      ),
      Row(
        children: [
          const Icon(Icons.circle, size: 14, color: RoutelyColors.amber),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              destination,
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 17),
            ),
          ),
        ],
      ),
    ],
  );
}

class _ErrorMessage extends StatelessWidget {
  const _ErrorMessage(this.message);
  final String message;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: _TintPanel(
      color: Theme.of(context).colorScheme.error,
      child: Text(
        message,
        style: TextStyle(color: Theme.of(context).colorScheme.error),
      ),
    ),
  );
}

class _RoadAttribution extends StatelessWidget {
  const _RoadAttribution();
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 8),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        TextButton(
          onPressed: () => _openExternal(
            context,
            Uri.parse('https://routing.openstreetmap.de/about.html'),
          ),
          child: const Text(
            'Straßenmodell: OSRM · © OpenStreetMap-Mitwirkende',
            style: TextStyle(fontSize: 11),
          ),
        ),
        TextButton(
          onPressed: () => _openExternal(
            context,
            Uri.parse('https://www.openstreetmap.org/fixthemap'),
          ),
          child: const Text(
            'Kartendaten verbessern ↗',
            style: TextStyle(fontSize: 11),
          ),
        ),
      ],
    ),
  );
}

Future<void> _openExternal(BuildContext context, Uri uri) async {
  var opened = false;
  try {
    opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
  } catch (_) {
    /* Show a readable platform fallback. */
  }
  if (!opened && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Der Link konnte nicht geöffnet werden.')),
    );
  }
}

String _formatMillis(int value) => displayTime(
  DateTime.fromMillisecondsSinceEpoch(value, isUtc: true).toIso8601String(),
);
String _category(String? value) => switch (value) {
  'nationalExpress' => 'Fernverkehr (ICE/IC)',
  'national' => 'Fernverkehr',
  'regionalExp' => 'RegionalExpress',
  'regional' => 'Regional (RE/RB)',
  'suburban' => 'S-Bahn',
  'subway' => 'U-Bahn',
  'tram' => 'Straßenbahn',
  'bus' => 'Bus',
  'ferry' => 'Fähre',
  _ => value ?? 'Fahrt',
};
