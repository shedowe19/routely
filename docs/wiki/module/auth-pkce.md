# Modul: Auth - OAuth2 PKCE Flow

## Zweck

Detaillierte Dokumentation des OAuth2-Authentifizierungsablaufs mit PKCE (Proof Key for Code Exchange).

## Kontext

PKCE- und OAuth-Hilfsfunktionen sind vorhanden. Der erreichbare `SetupScreen` nutzt ausschließlich die manuelle Token-Eingabe. Es gibt derzeit keinen angebundenen Browser-Login oder Code-Austausch; der deklarierte Callback allein bildet keinen vollständigen Ablauf.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/util/OAuthHelper.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/AuthViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/data/repository/AuthRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/repository/OAuthRefreshError.kt`
- `app/src/main/kotlin/de/traewelling/app/data/api/TraewellingApiService.kt` (enthält `OAuthApiService`)
- `app/src/main/AndroidManifest.xml`

## Vorhandene Hilfsfunktionen

- `OAuthHelper.generateCodeVerifier()` erzeugt 64 zufällige Bytes und kodiert sie Base64-URL ohne Padding.
- `generateCodeChallenge()` berechnet SHA-256 und kodiert das Ergebnis als Base64-URL.
- `buildAuthorizationUrl()` setzt an `/oauth/authorize` unter anderem `code_challenge`, `code_challenge_method=S256` und `state`. Callback-Code und State können extrahiert werden.
- `AuthRepository.exchangeCodeForToken()` sendet `grant_type=authorization_code`, Client-Konfiguration, Redirect-URI, `code` und optional `code_verifier` an `POST /oauth/token`. `code_challenge` gehört zur Autorisierungs-URL und wird hier nicht gesendet.
- `refreshAccessToken()` sendet `grant_type=refresh_token`, Client-Konfiguration und gespeichertes Refresh-Token an denselben Endpunkt. Eine erfolgreiche Antwort ersetzt den Refresh-Token nur durch einen nicht leeren Ersatz; fehlt dieser oder ist er leer, bleibt der bisherige für die nächste Erneuerung erhalten. Ein fehlender oder leerer Access-Token wird weiterhin abgelehnt, ohne Zugangsdaten zu verändern. Der Aufruf erfolgt derzeit nicht automatisch aus ViewModel oder Interceptor.

Der Fehlerpfad unterscheidet den OAuth-Fehlercode vom HTTP-Status: Nur HTTP 400/401/403 mit ausdrücklich erkanntem `error = "invalid_grant"` darf die weiterhin exakt passende Sitzung löschen. `OAuthRefreshError` akzeptiert dafür ausschließlich ein vollständig gültiges UTF-8-JSON-Objekt bis 16 KiB mit genau einem stringförmigen `error`-Feld. Fehlendes, mehrdeutiges, ungültiges oder größeres Fehler-JSON liefert keinen Löschbeleg. `invalid_client`, `invalid_request`, `invalid_scope`, unbekannte Fehler und ein bloßes 401/403 erhalten die Sitzung; ebenso Netzwerk-, Rate-Limit- und Serverfehler. Der Fehler wird dem Aufrufer weitergegeben, nicht als erfolgreiche Erneuerung ausgegeben.

Seit der Prüfung vom 03.11.2026 werden gleichzeitig begonnene Refreshversuche für dieselben exakten Zugangsdaten prozessweit zusammengeführt, auch über mehrere Repositoryinstanzen. Nur ein Aufruf sendet den rotierenden Refresh-Token; weitere warten auf dasselbe Ergebnis. Andere Sitzungen besitzen unabhängige Aufträge. Die Zugangsdaten werden unmittelbar vor dem Netzwerkaufruf erneut mit dem Store verglichen. Ein abgebrochener wartender Aufrufer beendet den gemeinsamen Auftrag nicht; Abbruch des ausführenden Aufrufs beendet auch dessen wartende Aufrufer. Nach Abschluss oder Abbruch wird der flüchtige Eintrag entfernt, ohne Zugangsdaten zu protokollieren oder Fehler für spätere Versuche zu cachen. Die bestehenden Session-/Cancellation-Prüfungen vor Speichern und Löschen bleiben erhalten.

`AuthRepositoryTest` ergänzt kontrollierte Parallelfälle für gemeinsame Rotation und `invalid_grant`, Repositorygrenzen, unabhängig abbrechende Wartende, ausführenden Abbruch mit erneutem Versuch, temporäre Fehler und unabhängige Sitzungen. Ein produktiver Browserlogin oder ein schreibender Live-OAuth-Test wird damit nicht behauptet; der Ausführungsnachweis steht unter [Tests](../entwicklung/tests.md).

Dieser korrigierte S2-Vertrag folgt [RFC 6749, Abschnitt 5.2](https://www.rfc-editor.org/rfc/rfc6749#section-5.2) und [Abschnitt 6](https://www.rfc-editor.org/rfc/rfc6749#section-6): Clientfehler sind kein Beleg für ein ungültiges Nutzer-Grant, ein neuer Refresh-Token ist optional. Revisions-/Snapshot-Vergleiche verhindern weiterhin Änderungen oder Löschungen nach Sitzungswechsel, auch bei erneutem Login mit demselben Token. Coroutine-Abbruch wird propagiert; nach einer abgebrochenen Antwort darf weder gespeichert noch gelöscht werden. Der historische Befund steht im [Main-Review](../entwicklung/main-review-2026-10-06.md).

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

- Die zuvor offene Parallel-Refresh-Frage ist im Helfer durch die gemeinsame Ausführung korrigiert. Vor einer Browserlogin-Anbindung bleiben Callback-/State-/PKCE-Lifecycle und der tatsächliche OAuth-Clientvertrag separat umzusetzen. Siehe [Nachreview](../entwicklung/main-review-2026-10-06.md).

- TODO: Vollständigen OAuth-UI-Ablauf mit Callback-Verarbeitung, State-Prüfung und Token-Erneuerung anbinden. Der bestehende manuelle Token-Login verwendet kein PKCE.

## Verwandte Seiten

- [Auth](./auth.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Offene Fragen](../offene-fragen.md)
