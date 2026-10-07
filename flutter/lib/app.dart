import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'data/app_store.dart';
import 'data/models.dart';
import 'features/checkin/checkin_screen.dart';
import 'features/detail/status_detail_screen.dart';
import 'features/feed/feed_screen.dart';
import 'features/notifications/notifications_screen.dart';
import 'features/profile/profile_screen.dart';
import 'features/settings/settings_screen.dart';
import 'features/setup/setup_screen.dart';
import 'features/users/user_profile_screen.dart';
import 'features/users/user_search_screen.dart';
import 'platform/native_trip_bridge.dart';
import 'recognition/ride_recognition_runtime.dart';
import 'runtime/tracking_runtime.dart';
import 'shared/theme.dart';
import 'shared/widgets.dart';

class RoutelyApp extends StatefulWidget {
  const RoutelyApp({super.key, required this.store});
  final AppStore store;
  @override
  State<RoutelyApp> createState() => _RoutelyAppState();
}

class _RoutelyAppState extends State<RoutelyApp> with WidgetsBindingObserver {
  late final TrackingRuntime tracking;
  late final RideRecognitionRuntime recognition;
  String session = '';
  bool bootstrapping = true;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    session = widget.store.sessionRevision;
    tracking = TrackingRuntime(store: widget.store);
    recognition = RideRecognitionRuntime(widget.store);
    widget.store.addListener(_sessionChanged);
    unawaited(_initialize());
  }

  Future<void> _initialize() async {
    try {
      final bridge = NativeTripBridge();
      if (!widget.store.authenticated && bridge.supported) {
        final legacy = await bridge.legacyImport().timeout(
          const Duration(seconds: 10),
        );
        if (legacy['session'] is Map && !widget.store.authenticated) {
          final source = Map<String, dynamic>.from(legacy['session'] as Map);
          await widget.store.importLegacySession(
            source['serverUrl'] as String,
            source['accessToken'] as String,
            settings: legacy['settings'] is Map
                ? Map<String, dynamic>.from(legacy['settings'] as Map)
                : null,
            activeStatusId: (legacy['activeStatusId'] as num?)?.toInt(),
          );
          await bridge.commitLegacyImport(legacy['importId'] as String);
        }
      }
    } catch (_) {
      /* The importer keeps its old credentials until commit. */
    }
    try {
      await tracking.initialize();
    } catch (_) {
      /* Manual restart remains available. */
    }
    try {
      await recognition.initialize();
    } catch (_) {
      /* Recognition can be explicitly restarted. */
    }
    if (widget.store.authenticated) {
      unawaited(widget.store.validateSession().catchError((_) {}));
    }
    if (mounted) setState(() => bootstrapping = false);
  }

  void _sessionChanged() {
    final next = widget.store.sessionRevision;
    if (next == session) return;
    final previous = session;
    session = next;
    unawaited(tracking.stop(sessionRevision: previous).catchError((_) {}));
    unawaited(recognition.stop(clearPreference: false).catchError((_) {}));
    if (mounted) setState(() {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(widget.store.synchronizeSession().catchError((_) {}));
    }
  }

  @override
  void didChangePlatformBrightness() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.store.removeListener(_sessionChanged);
    tracking.dispose();
    recognition.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.store,
    builder: (context, _) => MaterialApp(
      key: ValueKey(widget.store.sessionRevision),
      title: 'Routely',
      debugShowCheckedModeBanner: false,
      theme: routelyTheme(
        widget.store.settings.theme,
        View.of(context).platformDispatcher.platformBrightness,
      ),
      locale: const Locale('de'),
      supportedLocales: const [Locale('de'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: bootstrapping
          ? const Scaffold(
              body: StateMessage(
                icon: Icons.train,
                title: 'Routely wird vorbereitet …',
                loading: true,
              ),
            )
          : widget.store.authenticated
          ? _RoutelyShell(
              key: ValueKey(widget.store.sessionRevision),
              store: widget.store,
              tracking: tracking,
              recognition: recognition,
            )
          : SetupScreen(store: widget.store),
    ),
  );
}

class _RoutelyShell extends StatefulWidget {
  const _RoutelyShell({
    super.key,
    required this.store,
    required this.tracking,
    required this.recognition,
  });
  final AppStore store;
  final TrackingRuntime tracking;
  final RideRecognitionRuntime recognition;
  @override
  State<_RoutelyShell> createState() => _RoutelyShellState();
}

class _RoutelyShellState extends State<_RoutelyShell> {
  int tab = 0, unread = 0;
  bool submissionPending = false, selectingRide = false;
  RecognitionSelection? selectedRide;
  StreamSubscription<int>? openSubscription;
  StreamSubscription<void>? recognitionOpenSubscription;
  @override
  void initState() {
    super.initState();
    openSubscription = widget.tracking.openStatuses.listen((id) {
      if (mounted && !submissionPending) _status(id);
    });
    recognitionOpenSubscription = widget.recognition.openRequests.listen((_) {
      if (mounted && !submissionPending) setState(() => tab = 1);
    });
  }

  @override
  void dispose() {
    unawaited(openSubscription?.cancel());
    unawaited(recognitionOpenSubscription?.cancel());
    super.dispose();
  }

  void _error(String message) {
    if (mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(message)));
    }
  }

  void _push(Widget screen) {
    if (mounted && !submissionPending) {
      unawaited(
        Navigator.of(
          context,
        ).push<void>(MaterialPageRoute(builder: (_) => screen)),
      );
    }
  }

  void _status(int id) {
    final session = widget.store.sessionRevision;
    late final MaterialPageRoute<void> route;
    route = MaterialPageRoute<void>(
      builder: (_) => StatusDetailScreen(
        store: widget.store,
        statusId: id,
        tracking: widget.tracking.snapshot,
        onTrack: (status, stops) async {
          await widget.recognition.stop(clearPreference: false);
          await widget.tracking.start(status, stops);
        },
        onStop: () =>
            widget.tracking.stop(statusId: id, sessionRevision: session),
        onDeleted: () {
          if (!mounted ||
              widget.store.sessionRevision != session ||
              !route.isActive) {
            return;
          }
          final navigator = route.navigator;
          if (navigator == null) return;
          // A native intent may have opened another detail above this one while
          // its DELETE was pending. Retire the deleted route, never the newer one.
          if (route.isCurrent) {
            navigator.pop();
          } else {
            navigator.removeRoute(route);
          }
        },
        onUserTap: _user,
      ),
    );
    if (mounted && !submissionPending) {
      unawaited(Navigator.of(context).push<void>(route));
    }
  }

  void _user(String username) => _push(
    UserProfileScreen(
      store: widget.store,
      username: username,
      onStatusTap: _status,
    ),
  );
  void _notificationLink(String value) {
    final uri = Uri.tryParse(value),
        server = Uri.tryParse(widget.store.session?.serverUrl ?? '');
    if (uri == null ||
        uri.userInfo.isNotEmpty ||
        ((uri.hasScheme || uri.hasAuthority) &&
            (uri.scheme != 'https' ||
                uri.host != server?.host ||
                uri.port != server?.port))) {
      _error(
        'Dieser Meldungslink kann nicht innerhalb der App geöffnet werden.',
      );
      return;
    }
    var parts = uri.pathSegments;
    final prefix =
        server?.pathSegments.where((part) => part.isNotEmpty).toList() ??
        const <String>[];
    if (prefix.isNotEmpty &&
        parts.length > prefix.length &&
        List.generate(
          prefix.length,
          (index) => parts[index] == prefix[index],
        ).every((matches) => matches)) {
      parts = parts.skip(prefix.length).toList();
    }
    bool validUsername(String name) =>
        RegExp(r'^[a-zA-Z0-9_]{1,25}$').hasMatch(name);
    if (parts.length == 1 && parts.single.startsWith('@')) {
      final username = parts.single.substring(1);
      if (validUsername(username)) {
        _user(username);
        return;
      }
    }
    if (parts.length == 2 && {'status', 'statuses'}.contains(parts.first)) {
      final id = int.tryParse(parts.last);
      if (id != null && id > 0) {
        _status(id);
        return;
      }
    }
    if (parts.length == 2 &&
        {'user', 'users', 'u'}.contains(parts.first) &&
        validUsername(parts.last)) {
      _user(parts.last);
      return;
    }
    _error('Für diesen Meldungslink ist keine App-Seite verfügbar.');
  }

  Future<void> _created(Status status) async {
    final session = widget.store.session;
    if (session == null) return;
    try {
      await widget.recognition.stop(clearPreference: false);
      final trip = status.checkin?.trip;
      if (trip != null) {
        final stops = await widget.store.api.stopovers(trip);
        widget.store.requireCurrent(session);
        await widget.tracking.start(status, stops);
      } else {
        throw StateError(
          'Die Fahrt wurde erstellt. Ohne Haltestellen kann die Begleitung noch nicht starten.',
        );
      }
    } finally {
      if (mounted && widget.store.isCurrent(session)) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _status(status.id);
        });
      }
    }
  }

  Future<void> _settingsChanged() => widget.tracking.settingsChanged();
  Future<void> _recognitionChanged(bool value) async {
    if (value) {
      await widget.recognition.start();
    } else {
      await widget.recognition.stop();
    }
  }

  Future<void> _logout() async {
    final session = widget.store.session;
    if (session == null) return;
    try {
      await widget.tracking
          .stop(sessionRevision: session.revision)
          .timeout(const Duration(seconds: 15));
    } catch (_) {
      /* Auth revocation below also invalidates the headless owner. */
    }
    if (!widget.store.isCurrent(session)) return;
    try {
      await widget.recognition.stop().timeout(const Duration(seconds: 15));
    } catch (_) {
      /* Do not let platform cleanup prevent credential removal. */
    }
    if (!widget.store.isCurrent(session)) return;
    await widget.store.logout();
  }

  Future<void> _selectRide(Json candidate) async {
    if (selectingRide || submissionPending) return;
    setState(() => selectingRide = true);
    try {
      final value = await widget.recognition.confirm(candidate);
      if (mounted) {
        setState(() {
          selectedRide = value;
          tab = 1;
        });
      }
    } catch (_) {
      _error(
        'Der Fahrtvorschlag ist nicht mehr aktuell. Bitte erneut erkennen lassen.',
      );
    } finally {
      if (mounted) setState(() => selectingRide = false);
    }
  }

  Widget _recognitionCard() => ValueListenableBuilder<Json?>(
    valueListenable: widget.recognition.snapshot,
    builder: (context, value, _) {
      if (!widget.store.settings.rideRecognitionEnabled && value == null) {
        return const SizedBox.shrink();
      }
      final candidates = value?['candidates'] is List
          ? (value!['candidates'] as List).whereType<Map>().toList()
          : <Map>[];
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.radar),
                  const SizedBox(width: 10),
                  const Expanded(
                    child: Text(
                      'Fahrterkennung',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                  ),
                  IconButton(
                    tooltip: 'Suche beenden',
                    onPressed: () => unawaited(
                      widget.recognition.stop().catchError((_) {
                        _error('Suche konnte nicht beendet werden.');
                      }),
                    ),
                    icon: const Icon(Icons.stop_circle_outlined),
                  ),
                ],
              ),
              Text(
                value?['message'] as String? ??
                    'Die Suche ist pausiert. Starte sie in den Einstellungen erneut.',
              ),
              for (final raw in candidates)
                Builder(
                  builder: (context) {
                    final candidate = Map<String, dynamic>.from(raw),
                        departure = Departure.fromJson(
                          Map<String, dynamic>.from(
                            candidate['departure'] as Map,
                          ),
                        );
                    return ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.train),
                      title: Text(
                        '${departure.lineName} · ${departure.direction ?? ''}',
                      ),
                      subtitle: Text(
                        'Als nächstes: ${candidate['nextStationName']}',
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: selectingRide
                          ? null
                          : () => _selectRide(candidate),
                    );
                  },
                ),
            ],
          ),
        ),
      );
    },
  );
  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !submissionPending && tab == 0,
    onPopInvokedWithResult: (didPop, _) {
      if (!didPop && !submissionPending && mounted && tab != 0 && tab != 1) {
        setState(() => tab = 0);
      }
    },
    child: Scaffold(
      body: Column(
        children: [
          Expanded(
            child: IndexedStack(
              index: tab,
              children: [
                FeedScreen(
                  store: widget.store,
                  onStatusTap: _status,
                  onUserTap: _user,
                  onSearchUsersTap: () => _push(
                    UserSearchScreen(store: widget.store, onUserTap: _user),
                  ),
                ),
                CheckInScreen(
                  store: widget.store,
                  isCurrentPage: tab == 1,
                  onBackAtRoot: () {
                    if (mounted && !submissionPending) setState(() => tab = 0);
                  },
                  initialStation: selectedRide?.station,
                  initialDeparture: selectedRide?.departure,
                  initialTrip: selectedRide?.trip,
                  recognitionWidget: _recognitionCard(),
                  onCreated: _created,
                  onSubmissionPendingChanged: (pending) {
                    if (mounted) setState(() => submissionPending = pending);
                  },
                ),
                NotificationsScreen(
                  store: widget.store,
                  onUnreadCountChanged: (count) {
                    if (mounted && unread != count) {
                      setState(() => unread = count);
                    }
                  },
                  onNotificationLink: _notificationLink,
                ),
                ProfileScreen(
                  store: widget.store,
                  onStatusTap: _status,
                  onSettingsTap: () => _push(
                    SettingsScreen(
                      store: widget.store,
                      onSettingsChanged: _settingsChanged,
                      onRecognitionChanged: _recognitionChanged,
                      onLogout: _logout,
                    ),
                  ),
                ),
              ],
            ),
          ),
          ValueListenableBuilder<Json?>(
            valueListenable: widget.tracking.snapshot,
            builder: (context, value, _) {
              if (value == null ||
                  value['sessionRevision'] != widget.store.sessionRevision ||
                  value['statusId'] != widget.store.activeStatusId ||
                  value['completed'] == true) {
                final active = widget.store.activeStatusId;
                if (active != null) {
                  return Material(
                    color: Theme.of(context).colorScheme.secondaryContainer,
                    child: ListTile(
                      leading: const Icon(Icons.play_circle_outline),
                      title: const Text('Aktive Fahrt fortsetzen'),
                      subtitle: const Text(
                        'Fahrt öffnen und Reisebegleitung starten',
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: submissionPending ? null : () => _status(active),
                    ),
                  );
                }
                return const SizedBox.shrink();
              }
              return Material(
                color: Theme.of(context).colorScheme.secondaryContainer,
                child: ListTile(
                  dense: true,
                  leading: const Icon(Icons.train),
                  title: Text(
                    '${value['line'] ?? 'Aktive Fahrt'} · ${value['nextStop'] ?? ''}',
                  ),
                  subtitle: Text(
                    value['timeLabel'] as String? ?? 'Reisebegleitung aktiv',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: submissionPending
                      ? null
                      : () => _status((value['statusId'] as num).toInt()),
                ),
              );
            },
          ),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (index) {
          if (!submissionPending) setState(() => tab = index);
        },
        destinations: [
          const NavigationDestination(
            icon: Icon(Icons.dynamic_feed_outlined),
            selectedIcon: Icon(Icons.dynamic_feed),
            label: 'Feed',
          ),
          const NavigationDestination(
            icon: Icon(Icons.add_circle_outline),
            selectedIcon: Icon(Icons.add_circle),
            label: 'Check-in',
          ),
          NavigationDestination(
            icon: Badge(
              isLabelVisible: unread > 0,
              label: Text(unread > 99 ? '99+' : '$unread'),
              child: const Icon(Icons.notifications_none),
            ),
            selectedIcon: const Icon(Icons.notifications),
            label: 'Meldungen',
          ),
          const NavigationDestination(
            icon: Icon(Icons.person_outline),
            selectedIcon: Icon(Icons.person),
            label: 'Profil',
          ),
        ],
      ),
    ),
  );
}
