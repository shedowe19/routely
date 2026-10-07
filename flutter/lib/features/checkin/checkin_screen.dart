import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';

import '../../data/app_store.dart';
import '../../data/models.dart';
import '../../platform/native_trip_bridge.dart';
import '../../shared/theme.dart';
import '../../shared/widgets.dart';
import 'checkin_controller.dart';
import 'checkin_selection.dart';

class CheckInScreen extends StatefulWidget {
  const CheckInScreen({
    super.key,
    required this.store,
    required this.onCreated,
    this.onSubmissionPendingChanged,
    this.isCurrentPage = true,
    this.initialStation,
    this.initialDeparture,
    this.initialTrip,
    this.recognitionWidget,
    this.onBackAtRoot,
  });
  final AppStore store;
  final Future<void> Function(Status) onCreated;
  final ValueChanged<bool>? onSubmissionPendingChanged;
  final bool isCurrentPage;
  final Station? initialStation;
  final Departure? initialDeparture;
  final Trip? initialTrip;
  final Widget? recognitionWidget;
  final VoidCallback? onBackAtRoot;

  @override
  State<CheckInScreen> createState() => _CheckInScreenState();
}

class _CheckInScreenState extends State<CheckInScreen> {
  late CheckInController controller;
  final query = TextEditingController();
  final native = NativeTripBridge();
  bool _lastPending = false;
  String? _lastWarning;

  @override
  void initState() {
    super.initState();
    controller = CheckInController.forStore(
      widget.store,
      (status) => widget.onCreated(status),
    );
    controller.addListener(_changed);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _loadRecognition();
    });
  }

  @override
  void didUpdateWidget(covariant CheckInScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.initialDeparture != widget.initialDeparture) {
      _loadRecognition();
    }
  }

  void _loadRecognition() {
    final departure = widget.initialDeparture;
    final station = widget.initialStation;
    if (departure != null && station != null && !controller.submissionPending) {
      unawaited(
        controller.selectDeparture(
          departure,
          recognizedStation: station,
          recognizedTrip: widget.initialTrip,
        ),
      );
    }
  }

  void _changed() {
    if (!mounted) return;
    if (query.text != controller.query) {
      query.value = TextEditingValue(
        text: controller.query,
        selection: TextSelection.collapsed(offset: controller.query.length),
      );
    }
    setState(() {});
    if (_lastPending != controller.submissionPending) {
      _lastPending = controller.submissionPending;
      widget.onSubmissionPendingChanged?.call(_lastPending);
    }
    if (controller.warning != null && _lastWarning != controller.warning) {
      _lastWarning = controller.warning;
      final warning = controller.warning!;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(warning)));
        }
      });
    }
  }

  @override
  void dispose() {
    controller.removeListener(_changed);
    controller.dispose();
    query.dispose();
    super.dispose();
  }

  Future<(double, double)> _requestLocation() async {
    if (native.supported) {
      Map<String, dynamic> location;
      try {
        location = await native.requestLocation();
      } on PlatformException catch (failure) {
        if (failure.code != 'location_permission') rethrow;
        final permissions = await native.requestPermissions();
        if (permissions['location'] != true) {
          throw const FormatException('Standortfreigabe wurde nicht erteilt.');
        }
        location = await native.requestLocation();
      }
      final lat = (location['latitude'] as num?)?.toDouble();
      final lon = (location['longitude'] as num?)?.toDouble();
      if (lat == null || lon == null) {
        throw const FormatException('Kein aktueller Standort verfügbar.');
      }
      return (lat, lon);
    }
    if (!await Geolocator.isLocationServiceEnabled()) {
      throw const FormatException('Standortdienste sind ausgeschaltet.');
    }
    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.denied ||
        permission == LocationPermission.deniedForever) {
      throw const FormatException('Standortfreigabe wurde nicht erteilt.');
    }
    final location = await Geolocator.getCurrentPosition(
      locationSettings: const LocationSettings(
        accuracy: LocationAccuracy.high,
        timeLimit: Duration(seconds: 30),
      ),
    );
    return (location.latitude, location.longitude);
  }

  String get _title => switch (controller.step) {
    CheckInStep.station => 'Check-in',
    CheckInStep.departures => 'Abfahrten — ${controller.station?.name ?? ''}',
    CheckInStep.destination =>
      'Ziel wählen — ${controller.departure?.lineName ?? ''}',
    CheckInStep.confirm => 'Details bestätigen',
    CheckInStep.success =>
      controller.result == null ? 'Check-in prüfen' : 'Eingecheckt!',
  };

  @override
  Widget build(BuildContext context) => PopScope(
    canPop:
        !controller.submissionPending &&
        (!widget.isCurrentPage || !controller.canGoBack),
    onPopInvokedWithResult: (didPop, _) {
      if (!didPop && widget.isCurrentPage && !controller.submissionPending) {
        if (controller.canGoBack) {
          controller.goBack();
        } else {
          widget.onBackAtRoot?.call();
        }
      }
    },
    child: Scaffold(
      appBar: RoutelyAppBar(
        title: _title,
        leading:
            controller.step == CheckInStep.station ||
                controller.step == CheckInStep.success
            ? null
            : IconButton(
                tooltip: 'Zurück',
                onPressed: controller.submissionPending
                    ? null
                    : controller.goBack,
                icon: const Icon(Icons.arrow_back),
              ),
        actions: controller.step == CheckInStep.departures
            ? [
                IconButton(
                  tooltip: 'Aktualisieren',
                  onPressed: controller.loading
                      ? null
                      : controller.refreshDepartures,
                  icon: const Icon(Icons.refresh),
                ),
              ]
            : const [],
      ),
      body: ResponsiveContent(
        child: switch (controller.step) {
          CheckInStep.station => _stationStep(),
          CheckInStep.departures => _departuresStep(),
          CheckInStep.destination => _destinationStep(),
          CheckInStep.confirm => _confirmStep(),
          CheckInStep.success => _successStep(),
        },
      ),
    ),
  );

  Widget _stationStep() => ListView(
    padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
    children: [
      if (widget.recognitionWidget != null) widget.recognitionWidget!,
      Row(
        children: [
          Expanded(
            child: TextField(
              controller: query,
              onChanged: controller.updateQuery,
              decoration: InputDecoration(
                labelText: 'Bahnhof suchen',
                hintText: 'z. B. Berlin Hbf',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: query.text.isEmpty
                    ? null
                    : IconButton(
                        tooltip: 'Löschen',
                        onPressed: () => controller.updateQuery(''),
                        icon: const Icon(Icons.clear),
                      ),
              ),
            ),
          ),
          const SizedBox(width: 8),
          IconButton.filled(
            tooltip: 'Stationen in der Nähe',
            onPressed: controller.loading
                ? null
                : () => controller.findNearby(_requestLocation),
            icon: const Icon(Icons.location_on),
          ),
        ],
      ),
      if (controller.error != null) _error(),
      if (controller.loading)
        const SizedBox(
          height: 260,
          child: StateMessage(
            icon: Icons.search,
            title: 'Stationen werden gesucht',
            message: 'Wir suchen passende Stationen für deinen Einstieg.',
            loading: true,
          ),
        )
      else if (controller.stations.isEmpty)
        SizedBox(
          height: 280,
          child: StateMessage(
            icon: Icons.train,
            title: controller.query.trim().length < 2
                ? 'Station suchen'
                : 'Kein Bahnhof gefunden',
            message: controller.query.trim().length < 2
                ? 'Gib mindestens 2 Zeichen ein oder nutze deinen Standort.'
                : 'Prüfe die Schreibweise oder suche nach einer größeren Station.',
          ),
        )
      else
        for (final station in controller.stations)
          Card(
            child: ListTile(
              leading: const Icon(Icons.train),
              title: Text(station.name ?? '–'),
              subtitle: station.rilIdentifier == null
                  ? null
                  : Text('RIL: ${station.rilIdentifier}'),
              trailing: const Icon(Icons.chevron_right),
              onTap: (station.id ?? 0) <= 0
                  ? null
                  : () => controller.selectStation(station),
            ),
          ),
    ],
  );

  Future<void> _pickDeparturesTime() async {
    final initial = controller.departuresWhen ?? DateTime.now();
    final date = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(2000),
      lastDate: DateTime(2100),
    );
    if (!mounted ||
        date == null ||
        controller.submissionPending ||
        controller.step != CheckInStep.departures) {
      return;
    }
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(initial),
    );
    if (!mounted ||
        time == null ||
        controller.submissionPending ||
        controller.step != CheckInStep.departures) {
      return;
    }
    await controller.refreshDepartures(
      when: DateTime(date.year, date.month, date.day, time.hour, time.minute),
    );
  }

  Widget _departuresStep() => Column(
    children: [
      ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * .3,
        ),
        child: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed: controller.loading ? null : _pickDeparturesTime,
                  icon: const Icon(Icons.calendar_month),
                  label: Text(
                    controller.departuresWhen == null
                        ? 'Datum und Uhrzeit'
                        : '${controller.departuresWhen!.day}.${controller.departuresWhen!.month}.${controller.departuresWhen!.year} ${TimeOfDay.fromDateTime(controller.departuresWhen!).format(context)}',
                  ),
                ),
                if (controller.departuresWhen != null)
                  TextButton(
                    onPressed: () =>
                        controller.refreshDepartures(resetTime: true),
                    child: const Text('Jetzt'),
                  ),
                for (final filter in DepartureFilter.values)
                  ChoiceChip(
                    label: Text(_filterLabel(filter)),
                    selected: controller.departureFilter == filter,
                    onSelected: (_) => controller.changeFilter(filter),
                  ),
              ],
            ),
          ),
        ),
      ),
      if (controller.error != null)
        _error(onRetry: controller.refreshDepartures),
      Expanded(
        child: controller.loading
            ? const StateMessage(
                icon: Icons.schedule,
                title: 'Lade Abfahrten',
                message: 'Wir suchen passende Verbindungen ab deiner Station.',
                loading: true,
              )
            : controller.visibleDepartures.isEmpty
            ? const StateMessage(
                icon: Icons.train,
                title: 'Keine Abfahrten gefunden',
                message:
                    'Für diese Station und Auswahl wurden keine passenden Fahrten gefunden.',
              )
            : RefreshIndicator(
                onRefresh: controller.refreshDepartures,
                child: ListView.builder(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                  itemCount: controller.visibleDepartures.length,
                  itemBuilder: (context, index) {
                    final departure = controller.visibleDepartures[index];
                    final color = RoutelyColors.transport(departure.category);
                    final delay = departure.delayMinutes ?? 0;
                    return Card(
                      child: ListTile(
                        leading: Container(
                          padding: const EdgeInsets.all(10),
                          decoration: BoxDecoration(
                            color: color.withValues(alpha: .12),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Icon(Icons.train, color: color),
                        ),
                        title: Wrap(
                          spacing: 8,
                          runSpacing: 4,
                          children: [
                            Text(
                              departure.lineName,
                              style: const TextStyle(
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            if (departure.cancelled)
                              Text(
                                'AUSFALL',
                                style: TextStyle(
                                  color: Theme.of(context).colorScheme.error,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                          ],
                        ),
                        subtitle: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('→ ${departure.direction ?? '–'}'),
                            Text(
                              departureTimeLabel(departure),
                              style: TextStyle(
                                color: delay < 0
                                    ? RoutelyColors.success
                                    : delay > 0
                                    ? Theme.of(context).colorScheme.error
                                    : null,
                              ),
                            ),
                            if (departure.platform?.trim().isNotEmpty == true)
                              Text('Gleis ${departure.platform}'),
                          ],
                        ),
                        trailing: departure.cancelled
                            ? null
                            : const Icon(Icons.chevron_right),
                        enabled:
                            !departure.cancelled && departure.tripId.isNotEmpty,
                        onTap: departure.cancelled
                            ? null
                            : () => controller.selectDeparture(departure),
                      ),
                    );
                  },
                ),
              ),
      ),
    ],
  );

  String _filterLabel(DepartureFilter filter) => switch (filter) {
    DepartureFilter.all => 'Alle',
    DepartureFilter.rail => 'Bahn',
    DepartureFilter.subway => 'U-Bahn',
    DepartureFilter.tram => 'Tram',
    DepartureFilter.bus => 'Bus',
    DepartureFilter.ferry => 'Fähre',
  };

  Widget _destinationStep() {
    if (controller.loading) {
      return const StateMessage(
        icon: Icons.route,
        title: 'Lade Halte',
        message: 'Der Fahrtverlauf wird vorbereitet.',
        loading: true,
      );
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        if (controller.error != null) _error(),
        if (controller.trip?.operatorName != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text('Betreiber: ${controller.trip!.operatorName}'),
          ),
        if (controller.destinations.isEmpty)
          const SizedBox(
            height: 260,
            child: StateMessage(
              icon: Icons.location_on,
              title: 'Keine Zwischenhalte verfügbar',
              message:
                  'Für diese Fahrt konnten keine möglichen Ziele geladen werden.',
            ),
          ),
        for (final destination in controller.destinations)
          Card(
            child: ListTile(
              leading: Icon(
                Icons.location_on,
                color: Theme.of(context).colorScheme.secondary,
              ),
              title: Text(destination.stationName ?? '–'),
              subtitle: Text(
                'Ankunft: ${checkInLocalTime(destination.arrivalReal ?? destination.arrivalPlanned)}',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => controller.selectDestination(destination),
            ),
          ),
      ],
    );
  }

  Widget _confirmStep() => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _info(
                Icons.train,
                'Linie',
                controller.departure?.lineName ?? '–',
              ),
              _info(
                Icons.trip_origin,
                'Von',
                controller.origin?.stationName ??
                    controller.station?.name ??
                    '–',
              ),
              _info(
                Icons.location_on,
                'Nach',
                controller.destination?.stationName ?? '–',
              ),
              _info(
                Icons.schedule,
                'Abfahrt',
                checkInLocalTime(
                  controller.origin?.departureReal ??
                      controller.origin?.departurePlanned,
                ),
              ),
              _info(
                Icons.schedule,
                'Ankunft',
                checkInLocalTime(
                  controller.destination?.arrivalReal ??
                      controller.destination?.arrivalPlanned,
                ),
              ),
              if (controller.departure?.platform != null)
                _info(
                  Icons.confirmation_number,
                  'Gleis',
                  controller.departure!.platform!,
                ),
              if (controller.trip?.operatorName != null)
                _info(
                  Icons.business,
                  'Betreiber',
                  controller.trip!.operatorName!,
                ),
            ],
          ),
        ),
      ),
      const SizedBox(height: 16),
      Text('Reisegrund', style: Theme.of(context).textTheme.labelLarge),
      const SizedBox(height: 8),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (var reason = 0; reason < 3; reason++)
            FilterChip(
              selected: controller.business == reason,
              onSelected: controller.submissionPending
                  ? null
                  : (_) => controller.updateDetails(reason: reason),
              avatar: Icon(
                [Icons.person, Icons.work, Icons.home][reason],
                size: 18,
              ),
              label: Text(['Privat', 'Geschäftlich', 'Arbeitsweg'][reason]),
            ),
        ],
      ),
      const SizedBox(height: 16),
      TextFormField(
        initialValue: controller.body,
        enabled: !controller.submissionPending,
        decoration: const InputDecoration(
          labelText: 'Statusmeldung (optional)',
          hintText: 'Was machst du auf dieser Reise?',
        ),
        minLines: 2,
        maxLines: 6,
        onChanged: (value) => controller.updateDetails(statusBody: value),
      ),
      const SizedBox(height: 16),
      Text(
        'Tatsächliche Zeiten korrigieren (optional)',
        style: Theme.of(context).textTheme.labelLarge,
      ),
      const SizedBox(height: 6),
      const Text(
        'Leer lassen für API-/Fahrplanzeiten. Eingaben werden nach dem Check-in als manuelle Zeiten gespeichert. Nutze ISO-Format mit Zeitzone, z. B. 2026-10-07T18:05:00+02:00.',
      ),
      const SizedBox(height: 12),
      TextFormField(
        initialValue: controller.manualDeparture,
        enabled: !controller.submissionPending,
        autocorrect: false,
        decoration: const InputDecoration(labelText: 'Abfahrt real'),
        onChanged: (value) => controller.updateDetails(realDeparture: value),
      ),
      const SizedBox(height: 12),
      TextFormField(
        initialValue: controller.manualArrival,
        enabled: !controller.submissionPending,
        autocorrect: false,
        decoration: const InputDecoration(labelText: 'Ankunft real'),
        onChanged: (value) => controller.updateDetails(realArrival: value),
      ),
      if (controller.error != null) _error(),
      const SizedBox(height: 24),
      FilledButton.icon(
        onPressed: controller.submissionPending ? null : controller.confirm,
        icon: controller.submissionPending
            ? const SizedBox(
                width: 22,
                height: 22,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.check_circle),
        label: Text(
          controller.submissionPending
              ? 'Check-in wird gespeichert…'
              : 'Jetzt einchecken!',
        ),
      ),
      const SizedBox(height: 24),
    ],
  );

  Widget _info(IconData icon, String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 20, color: Theme.of(context).colorScheme.primary),
        const SizedBox(width: 8),
        Expanded(
          child: Text.rich(
            TextSpan(
              children: [
                TextSpan(
                  text: '$label: ',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                TextSpan(text: value),
              ],
            ),
          ),
        ),
      ],
    ),
  );

  Widget _successStep() => ListView(
    padding: const EdgeInsets.all(24),
    children: [
      const SizedBox(height: 24),
      Icon(
        controller.result == null ? Icons.info : Icons.check_circle,
        size: 80,
        color: Theme.of(context).colorScheme.primary,
      ),
      const SizedBox(height: 16),
      Text(
        controller.result == null
            ? 'Check-in angenommen – bitte prüfen'
            : 'Erfolgreich eingecheckt!',
        textAlign: TextAlign.center,
        style: Theme.of(
          context,
        ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold),
      ),
      if (controller.submissionPending) ...[
        const SizedBox(height: 16),
        const Center(child: CircularProgressIndicator()),
        const SizedBox(height: 12),
        const Text(
          'Eingegebene Zeitkorrekturen werden gespeichert und die Fahrt wird für die Begleitung aktiviert.',
          textAlign: TextAlign.center,
        ),
      ],
      if (controller.warning != null)
        Padding(
          padding: const EdgeInsets.only(top: 16),
          child: Text(
            controller.warning!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        ),
      if ((controller.earnedPoints ?? 0) > 0)
        Padding(
          padding: const EdgeInsets.only(top: 12),
          child: Text(
            '+${controller.earnedPoints} Punkte erhalten!',
            textAlign: TextAlign.center,
            style: TextStyle(color: Theme.of(context).colorScheme.secondary),
          ),
        ),
      const SizedBox(height: 32),
      FilledButton(
        onPressed: controller.submissionPending
            ? null
            : () {
                _lastWarning = null;
                controller.reset();
              },
        child: const Text('Neuer Check-in'),
      ),
    ],
  );

  Widget _error({VoidCallback? onRetry}) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Semantics(
          liveRegion: true,
          child: Text(
            controller.error ?? '',
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        ),
        if (onRetry != null)
          TextButton(onPressed: onRetry, child: const Text('Erneut versuchen')),
      ],
    ),
  );
}
