# API: Externe Schnittstellen

## Zweck

Dokumentation der externen APIs, mit denen die App kommuniziert.

## Träwelling API

Die primäre externe Schnittstelle ist die Träwelling RESTful API.

- **Basis-URL**: Die genaue Basis-URL wird über `buildConfigField` bzw. Retrofit-Konfiguration gesetzt. Typischerweise `https://traewelling.de/`.
- **Authentifizierung**: OAuth 2.0. Endpunkte erfordern einen Bearer-Token, der via `OAuthApiService` (`POST /oauth/token`) geholt und erneuert wird.
- **Provider (Transitous/HAFAS)**: Die Träwelling API greift intern auf Transit-Provider (wie HAFAS) zu, um Abfahrten und Trips zu liefern.

Besonderheiten beim Umgang mit den von Träwelling gelieferten Transit-Daten:

- **Duplikate**: Die APIs (via Transitous) liefern häufig doppelte Bahnhöfe oder Stationen bei Suchen. Hier muss per Koordinaten-Nähe (< 150m) und Namen dedupliziert werden. Auch bei Paginierung können doppelte Einträge auftreten (z.B. gleiche `tripId` bei Departures).
- **HafasTripId**: Wird für `getTrip` benötigt.
- **Identitäten**: Eine Station und ein Halt innerhalb einer Fahrt sind verschiedene Objekte. `StopStation.stationId` wird für Stationsanfragen und den Check-in verwendet; `StopStation.uuid` identifiziert den konkreten Halt. Das alte Stopover-Feld `id` darf nach dem 30.11.2026 nicht mehr als Station-ID interpretiert werden.
- **Stationskennungen**: IBNR und RIL100 werden aus `station.identifiers` gelesen (`de_db_ibnr`, `de_db_ril100`). Die Kennungen sind optional und können fehlen, wenn der Endpunkt die Relation nicht geladen hat.
- **Zeitdaten**: Stopovers nutzen Echtzeit mit Planzeit als Rückfall. Bei Abfahrten wird die Verspätung aus `when - plannedWhen` berechnet, nicht aus dem auslaufenden Feld `delay`.
- **Plattform-Präfixe**: Bei manchen DB-Bahnhöfen werden interne Plattform-IDs mit dem Sektor-Code `9` (z.B. `91` für Gleis 1) zurückgegeben. Diese müssen vor der Anzeige gestrippt werden.

## Öffentliche SEV-Karten von bahnhof.de

Die [SEV-Ergänzung](../module/sev-haltestellen.md) liest `GET https://www.bahnhof.de/<stations-slug>/karte` mit einem getrennten anonymen OkHttp-Client. Träwelling-Bearer-Token und Gerätepositionen werden dabei nicht übertragen. Die URL nennt den angefragten Bahnhof; der Webseitenbetreiber sieht damit den Stationsabruf und die übliche Netzwerkverbindung.

Die Antwort ist HTML mit JSON-Daten in Next.js-Datensätzen. `BahnhofSevParser` liest diese als Daten, ohne JavaScript auszuführen, und akzeptiert ausschließlich GeoJSON-Punkte mit Typ `RAIL_REPLACEMENT_TRANSPORT`. Koordinaten folgen der GeoJSON-Reihenfolge Längengrad, Breitengrad. Richtungsnamen und separate Hinweise bleiben für die Zuordnung erhalten. Bahnhofsmittelpunkt, PDF-Link oder QR-Link sind keine Ersatzhaltkoordinate.

Für diese öffentliche Quelle wird kein RIS::Stations-Zugang benötigt. HTML-Struktur und Inhalt sind kein garantierter REST-Vertrag; fehlerhafte, fehlende oder nicht eindeutig zuordenbare Daten bleiben optional und lassen die API-Route verwendbar. Die Cache-, Quellenalter-, Richtungs- und Datumsgrenzen stehen in der [Modulseite](../module/sev-haltestellen.md). Neue Retrofit-Routen, Zugangsdaten oder Android-Abhängigkeiten werden dafür nicht eingeführt.

## Öffentliche Straßen-Geometrie über FOSSGIS/OSRM

Für die optionale [SEV-Zeitprojektion](../module/gps-zeiten.md) verwendet `RoadRouteRepository` einen weiteren getrennten anonymen OkHttp-Client:

```text
GET https://routing.openstreetmap.de/routed-car/route/v1/driving/<lon1>,<lat1>;<lon2>,<lat2>?geometries=geojson&overview=full&steps=false&alternatives=2&generate_hints=false
```

Beide Koordinaten stammen ausschließlich aus aktuell eindeutig zugeordneten öffentlichen SEV-Punkten. Gerätepositionen, Bewegungshistorie, Fahrt-/Nutzerkennungen und Träwelling-Token sind keine Routingparameter. Geordnete Besuchsschlüssel binden die Antwort erst lokal an die aktive Fahrt. Das vorbereitete Pkw-Profil liefert mögliche Straßenführung, keinen offiziellen Ersatzbusweg. Der Schätzer übernimmt keine OSRM-Fahrtdauer als Bus-ETA.

`alternatives=2` fordert neben dem bevorzugten Weg bis zu zwei weitere Kandidaten an; der Dienst garantiert nicht, dass Alternativen existieren. `generate_hints=false` deaktiviert die nicht benötigten Routing-Hints. `RoadRouteParser` akzeptiert höchstens drei GeoJSON-`LineString`-Kandidaten mit insgesamt begrenzter Antwortgröße von 2 MiB und höchstens 5.000 Punkten je Weg. Er prüft erfolgreiche Antwort, genau zwei plausible Waypoints, höchstens 150 Meter Endpunktsnap, endliche Koordinaten, 100 Meter bis 50 Kilometer Weglänge sowie konsistente deklarierte/aus Geometrie berechnete Länge. Fehlende oder ungültige Antworten liefern keine Geometrie.

Der feste HTTPS-Endpunkt erlaubt weder Redirects noch HTTP-Rückfall; Call-/Connect-/Read-Timeouts liegen bei 20/10/15 Sekunden. Der Client setzt `Accept: application/json` und einen projektspezifischen User-Agent ohne Account-Interceptor oder HTTP-Logging. Request-Starts sind im Prozess auf höchstens einen je Sekunde begrenzt. Gleiche geordnete Endpunktpaare teilen laufende Abrufe; der RAM-Cache hält höchstens 64 Ergebnisse, erfolgreiche 24 Stunden und Fehlversuche 15 Minuten. Höchstens 16 unterschiedliche Anfragen dürfen gleichzeitig ausstehen. Das Service-Prefetch betrachtet höchstens den aktuellen Ankunftsabschnitt und dessen Folgeabschnitt, am Einstieg die ersten zwei geeigneten Abschnitte, und blockiert weder API-Polling noch Standortverarbeitung. Der Service hält höchstens acht besuchsbezogene Abschnitte. Ein bereits laufender gemeinsamer Request darf den Cache auch nach Abbruch eines einzelnen wartenden Service-Jobs füllen; er kann keine beendete Fahrt reaktivieren.

Nach der [Anbieterseite](https://routing.openstreetmap.de/about.html) betreibt FOSSGIS den OSRM-Dienst mit OpenStreetMap-Daten. Sie verlangt Attribution, einen Link zur Kartenkorrektur, identifizierbaren User-Agent, höchstens eine Anfrage pro Sekunde und begrenzte Nutzung. Die Ersatzbus-Detailansicht zeigt die Quellenangabe und einen Kartenkorrektur-Link. Die Anbieterseite erklärt außerdem, dass Routenanfragen serverseitig protokolliert werden; die öffentlichen Haltpaare und übliche Verbindungsdaten sind dem Anbieter sichtbar. TODO: Belastung und Eignung des öffentlichen Dienstes bei wachsender Installation prüfen; das Request-Limit gilt pro App-Prozess und ersetzt keine Gesamtkapazitätsplanung.

Vertrag: [offizielle OSRM-HTTP-Dokumentation](https://project-osrm.org/docs/v5.24.0/api/). Geometrien bleiben unpersistiert im RAM. Keine neue Preference, Retrofit-Route, Datenbanktabelle oder Android-Bibliothek. Unpassende oder mehrdeutige Wege verwenden den bestehenden GPS-/API-/Plan-Rückfall; die Stationsengine bleibt unverändert.

## Verwandte Seiten

- [API Überblick](./ueberblick.md)
- [Interne Schnittstellen](./interne-schnittstellen.md)
- [Träwelling-API-Kompatibilität](./traewelling-kompatibilitaet.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
