# Externe Abhängigkeiten

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Routely spricht mit einem Träwelling-Server und drei öffentlichen
Datendiensten. Diese Seite sammelt Zweck, Vertrag und Ausfallverhalten, damit
Änderungen an einem Client nicht blind erfolgen.

## Übersicht

| Dienst | Zweck | Authentifizierung | Client |
|---|---|---|---|
| Träwelling-REST-API | Login, Feed, Check-in, Status, Profile, Stopovers | Bearer-Token des Nutzers | `RetrofitClient` |
| Träwelling-Polyline | native Streckengeometrie einer Fahrt | Bearer-Token | `TransitRouteRepository` |
| DBF/IRIS v3 | anonyme Bahn-Echtzeit (Zeit, Gleis, Ausfall) | keine | `DbfRealtimeRepository` |
| OSRM (FOSSGIS) | Straßenrouten für SEV-Abschnitte | keine | `RoadRouteRepository` |
| bahnhof.de | SEV-Ersatzhaltestellen aus öffentlichen Bahnhofskarten | keine | `BahnhofSevRepository` |

Die Basis-URL des Träwelling-Servers ist eine Nutzereinstellung
(`server_url`), Standard ist die öffentliche Träwelling-Instanz. Jede
HTTPS-Instanz mit optionalem Unterpfad wird akzeptiert; HTTP, Zugangsdaten in
der URL, Query und Fragment werden abgelehnt (`data/api/ApiServerUrl.kt`).

## Verträge und Grenzen

### Träwelling-REST-API

- Retrofit-Interface `TraewellingApiService`; Gson-DTOs in `data/model/`.
- Der User-Agent enthält App-Name und `BuildConfig.VERSION_NAME`.
- Timeouts: 60 s pro Aufruf, 30 s connect/read/write.
- Fehler werden nicht einheitlich typisiert: teils `HttpException`, teils
  `IllegalStateException` mit Statuscode im Text.

### Träwelling-Polyline

- `GET {server}/api/v1/polyline/{statusId}` mit Bearer-Token.
- Nur ein `FeatureCollection`-Feature, dessen `properties.statusId` zur Anfrage
  passt, wird akzeptiert.
- 2…256 eindeutige Besuchsschlüssel; Start und Ziel dürfen nicht ausgefallen
  sein; Endpunkte müssen innerhalb von 250 m einrasten.
- Antwort wird pro Session gecacht; harte Grenze 15 min, sanfte Aktualisierung
  nach 14 min. Fremde Origin, 401/403/406 und unparsbare Bodies werden nicht
  negativ gecacht.

### DBF/IRIS v3

- `GET https://dbf.finalrewind.org/{eva}.json?version=3&no_related=1&past=1`.
- Kein Token, kein Bezug zum Nutzerkonto. Die Funktion ist über
  `public_rail_realtime_enabled` abschaltbar (Standard: an).
- Nur Bahn-Kategorien (`nationalExpress`, `national`, `regionalExp`,
  `regional`, `suburban`), nicht Bus, und nur mit positiver Zugnummer.
- Das Schema enthält keine Stations-EVA, kein Datum, keine Trip-ID und keinen
  Provider-Zeitstempel. Deshalb wird jede Änderung nur feldweise und mit dem
  eigenen Lesealter übernommen.
- Grenzen: 2 MiB Body, 6 s Call, 8 s Gesamtdeadline, 10 Abrufe/Minute global,
  1 Abruf/Station/Minute.
- `Retry-After` pausiert alle Stationen (mindestens 60 s). Ein HTTP-`Date`/`Age`
  älter als 180 s oder mehr als 60 s in der Zukunft wird verworfen.

### OSRM

- `GET https://routing.openstreetmap.de/routed-car/route/v1/driving/...` mit
  GeoJSON, vollständiger Übersicht und bis zu zwei Alternativen.
- Nur öffentliche Haltestellenkoordinaten; keine Geräteposition wird gesendet.
- Grenzen: 100 m…50 km Luftlinie, 5.000 Punkte, 2 MiB, 150 m Snap,
  ±10 %/150 m Abweichung zwischen deklarierter und geometrischer Länge.
- Ein globales Limit erzwingt mindestens 1 s zwischen Starts. Erfolg wird 24 h
  gecacht, transienter Fehler 60 s, deterministischer Fehler 15 min.

### bahnhof.de

- `GET https://www.bahnhof.de/{slug}/karte`, HTML mit eingebetteten
  Next.js-Daten; bis zu vier Same-Host-Redirects.
- Nur Ersatzbusse: Kategorie/Modus „bus“ und Linienname `Bus RE/RB …`.
- Grenzen: 8 MiB HTML, Anker ≤ 1.500 m, Punkte ≤ 3.000 m vom Bahnhof,
  Kartendatum ≤ 24 h, höchstens 64 Slugs, 3 parallel, 45 s Batch-Deadline.

## Ausfallverhalten

| Fall | Verhalten |
|---|---|
| Träwelling 401/403 | Session wird nur bei passender Revision gelöscht; sonst bleibt sie erhalten. |
| Träwelling 5xx/Timeout | Lesen fällt für Seite 1 auf Room zurück; Schreiben wird als Fehler gemeldet, nie automatisch wiederholt. |
| DBF nicht erreichbar | Haltestellen behalten API-/Fahrplanzeiten; kein erfundenes Echtzeitfeld. |
| DBF deaktiviert | Kein Abruf, kein Cache-Schreibvorgang, keine Anreicherung. |
| OSRM nicht erreichbar | SEV-Abschnitt bleibt ohne Straßenkurve; GPS darf ihn nicht als bestätigt nutzen. |
| bahnhof.de geändert | Parser liefert keine Koordinaten; Grund wird deutsch ausgegeben. |
| Polyline unbrauchbar | Nur bestätigte Teilstücke werden genutzt; Stationssehnen werden verworfen. |

## Datenschutz und Netzzugriffe

- DBF, OSRM und bahnhof.de erhalten keine Tokens und keine Gerätepositionen.
- Der Debug-HTTP-Logger redigiert Koordinaten-Query-Parameter
  (`data/api/HttpLogSanitizer.kt`).
- Alle Nicht-Träwelling-Clients deaktivieren Redirects und Connection-Retry;
  die SEV-Quelle folgt nur ihrem eigenen begrenzten Redirect-Loop.
- Details: [Sicherheit](../security/sicherheit.md).

## Verwandte Seiten

- [Live-Datenquellen](../api/live-datenquellen.md)
- [Träwelling-API](../api/traewelling-api.md)
- [Datenfluss](datenfluss.md)
- [Index](../index.md)
