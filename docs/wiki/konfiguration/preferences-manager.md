# Konfiguration: PreferencesManager

## Zweck

Zentraler Manager für alle App-Einstellungen und persistierte Daten. Nutzt Android DataStore.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt`

## Gespeicherte Werte

### Auth-Daten

| Key             | Flow-Typ        | Beschreibung                          |
| --------------- | --------------- | ------------------------------------- |
| `server_url`    | `Flow<String>`  | Server-URL (Standard: traewelling.de) |
| `access_token`  | `Flow<String?>` | OAuth Access Token                    |
| `refresh_token` | `Flow<String?>` | OAuth Refresh Token                   |
| `client_id`     | `Flow<String?>` | OAuth Client ID                       |
| `client_secret` | `Flow<String?>` | OAuth Client Secret                   |
| `username`      | `Flow<String?>` | Aktueller Nutzername                  |
| `isLoggedIn`    | `Flow<Boolean>` | Login-Status (Access-Token vorhanden) |

### Active Status

| Key                | Flow-Typ     | Beschreibung                                   |
| ------------------ | ------------ | ---------------------------------------------- |
| `active_status_id` | `Flow<Int?>` | ID der aktiven Fahrt (für TripTrackingService) |

### Stationsalarm und Trackingzustand

| Key | Zugriff / Standard | Beschreibung |
| --- | --- | --- |
| `gps_tracking_enabled` | `Flow<Boolean>`, `true` | GPS-Tracking gewünscht; ersetzt keine Standortfreigabe |
| `announcement_radius_meters` | `Flow<Int>`, `0` | `0` für Automatik; feste Werte `300`, `500`, `1000`, `2000` Meter |
| `trip_tracking_state` | `getTrackingState(): String?` | JSON-Zustand der aktiven Fahrt; intern gespeichert |
| `location_permission_requested` | `hasRequestedLocationPermission(): Boolean`, `false` | Merkt eine bereits angeforderte Standortfreigabe für den manuellen Weg zu Android-App-Berechtigungen |

`setAnnouncementRadiusMeters` und der lesende Flow setzen ungültige Radien auf Automatik (`0`) zurück. `saveTrackingState(statusId, stateJson)` schreibt nur, wenn diese Status-ID weiterhin aktiv ist. Ein abgelöster Service kann dadurch den Zustand einer neuen Fahrt nicht überschreiben.

Ein Wechsel beziehungsweise Löschen der aktiven Status-ID entfernt den Trackingzustand. `clearActiveTracking(statusId)` löscht ihn nur für die passende aktive Fahrt. Auch `clearSession()` entfernt aktive Fahrt und Trackingzustand.

Der Tracking-Cache enthält Status-ID, Check-in, Haltfolge und `TrackingProgress`. Gespeichert werden aktueller Besuch, `gpsEstablished` zur Unterscheidung von GPS- und vorläufigem Zeitcursor sowie erfolgreich eingereihte Ansageschlüssel. Positionen und Bewegungshistorie werden nicht persistiert. Der Marker einer Standortanfrage ist keine erteilte Berechtigung: Diese wird bei jedem sichtbaren Start erneut geprüft.

Ein TTS-Fehler beziehungsweise Abbruch kann den noch aktuellen Ansageschlüssel wieder freigeben; der Service speichert diesen korrigierten Fortschritt. Der für die Status-Timeline bereitgestellte `trackingLiveState` bleibt dagegen ausschließlich im Prozessspeicher und führt keinen zusätzlichen DataStore-Key ein. Das Tracking-Cacheformat bleibt Version 1.

### Reisebegleitung

| Key | Flow / Standard | Beschreibung |
| --- | --- | --- |
| `ride_recognition_enabled` | `Flow<Boolean>`, `false` | Ausdrücklicher Opt-in für die sichtbare Fahrtsuche; Kandidaten und Fixes bleiben im RAM. |
| `trip_change_alerts_enabled` | `Flow<Boolean>`, `true` | Änderungsmonitor-Hinweise zur eigenen aktiven Fahrt. |
| `trip_change_speech_enabled` | `Flow<Boolean>`, `true` | Änderungssprache; benötigt zusätzlich aktivierte Hinweise und globale TTS. |
| `live_progress_enabled` | `Flow<Boolean>`, `true` | Fortschrittsdarstellung und systemabhängige Live-Update-Anfrage. |
| `lock_screen_details_enabled` | `Flow<Boolean>`, `true` | Sichtbare Reisedetails; `false` verwendet öffentliche Ersatzanzeigen und unterdrückt Promotion. |

Die zugehörigen suspend-Getter und Setter entsprechen den Flow-Namen. Ein aktiver Check-in pausiert die Erkennung, ohne einen Kandidaten als Fahrt zu speichern. Der Trackingcache enthält zusätzlich `TripChangeMonitorState` mit letzten Ereigniswerten zur Deduplizierung. Die frische Vergleichsbasis, Verspätungsreferenzen und Gerätepositionen werden weiterhin nicht persistiert.

### TTS-Einstellungen

| Key            | Flow-Typ        | Beschreibung                      |
| -------------- | --------------- | --------------------------------- |
| `tts_enabled`  | `Flow<Boolean>` | TTS aktiviert (Standard: false)   |
| `tts_engine`   | `Flow<String?>` | TTS-Engine-Paketname              |
| `tts_language` | `Flow<String?>` | BCP47 Language Tag (z.B. "de-DE") |
| `tts_voice`    | `Flow<String?>` | Voice-Name                        |

## Konstanten

```kotlin
DEFAULT_SERVER_URL = "https://traewelling.de"
REDIRECT_URI = "traewelling://oauth-callback"
OAUTH_SCOPES = "read-statuses write-statuses read-notifications read-settings write-settings"
```

## Offline-Zugriff

Für nicht-reaktive Kontexte gibt es suspend-Funktionen:

- `getAccessToken(): String?`
- `getServerUrl(): String`
- `getUsername(): String?`
- `getTtsEnabled(): Boolean`
- `getGpsTrackingEnabled(): Boolean`
- `getAnnouncementRadiusMeters(): Int`
- `getTrackingState(): String?`
- etc.

## Offene Fragen

- Keine spezifischen aktuell.

## Verwandte Seiten

- [Auth](../module/auth.md)
- [Config-Dateien](./config-dateien.md)
- [TripTracking](../module/trip-tracking.md)
- [StatusDetail](../module/status-detail.md)
- [Settings](../module/settings.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Fahrtänderungen](../module/trip-changes.md)
- [Reisefortschritt](../module/trip-progress.md)
