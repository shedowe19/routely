# Settings Modul

## Zweck

Dieses Modul verwaltet das UI-Theme (Light, Dark, AMOLED), GPS-Stationsalarme und die Konfiguration der Sprachausgabe (TTS).

## Kontext

Der SettingsScreen wird über den `ProfileScreen` aufgerufen. Die hier getroffenen Einstellungen wirken sich global auf die App aus (z.B. durch reaktives Theming auf Root-Ebene in der `MainActivity`).

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/SettingsScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/SettingsViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt`
- `app/src/main/kotlin/de/traewelling/app/MainActivity.kt`

## Verhalten

Das `SettingsViewModel` liest und schreibt Präferenzen asynchron mittels des `PreferencesManager` (welcher Android DataStore verwendet). Änderungen, wie z.B. das App-Theme, werden als StateFlow bereitgestellt, wodurch die UI (wie das `TraewellingTheme`) automatisch auf Änderungen reagiert und sich neu zeichnet.

### UI-Darstellung

- Nutzt `TraewellingTopAppBar` für konsistente Routely-Navigation
- Theme-Auswahl wird als direkte `FilterChip`-Auswahl (`Hell`, `Dunkel`, `AMOLED`) dargestellt
- Einstellungsbereiche werden als abgerundete Cards mit Icon, Beschreibung und dezentem Border angezeigt
- TTS-Optionen bleiben als Dropdowns verfügbar, sobald Haltestellenansagen aktiviert sind

### Stationsansagen mit GPS

Der Bereich `Stationsansagen mit GPS` bietet `GPS verwenden` und die Entfernung für die Ansage. Standard ist GPS mit automatischem Radius von 300–2.000 Metern; alternativ sind 300, 500, 1.000 oder 2.000 Meter fest wählbar. `SettingsViewModel` schreibt `gps_tracking_enabled` und `announcement_radius_meters` über DataStore.

`Standort freigeben` übergibt die Freigabeaktion an `MainActivity`. Dort werden präziser Standort und aktivierte Ortungsdienste geprüft; bei ausgeschalteter Ortung wird die Android-Standorteinstellung geöffnet. Bei vorheriger dauerhafter Ablehnung öffnet der manuelle Button die App-Berechtigungen. Der GPS-Schalter ersetzt die Runtimefreigabe nicht. Ohne nutzbaren Standort wird der gekennzeichnete Fahrplan-Rückfall verwendet.

Die Sprachausgabe benötigt weiterhin die separate Option `Haltestellen ansagen`. GPS-Tracking und TTS-Aktivierung sind getrennte Einstellungen. Das genaue Trigger- und Rückfallverhalten beschreibt [TripTracking](./trip-tracking.md).

## Abhängigkeiten

- `PreferencesManager` (DataStore)
- `android.speech.tts.TextToSpeech`
- `TraewellingTheme` (für das reaktive Styling)

## Verwandte Seiten

- [Theme Konfiguration](../ui/theme.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [ADR Dark Mode & Settings](../entscheidungen/2026-04-29-dark-mode-und-settings.md)
- [TripTracking](./trip-tracking.md)

## Offene Fragen

- Fehlerbehandlung in PreferencesManager — offen — @dev
- Integration von App-spezifischen Spracheinstellungen — offen — @dev
