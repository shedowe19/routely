# Authentifizierung und Sitzung

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite beschreibt Login, Sessionmodell, Kontowechsel und die derzeit
unerreichbare OAuth-Implementierung.

## Login

Der produktive Weg ist ein manuell eingefügter API-Token:

1. Nutzer trägt Server-URL und Token ein.
2. `AuthRepository.loginWithToken` ruft `GET api/v1/auth/user` auf.
3. Erst nach erfolgreicher Validierung werden Server, Token und Benutzername
   gespeichert.
4. Ein Fehler oder Abbruch ersetzt keine bestehende Session.

Die Server-URL wird über `ApiServerUrl.normalize` geprüft: HTTPS, keine
Zugangsdaten, keine Query, kein Fragment, optionaler Unterpfad erlaubt.

## Sessionmodell

`AuthSession(serverUrl, accessToken?, revision)` ist eine unveränderliche
Momentaufnahme. Die Revision ist eine UUID und wird bei Login, Tokenwechsel und
Logout neu erzeugt.

Folgen:

- Jede Anfrage prüft die Session vor dem Start und nach der Antwort.
- Eine späte Antwort eines alten Kontos wird mit `CancellationException`
  verworfen, nicht als Fehler angezeigt.
- Feature-ViewModels werden bei Revisionswechsel geleert
  (`SessionViewModelStore`).
- Schreibzugriffe auf aktive Fahrt und Tracking sind status- und
  sessiongebunden.

## Validierung und Ablauf

- Beim Start wird die gespeicherte Session gegen `auth/user` geprüft.
- 401/403 löscht nur die passende Session; transiente Fehler behalten sie.
- Logout löscht lokal zuerst und ruft danach best-effort den Server auf.
- Es gibt keinen erreichbaren stillen Token-Refresh: Der Token-Login speichert
  weder `refresh_token` noch `client_id`, und `refreshAccessToken()` hat keinen
  Produktivaufrufer. Jeder 401 beendet daher die Session bei der nächsten
  Prüfung.

## OAuth: vorhanden, aber ungenutzt

- Manifest registriert `traewelling://oauth-callback`
  (`AndroidManifest.xml:44-52`).
- `OAuthHelper` erzeugt PKCE und State.
- `AuthRepository.exchangeCodeForToken` und `refreshAccessToken` existieren.
- `MainActivity.receiveNavigationIntent` liest nur Extras, nie `intent.data`.
- Kein Screen startet einen Browser-OAuth-Flow.

Damit ist der Filter wirkungslos. Siehe
[Offene Fragen](../questions/offene-fragen.md).

## Speicherung

- Ein DataStore-File `traewelling_prefs` enthält Session und Einstellungen.
- Relevante Schlüsselnamen: `server_url`, `access_token`, `refresh_token`,
  `client_id`, `client_secret`, `username`, `active_status_id`,
  `auth_session_revision`, `trip_tracking_state`.
- Kein `EncryptedSharedPreferences`, kein Keystore. Der Schutz ist der
  app-private Speicher plus Ausschluss von Cloud-Backup und Gerätetransfer.
- `AuthSession.toString` redigiert Zugangsdaten; ein Test sichert das ab.

## Tests

`AuthRepositoryTest`, `AuthSessionTest`, `SessionViewModelStoreTest`,
`PreferencesManagerStorageTest`, `ApiServerUrlTest`.

Abgedeckt: Validierung vor dem Speichern, späte Antworten, Logout-Reihenfolge,
Revisionen, aktive Fahrt, redigierte Diagnose. Nicht abgedeckt: ein echter
OAuth-Flow, weil er nicht existiert.

## Risiken

- Ein Klartext-Token im DataStore ist bei Root oder Debug-Backup lesbar.
- Ohne Refresh endet eine Sitzung bei Token-Ablauf hart.
- Der eingegebene Token liegt bis zum erfolgreichen Login in
  `AuthUiState.accessToken`; er wird nicht in `SavedStateHandle` persistiert,
  geht also bei Rotation verloren.
- Der ungenutzte OAuth-Code erhöht die Angriffsfläche und suggeriert eine
  Funktion, die es nicht gibt.

## Verwandte Seiten

- [Storage-Recovery](storage-recovery.md)
- [Sicherheit](../security/sicherheit.md)
- [Einstellungen](einstellungen.md)
- [Index](../index.md)
