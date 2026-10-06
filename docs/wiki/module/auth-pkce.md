# Modul: Auth - OAuth2 PKCE Flow

## Zweck

Detaillierte Dokumentation des OAuth2-Authentifizierungsablaufs mit PKCE (Proof Key for Code Exchange).

## Kontext

PKCE- und OAuth-Hilfsfunktionen sind vorhanden. Der erreichbare `SetupScreen` nutzt ausschließlich die manuelle Token-Eingabe. Es gibt derzeit keinen angebundenen Browser-Login oder Code-Austausch; der deklarierte Callback allein bildet keinen vollständigen Ablauf.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/util/OAuthHelper.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/AuthViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/data/repository/AuthRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/api/TraewellingApiService.kt` (enthält `OAuthApiService`)
- `app/src/main/AndroidManifest.xml`

## Vorhandene Hilfsfunktionen

- `OAuthHelper.generateCodeVerifier()` erzeugt 64 zufällige Bytes und kodiert sie Base64-URL ohne Padding.
- `generateCodeChallenge()` berechnet SHA-256 und kodiert das Ergebnis als Base64-URL.
- `buildAuthorizationUrl()` setzt an `/oauth/authorize` unter anderem `code_challenge`, `code_challenge_method=S256` und `state`. Callback-Code und State können extrahiert werden.
- `AuthRepository.exchangeCodeForToken()` sendet `grant_type=authorization_code`, Client-Konfiguration, Redirect-URI, `code` und optional `code_verifier` an `POST /oauth/token`. `code_challenge` gehört zur Autorisierungs-URL und wird hier nicht gesendet.
- `refreshAccessToken()` sendet `grant_type=refresh_token`, Client-Konfiguration und gespeichertes Refresh-Token an denselben Endpunkt. Nur 400/401/403 löschen die weiterhin passende Sitzung; vorübergehende Fehler erhalten sie. Leere Tokenantworten werden abgelehnt, späte Antworten nach Sitzungswechsel nicht gespeichert. Der Aufruf erfolgt derzeit nicht automatisch aus ViewModel oder Interceptor.

Die Manifest-URI `traewelling://oauth-callback` ist registriert. Eine passende Verarbeitung des Callback-Codes, des PKCE-Verifiers und des States ist in `MainActivity` nicht angebunden.

## PreferencesManager Keys für Auth

| Key             | Typ    | Beschreibung          |
| --------------- | ------ | --------------------- |
| `server_url`    | String | Träwelling-Server URL |
| `access_token`  | String | Bearer Token          |
| `refresh_token` | String | Refresh Token         |
| `client_id`     | String | OAuth Client ID       |
| `client_secret` | String | OAuth Client Secret   |

Diese Keys gehören zur revisionsgeschützten Session-Verwaltung unter [Auth](./auth.md). Der manuelle Login benötigt keine Client-ID und entfernt alte OAuth-Konfiguration bei einer neuen validierten Anmeldung. Ein registrierter Callback ist weiterhin kein Beleg für einen vollständigen Browser-Login.

## Offene Fragen

- TODO: Vollständigen OAuth-UI-Ablauf mit Callback-Verarbeitung, State-Prüfung und Token-Erneuerung anbinden. Der bestehende manuelle Token-Login verwendet kein PKCE.

## Verwandte Seiten

- [Auth](./auth.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Offene Fragen](../offene-fragen.md)
