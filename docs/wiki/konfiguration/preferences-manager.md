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
- etc.

## Offene Fragen

- Keine spezifischen aktuell.

## Verwandte Seiten

- [Auth](../module/auth.md)
- [Config-Dateien](./config-dateien.md)
