# Settings Modul

## Zweck

Dieses Modul verwaltet die globalen Anwendungseinstellungen wie das UI-Theme (Light, Dark, AMOLED) und die Konfiguration der Sprachausgabe (TTS). Es bietet eine zentrale Anlaufstelle für den Benutzer, um die App an seine Präferenzen anzupassen.

## Kontext

Der SettingsScreen wird über den `ProfileScreen` aufgerufen. Die hier getroffenen Einstellungen wirken sich global auf die App aus (z.B. durch reaktives Theming auf Root-Ebene in der `MainActivity`).

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/SettingsScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/SettingsViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt`

## Verhalten

Das `SettingsViewModel` liest und schreibt Präferenzen asynchron mittels des `PreferencesManager` (welcher Android DataStore verwendet). Änderungen, wie z.B. das App-Theme, werden als StateFlow bereitgestellt, wodurch die UI (wie das `TraewellingTheme`) automatisch auf Änderungen reagiert und sich neu zeichnet.

### UI-Darstellung

- Nutzt `TraewellingTopAppBar` für konsistente Routely-Navigation
- Theme-Auswahl wird als direkte `FilterChip`-Auswahl (`Hell`, `Dunkel`, `AMOLED`) dargestellt
- Einstellungsbereiche werden als abgerundete Cards mit Icon, Beschreibung und dezentem Border angezeigt
- TTS-Optionen bleiben als Dropdowns verfügbar, sobald Haltestellenansagen aktiviert sind

## Abhängigkeiten

- `PreferencesManager` (DataStore)
- `android.speech.tts.TextToSpeech`
- `TraewellingTheme` (für das reaktive Styling)

## Verwandte Seiten

- [Theme Konfiguration](../ui/theme.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [ADR Dark Mode & Settings](../entscheidungen/2026-04-29-dark-mode-und-settings.md)

## Offene Fragen

- Fehlerbehandlung in PreferencesManager — offen — @dev
- Integration von App-spezifischen Spracheinstellungen — offen — @dev
