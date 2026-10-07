import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_tts/flutter_tts.dart';

import '../../data/app_store.dart';
import '../../platform/native_trip_bridge.dart';
import '../../shared/widgets.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({
    super.key,
    required this.store,
    required this.onSettingsChanged,
    required this.onLogout,
    this.onRecognitionChanged,
  });
  final AppStore store;
  final Future<void> Function() onSettingsChanged, onLogout;
  final Future<void> Function(bool)? onRecognitionChanged;
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  final bridge = NativeTripBridge();
  final tts = FlutterTts();
  List<String> languages = [], engines = [];
  List<Map<String, String>> voices = [];
  Map<String, dynamic> battery = {};
  bool busy = false;
  int previewEpoch = 0;
  String? speechError;
  String get revision => widget.store.sessionRevision;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_loadSpeech());
    unawaited(_loadBattery());
  }

  @override
  void dispose() {
    ++previewEpoch;
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_stopPreview());
    super.dispose();
  }

  Future<void> _stopPreview() async {
    try {
      await tts.stop().timeout(const Duration(seconds: 3));
    } catch (_) {
      // A platform speech failure must not prevent disposal or logout.
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) unawaited(_loadBattery());
  }

  Future<void> _loadBattery() async {
    try {
      final value = await bridge.batteryStatus();
      if (mounted) setState(() => battery = value);
    } catch (_) {
      if (mounted) setState(() => battery = {'supported': false});
    }
  }

  Future<void> _loadSpeech() async {
    try {
      final rawLanguages = await tts.getLanguages;
      final rawVoices = await tts.getVoices;
      final rawEngines =
          !kIsWeb && defaultTargetPlatform == TargetPlatform.android
          ? await tts.getEngines
          : null;
      if (!mounted) return;
      setState(() {
        languages = rawLanguages is List
            ? (rawLanguages.whereType<String>().toList()..sort())
            : [];
        voices = rawVoices is List
            ? rawVoices
                  .whereType<Map>()
                  .where((v) => v['name'] is String && v['locale'] is String)
                  .map(
                    (v) => {
                      'name': v['name'] as String,
                      'locale': v['locale'] as String,
                    },
                  )
                  .toList()
            : [];
        engines = rawEngines is List
            ? rawEngines.whereType<String>().toList()
            : [];
        speechError = null;
      });
    } catch (_) {
      if (mounted) {
        setState(
          () => speechError =
              'Sprachausgabe ist auf diesem Gerät nicht verfügbar.',
        );
      }
    }
  }

  void _error(String text) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
    }
  }

  Future<void> _save(String key, dynamic value) async {
    if (busy) return;
    final captured = revision;
    setState(() => busy = true);
    try {
      if (key == 'gps_tracking_enabled' && value == true && bridge.supported) {
        await bridge.requestPermissions();
      }
      if (revision != captured) return;
      if (key == 'ride_recognition_enabled' &&
          widget.onRecognitionChanged != null) {
        await widget.onRecognitionChanged!(value == true);
      } else {
        await widget.store.setSetting(key, value);
      }
      if (revision == captured) await widget.onSettingsChanged();
    } catch (_) {
      if (revision == captured) {
        _error('Die Einstellung konnte nicht übernommen werden.');
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _preview() async {
    final captured = revision, epoch = ++previewEpoch;
    bool current() =>
        mounted &&
        revision == captured &&
        previewEpoch == epoch &&
        widget.store.authenticated;
    try {
      final settings = widget.store.settings;
      if (!kIsWeb &&
          defaultTargetPlatform == TargetPlatform.android &&
          engines.contains(settings.ttsEngine)) {
        await tts.setEngine(settings.ttsEngine!);
      }
      if (!current()) return;
      if (languages.contains(settings.ttsLanguage)) {
        await tts.setLanguage(settings.ttsLanguage!);
      }
      if (!current()) return;
      final voice = voices.where(
        (v) =>
            v['name'] == settings.ttsVoice &&
            (settings.ttsLanguage == null ||
                v['locale'] == settings.ttsLanguage),
      );
      if (voice.isNotEmpty) await tts.setVoice(voice.first);
      if (!current()) return;
      await tts.setSpeechRate(settings.ttsRate);
      if (!current()) return;
      await tts.setPitch(settings.ttsPitch);
      if (!current()) return;
      await tts.speak(
        'Nächster Halt: Essen Hauptbahnhof. Bitte beim Aussteigen auf den Abstand achten.',
      );
    } catch (_) {
      if (current()) {
        _error('Die Sprachvorschau konnte nicht gestartet werden.');
      }
    }
  }

  Future<void> _selectEngine(String value) async {
    final captured = revision;
    try {
      await _save('tts_engine', value.isEmpty ? null : value);
      if (!mounted ||
          revision != captured ||
          widget.store.settings.ttsEngine != (value.isEmpty ? null : value)) {
        return;
      }
      if (value.isNotEmpty) await tts.setEngine(value);
      if (mounted && revision == captured) await _loadSpeech();
    } catch (_) {
      if (mounted && revision == captured) {
        _error('Die Sprach-Engine konnte nicht gestartet werden.');
      }
    }
  }

  Future<void> _logout() async {
    if (busy) return;
    final captured = revision;
    final confirm = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Abmelden?'),
        content: const Text(
          'Deine aktive Reisebegleitung wird beendet. Lokale Kontodaten werden entfernt.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Abbrechen'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Abmelden'),
          ),
        ],
      ),
    );
    if (confirm != true || !mounted || revision != captured) return;
    ++previewEpoch;
    setState(() => busy = true);
    try {
      await _stopPreview();
      if (!mounted || revision != captured) return;
      await widget.onLogout();
    } catch (_) {
      _error(
        'Abmelden konnte nicht abgeschlossen werden. Bitte erneut versuchen.',
      );
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.store,
    builder: (context, _) {
      final s = widget.store.settings;
      final filteredVoices = voices
          .where((v) => s.ttsLanguage == null || v['locale'] == s.ttsLanguage)
          .toList();
      return Scaffold(
        appBar: const RoutelyAppBar(title: 'Einstellungen'),
        body: ResponsiveContent(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _group('Reisebegleitung', Icons.route, [
                _toggle(
                  'Fahrten automatisch erkennen',
                  'Schlägt passende Fahrten vor. Fahrt und Ziel bestätigst du selbst.',
                  s.rideRecognitionEnabled,
                  (value) => _save('ride_recognition_enabled', value),
                ),
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    'Für die Fahrterkennung wird dein Standort an deinen Träwelling-Server gesendet, um Stationen in der Nähe zu finden. Die Suche kann jederzeit beendet werden.',
                  ),
                ),
                _toggle(
                  'Reiseänderungen melden',
                  'Gleiswechsel, entfallene Halte und größere Verspätungsänderungen.',
                  s.tripChangeAlertsEnabled,
                  (value) => _save('trip_change_alerts_enabled', value),
                ),
                _toggle(
                  'Änderungen auch ansagen',
                  'Spricht wichtige Änderungen bei aktivierten Haltestellenansagen.',
                  s.tripChangeSpeechEnabled,
                  (value) => _save('trip_change_speech_enabled', value),
                  enabled: s.tripChangeAlertsEnabled,
                ),
                _toggle(
                  'Live-Reisefortschritt',
                  'Nächster Halt auf Android und als Live Activity auf unterstützten iPhones.',
                  s.liveProgressEnabled,
                  (value) => _save('live_progress_enabled', value),
                ),
                _toggle(
                  'Reisedetails auf dem Sperrbildschirm',
                  'Linie, Ziel, nächster Halt und Gleis auf dem gesperrten Gerät anzeigen.',
                  s.lockScreenDetailsEnabled,
                  (value) => _save('lock_screen_details_enabled', value),
                ),
                if (bridge.supported)
                  OutlinedButton.icon(
                    onPressed: busy
                        ? null
                        : () async {
                            try {
                              await bridge.openNotificationSettings();
                            } catch (_) {
                              _error(
                                'Anzeigeeinstellungen konnten nicht geöffnet werden.',
                              );
                            }
                          },
                    icon: const Icon(Icons.notifications_outlined),
                    label: const Text('Anzeigeeinstellungen des Geräts'),
                  ),
              ]),
              _group('Erscheinungsbild', Icons.palette_outlined, [
                _dropdown('App-Theme', s.theme, const {
                  'LIGHT': 'Hell',
                  'DARK': 'Dunkel',
                  'AMOLED': 'AMOLED',
                  'SYSTEM': 'System',
                }, (value) => _save('app_theme', value)),
              ]),
              _group('GPS und Ansagen', Icons.near_me_outlined, [
                _toggle(
                  'Fortschritt per GPS',
                  'GPS für Haltestellen und Ankunftsprognosen verwenden. Bei fehlendem Empfang dienen API-Zeiten als Rückfall.',
                  s.gpsTrackingEnabled,
                  (value) => _save('gps_tracking_enabled', value),
                ),
                _dropdown(
                  'Ansage-Abstand',
                  '${s.announcementRadiusMeters}',
                  const {
                    '0': 'Automatisch',
                    '300': '300 m',
                    '500': '500 m',
                    '1000': '1 km',
                    '2000': '2 km',
                  },
                  (value) =>
                      _save('announcement_radius_meters', int.parse(value)),
                ),
                _toggle(
                  'Haltestellen ansagen',
                  'Nächsten Halt und das Reiseziel mit der Stimme dieses Geräts ansagen.',
                  s.ttsEnabled,
                  (value) => _save('tts_enabled', value),
                ),
                if (speechError != null)
                  Text(
                    speechError!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                if (engines.isNotEmpty)
                  _dropdown(
                    'Sprach-Engine',
                    engines.contains(s.ttsEngine) ? s.ttsEngine! : '',
                    {'': 'Systemstandard', for (final e in engines) e: e},
                    _selectEngine,
                  ),
                if (languages.isNotEmpty)
                  _dropdown(
                    'Sprache',
                    languages.contains(s.ttsLanguage) ? s.ttsLanguage! : '',
                    {
                      '': 'Systemstandard',
                      for (final language in languages) language: language,
                    },
                    (value) =>
                        _save('tts_language', value.isEmpty ? null : value),
                  ),
                if (filteredVoices.isNotEmpty)
                  _dropdown(
                    'Stimme',
                    filteredVoices.any((v) => v['name'] == s.ttsVoice)
                        ? s.ttsVoice!
                        : '',
                    {
                      '': 'Systemstandard',
                      for (final voice in filteredVoices)
                        voice['name']!: '${voice['name']} (${voice['locale']})',
                    },
                    (value) => _save('tts_voice', value.isEmpty ? null : value),
                  ),
                Text('Sprechtempo: ${(s.ttsRate * 100).round()} %'),
                Slider(
                  value: s.ttsRate,
                  divisions: 10,
                  onChanged: busy ? null : (value) => _save('tts_rate', value),
                ),
                Text('Tonhöhe: ${s.ttsPitch.toStringAsFixed(1)}'),
                Slider(
                  value: s.ttsPitch,
                  min: .5,
                  max: 2,
                  divisions: 15,
                  onChanged: busy ? null : (value) => _save('tts_pitch', value),
                ),
                OutlinedButton.icon(
                  onPressed: speechError != null || busy ? null : _preview,
                  icon: const Icon(Icons.volume_up),
                  label: const Text('Stimme testen'),
                ),
              ]),
              if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android)
                _group('Hintergrund und Akku', Icons.battery_charging_full, [
                  Text(
                    battery['exempt'] == true
                        ? 'Akku-Optimierung: Ausnahme aktiv'
                        : 'Akku-Optimierung: Ausnahme nicht bestätigt',
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Die Reisebegleitung nutzt einen sichtbaren Standortdienst. Eine Akku-Ausnahme kann die Zuverlässigkeit bei ausgeschaltetem Display verbessern. Herstellereinstellungen können zusätzlich eingreifen.',
                  ),
                  OutlinedButton(
                    onPressed: busy
                        ? null
                        : () async {
                            try {
                              await bridge.requestBatteryExemption();
                              await _loadBattery();
                            } catch (_) {
                              _error(
                                'Akku-Einstellungen konnten nicht geöffnet werden.',
                              );
                            }
                          },
                    child: const Text('Akku-Optimierung prüfen'),
                  ),
                ]),
              if (!kIsWeb && defaultTargetPlatform == TargetPlatform.iOS)
                _group('Hintergrund auf iPhone', Icons.phone_iphone, [
                  const Text(
                    'Die Reisebegleitung verwendet Apples Standort-Hintergrundmodus. Standort und Mitteilungen müssen erlaubt sein. Nach erzwungenem Beenden kann iOS die Begleitung nicht weiterführen. Live Activities unterliegen Apples Zeitgrenzen.',
                  ),
                ]),
              if (!bridge.supported)
                _group('Dieses Gerät', Icons.devices, [
                  const Text(
                    'Feeds, Check-in und Fahrtdetails sind verfügbar. Die native Hintergrundbegleitung und Sperrbildschirm-Anzeige sind für Android und iOS eingerichtet. Browser und Desktop begleiten eine Reise nur während die App aktiv ist.',
                  ),
                ]),
              _group('Konto', Icons.person_outline, [
                Text('@${widget.store.user?.username ?? ''}'),
                const SizedBox(height: 8),
                Text(widget.store.session?.serverUrl ?? ''),
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  onPressed: busy ? null : _logout,
                  icon: const Icon(Icons.logout),
                  label: const Text('Abmelden'),
                ),
              ]),
            ],
          ),
        ),
      );
    },
  );
  Widget _group(String title, IconData icon, List<Widget> children) => Padding(
    padding: const EdgeInsets.only(bottom: 18),
    child: Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: Theme.of(context).colorScheme.primary),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    title,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            ...children,
          ],
        ),
      ),
    ),
  );
  Widget _toggle(
    String title,
    String subtitle,
    bool value,
    ValueChanged<bool> onChanged, {
    bool enabled = true,
  }) => SwitchListTile(
    contentPadding: EdgeInsets.zero,
    title: Text(title),
    subtitle: Text(subtitle),
    value: value,
    onChanged: busy || !enabled ? null : onChanged,
  );
  Widget _dropdown(
    String label,
    String value,
    Map<String, String> values,
    ValueChanged<String> onChanged,
  ) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 8),
    child: DropdownButtonFormField<String>(
      initialValue: value,
      key: ValueKey('$label:$value'),
      decoration: InputDecoration(labelText: label),
      isExpanded: true,
      items: values.entries
          .map(
            (e) => DropdownMenuItem(
              value: e.key,
              child: Text(e.value, overflow: TextOverflow.ellipsis),
            ),
          )
          .toList(),
      onChanged: busy
          ? null
          : (value) {
              if (value != null) onChanged(value);
            },
    ),
  );
}
