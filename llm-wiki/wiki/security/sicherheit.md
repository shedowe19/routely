# Sicherheit und Datenschutz

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite sammelt die sicherheitsrelevanten Entscheidungen, die im Code
tatsächlich durchgesetzt werden. Sie ist keine allgemeine Bedrohungsanalyse.

## Authentifizierung

- Ausschließlich HTTPS. `ApiServerUrl.normalize` lehnt HTTP, Zugangsdaten in
  der URL, Query und Fragment ab.
- Bearer-Token pro Anfrage; keine Cookies.
- Login validiert den Token über `auth/user`, bevor irgendetwas gespeichert
  wird.
- 401/403 löscht nur die Session mit passender Revision. Eine späte Antwort
  eines alten Kontos kann keine neue Session löschen.
- Logout löscht lokal zuerst; der Serveraufruf ist best-effort.

## Token-Speicherung

- `access_token`, `refresh_token`, `client_id` und `client_secret` liegen im
  app-privaten DataStore `traewelling_prefs`.
- Kein `EncryptedSharedPreferences`, kein Android Keystore.
- Cloud-Backup und Gerätetransfer schließen `datastore/` und die Datenbank-
  Domain vollständig aus (`backup_rules.xml`, `data_extraction_rules.xml`).
- `AuthSession.toString` redigiert Zugangsdaten; ein Test verhindert
  versehentliche Ausgabe.

**Risiko:** Bei Root, Debug-Backup oder einem kompromittierten Gerät sind die
Tokens im Klartext lesbar. Eine Keystore-basierte Verschlüsselung ist nicht
implementiert.

## Netzwerk und Datensparsamkeit

| Ziel | Gesendet | Nicht gesendet |
|---|---|---|
| Träwelling | Token, API-Anfrage | — |
| DBF/IRIS | EVA, Zugnummer, Zeitfenster | Token, Konto, Geräteposition |
| OSRM | öffentliche Haltestellenkoordinaten | Token, Geräteposition |
| bahnhof.de | Stations-Slug | Token, Geräteposition |

- Debug-HTTP-Logging redigiert `min_lat`, `max_lat`, `min_lon`, `max_lon`,
  `latitude`, `longitude` (`HttpLogSanitizer`).
- Nicht-Träwelling-Clients deaktivieren Redirects und Connection-Retry.
- Antwortgrößen, Ursprünge, Content-Types und UTF-8 werden geprüft, bevor
  geparst wird.

## Umgang mit fremden Daten

- DBF-JSON, OSRM-GeoJSON, bahnhof.de-HTML und Träwelling-Antworten sind
  unvertrauenswürdige Eingaben.
- Parser validieren Größe, Typ, Struktur und Wertebereiche; mehrdeutige Daten
  werden verworfen.
- Der SEV-Parser führt keine Skripte aus; er liest eingebettete JSON-Daten.
- Koordinaten aus bahnhof.de werden nur nach Distanz-, Identitäts- und
  Zeitfensterprüfung übernommen.

## Schreibsicherheit

- Ein HTTP-Commit wird nie automatisch wiederholt.
- `CheckInAcceptedException` und `StatusWriteAcceptedException` trennen
  „serverseitig angenommen“ von „fehlgeschlagen“.
- Ein lokaler Speicherfehler vor dem Absenden verhindert den Aufruf; danach
  macht er ihn nicht rückgängig.

## Secrets im Repository

- Es sind keine API-Keys, Passwörter oder privaten Schlüssel im Quellcode
  hinterlegt.
- CI verwendet ausschließlich GitHub-Secrets für Signatur und Wiki-Sync; im
  Wiki werden nur die Secret-Namen genannt, nie Werte.
- `.gitignore` schließt `.env`, `.env.*`, `*.jks` und `*.keystore` aus.
- Der Release-Guard liest `GITHUB_TOKEN` nur aus der Umgebung und gibt es nicht
  in Fehlern aus.

## Datenschutz gegenüber Nutzern

- Es gibt keine Analytics-, Crash- oder Werbe-SDKs im Build.
- Feed-Inhalte werden lokal gespeichert; ein Logout löscht sie nicht.
- Gerätepositionen bleiben im Speicher und werden nicht persistiert.
- Die App erhebt keine Kontaktdaten und keine Werbe-IDs.

## Offene Punkte

- Feed-Cache-Löschung bei Logout/Tokenwechsel ist nicht implementiert.
- OAuth-Code und Deep-Link-Filter sind vorhanden, aber ungenutzt; das
  vergrößert die Angriffsfläche ohne Nutzen.
- `targetSdk 34` bei `compileSdk 36` kann zukünftige Plattform-Schutzmaßnahmen
  verzögern.

## Verwandte Seiten

- [Auth und Sitzung](../modules/auth-und-sitzung.md)
- [Lokale Speicherung](../data/lokale-speicherung.md)
- [Externe Abhängigkeiten](../architecture/externe-abhaengigkeiten.md)
- [Index](../index.md)
