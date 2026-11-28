# Einstellungen

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Die Einstellungen bündeln Theme, Tracking, Sprachausgabe, Fahrterkennung,
Datenschutz und Storage-Status. Diese Seite listet die Schlüssel und ihr
Verhalten, ohne Werte oder Zugangsdaten zu dokumentieren.

## Screen

`SettingsScreen` erhält das `SettingsViewModel` und Callbacks für
GPS-Berechtigung, Fahrterkennung und Live-Update-Einstellungen. Das ViewModel
ist Activity-scoped, nicht session-scoped; es überlebt daher einen Logout,
während Feature-ViewModels geleert werden.

## Preference-Schlüssel

| Schlüssel | Bedeutung | Default |
|---|---|---|
| `app_theme` | `LIGHT`, `DARK`, `AMOLED` | `LIGHT` |
| `gps_tracking_enabled` | GPS im Tracking-Service | an |
| `ride_recognition_enabled` | Fahrterkennung erlauben | aus |
| `announcement_radius_meters` | Ansageradius: 0, 300, 500, 1000, 2000 | 0 |
| `tts_enabled` | Sprachansagen | aus |
| `tts_engine`, `tts_language`, `tts_voice` | TTS-Auswahl | System |
| `trip_change_alerts_enabled` | Hinweise auf Fahrtänderungen | an |
| `trip_change_speech_enabled` | Änderungen vorlesen | an |
| `live_progress_enabled` | Live-Fortschritt in Notification | an |
| `lock_screen_details_enabled` | Details auf dem Sperrbildschirm | an |
| `public_rail_realtime_enabled` | DBF/IRIS-Anreicherung | an |
| `location_permission_requested` | Merker für Berechtigungsdialog | aus |

Quelle: `util/PreferencesManager.kt:40-140`.

## Sprachausgabe

- `SettingsSpeechInitializer` lädt Engines, Sprachen und Stimmen.
- Ein fehlgeschlagener Standard-Engine führt nicht dazu, dass Alternativen
  verschwinden.
- Initialisierung hat 10 s Timeout; späte Callbacks eines ersetzten Engines
  werden ignoriert.
- "Testen" startet eine Ansage; ein Fehler wird im Settings-Zustand gemeldet.
- Die tatsächliche Warteschlange liegt im Tracking-Service
  (`SpeechDeliveryQueue`).

## Theme

- Drei Schemata: hell, dunkel, AMOLED (reines Schwarz).
- Kein Dynamic Color, kein System-Dark-Mode-Following.
- `themes.xml` ist nur ein helles Fenster-Theme. Beim Start kann deshalb ein
  heller Fensterhintergrund kurz vor dem Compose-Theme sichtbar sein.

## Storage-Status

Das ViewModel kann einen `storageError` anzeigen. Die eigentliche
Wiederherstellung läuft über den globalen Dialog; siehe
[Storage-Recovery](storage-recovery.md).

## Tests

`SettingsSpeechInitializerTest` — Engine-Wechsel, Timeout, späte Callbacks.
`PreferencesManagerStorageTest` — gemeinsame Store-Gesundheit, keine
optimistischen Schreibvorgänge. Es gibt keinen Compose-UI-Test für den Screen.

## Risiken

- Fast alle sichtbaren Texte sind im Kotlin-Code hart auf Deutsch; nur fünf
  Strings liegen in `strings.xml`. Eine Lokalisierung ist nicht vorbereitet.
- Activity-scoped Settings-Zustand kann nach einem Kontowechsel veraltet
  wirken, obwohl die Preferences selbst kontogebunden gespeichert sind.
- Der Radius gilt nur für Ansagen, nicht für die Umkreissuche beim Check-in.

## Verwandte Seiten

- [Lokale Speicherung](../data/lokale-speicherung.md)
- [UI und Design](../frontend/ui-und-design.md)
- [Design-System](../frontend/ui-und-design.md)
- [Index](../index.md)
