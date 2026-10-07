# API: Externe Schnittstellen

## Zweck

Dokumentation der externen APIs, mit denen die App kommuniziert.

## Träwelling API

Die primäre externe Schnittstelle ist die Träwelling RESTful API.

- **Basis-URL**: Laufzeitwert aus `PreferencesManager`, Standard `https://traewelling.de`. Der manuelle Login erlaubt eine gültige HTTPS-Server-URL ohne eingebettete Zugangsdaten, Query oder Fragment; keine Gradle-Property setzt den API-Server.
- **Authentifizierung**: Geschützte Endpunkte erhalten einen Bearer-Token. Der erreichbare Login nimmt ihn manuell entgegen und prüft `GET /api/v1/auth/user`, bevor die Sitzung gespeichert wird. `OAuthApiService` (`POST /oauth/token`) enthält zusätzliche Austausch-/Refresh-Helfer, die noch nicht an den Login oder einen automatischen Refresh angebunden sind.
- **Provider (Transitous/HAFAS)**: Die Träwelling API greift intern auf Transit-Provider (wie HAFAS) zu, um Abfahrten und Trips zu liefern.

Besonderheiten beim Umgang mit den von Träwelling gelieferten Transit-Daten:

- **Duplikate**: `NearbyStationIdentity` fasst nur dieselbe positive interne Stations-ID zusammen; fehlt eine solche ID, kann eine nicht leere UUID dieselbe Identität belegen. Verschiedene interne IDs bleiben trotz Nähe oder gemeinsamen Namens erhalten. Namen, Koordinaten und IBNR allein sind kein austauschbarer Haltbeleg. Auch bei Paginierung können doppelte Einträge auftreten, deren jeweilige Objektidentität gesondert zu prüfen ist.
- **HafasTripId**: Wird für `getTrip` benötigt.
- **Identitäten**: Eine Station und ein Halt innerhalb einer Fahrt sind verschiedene Objekte. `StopStation.stationId` wird für Stationsanfragen und den Check-in verwendet; `StopStation.uuid` identifiziert den konkreten Halt. Das alte Stopover-Feld `id` darf nach dem 30.11.2026 nicht mehr als Station-ID interpretiert werden.
- **Stationskennungen**: IBNR und RIL100 werden aus `station.identifiers` gelesen (`de_db_ibnr`, `de_db_ril100`). Die Kennungen sind optional und können fehlen, wenn der Endpunkt die Relation nicht geladen hat.
- **Zeitdaten**: Stopovers nutzen Echtzeit mit Planzeit als Rückfall. Bei Abfahrten wird die Verspätung aus `when - plannedWhen` berechnet, nicht aus dem auslaufenden Feld `delay`.
- **Gleisangaben**: Gelieferte Gleis-/Plattformstrings bleiben Provider-Anzeigewerte. Eine führende `9`, etwa in `91`, wird nicht ohne gesonderten Herkunftsbeleg entfernt. Die aktive Begleitung wählt am Einstieg Abfahrtsgleise und später Ankunftsgleise; Ersatzbusse erhalten keine Bahnsteigangabe.

API- und OAuth-Clients begrenzen den gesamten HTTP-Call auf 60 Sekunden sowie Connect-, Read- und Write-Phasen auf jeweils 30 Sekunden. Coroutine-Abbruch wird unverändert weitergereicht; Timeout und vorübergehender Netzfehler machen eine fehlgeschlagene Authprüfung nicht zu einem Logout.

## Träwelling-Streckenverlauf für Bahn und Tram

Der native Vertrag `GET /api/v1/polyline/{statusId}` liefert den zum Check-in gehörenden Linienzug als `data`-GeoJSON-`FeatureCollection`. Ein Feature enthält `geometry.type = LineString`, Koordinaten in der Reihenfolge Längengrad, Breitengrad und `properties.statusId`. Die Kennung ist die numerische Status-ID, weder Trip-ID noch Stopover-UUID. Der Backend-Endpunkt erlaubt mehrere Status-IDs; die aktive Begleitung benötigt nur die eigene Fahrt. Unsichtbare beziehungsweise nicht gefundene Status können mit HTTP 200 und leerer Featureliste antworten; das ist kein verfügbarer Streckenverlauf.

Der [Status-Controller](https://github.com/Traewelling/traewelling/blob/4d602796da8409017314cc771b1127d169155f02/app/Http/Controllers/API/v1/StatusController.php) begrenzt die Form auf Einstieg bis Ausstieg. Der [Location-Controller](https://github.com/Traewelling/traewelling/blob/4d602796da8409017314cc771b1127d169155f02/app/Http/Controllers/Backend/Support/LocationController.php) kann gespeicherte RouteSegments mit Stationsverbindungen mischen. Die Antwort liefert keine Abschnittsherkunft, Stopover-UUIDs oder sichere Aussage über aktuell befahrene Gleise. Deshalb heißt die Quelle `Träwelling-Streckenverlauf`; ein Linienzug ist kein garantiert amtlicher Fahrweg. Eine bloße Verbindung von Stationspunkten darf nicht als belegte Schienen-Geometrie gelten.

Die [Route](https://github.com/Traewelling/traewelling/blob/4d602796da8409017314cc771b1127d169155f02/routes/api.php) liegt unter `semiscope:read-statuses`: Der Server erlaubt einen anonymen Abruf sichtbarer Status, prüft bei angemeldetem Zugriff jedoch den passenden Scope. Die App verwendet für die eigene Fahrt ihren konfigurierten Träwelling-Server und dessen passende Sitzung. Geräteposition oder Bewegungshistorie gehören nicht zu diesem Request. Der vorhandene SEV-Straßenabruf über OSRM bleibt eine getrennte Quelle; dessen Pkw-Modell ersetzt keinen Bahn-/Tramverlauf.

`TransitRouteRepository` besitzt einen eigenen Client je Auth-Snapshot. HTTPS-Origin, gültige Server-URL und Status-ID werden vor dem Request geprüft; der Bearer bleibt am konfigurierten Server. Redirects, HTTP-Rückfall, automatische Verbindungswiederholung und HTTP-Logging sind aus. Call-/Connect-/Read-Timeouts betragen 20/10/15 Sekunden. Antwortdaten werden als striktes UTF-8 gelesen und auf vier MiB begrenzt. Parsergrenzen sind 40.000 Punkte, 256 Haltbesuche, 3.000 Kilometer Gesamtform, 500 Kilometer Abschnitt, zehn Kilometer je Kante und 250 Meter Halt-Snap. Eindeutige monotone Besuchsbindung und zusätzlicher Formbeleg werden unter [GPS-Zeiten](../module/gps-zeiten.md) beschrieben.

Gleiche vollständige Requests teilen einen Abruf; höchstens vier unterschiedliche Abrufe und acht Ergebnisse liegen im sessiongebundenen RAM-Cache. Erfolgreiche Formen gelten 15 Minuten, Softrefresh ist ab 14 Minuten möglich, Fehlversuche werden zwei Minuten zurückgehalten. Ein erfolgloser Softrefresh verlängert die alte harte Frist nicht. HTTP 401/403/406 entfernt den betreffenden Repositoryeintrag statt einen negativen oder früheren erfolgreichen Cache zu erhalten. Schließen leert den Cache und beendet Scope sowie HTTP-Calls synchron. Die Daten werden weder in Room noch im Fahrtcache-JSON gespeichert. Neue Gerätepositionen werden nicht für ein Netzresultat erfunden; lokale Übernahme verlangt unveränderte Sitzung und Besuchsbasis.

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

Der feste HTTPS-Endpunkt erlaubt weder Redirects noch HTTP-Rückfall; Call-/Connect-/Read-Timeouts liegen bei 20/10/15 Sekunden. Der Client setzt `Accept: application/json` und einen projektspezifischen User-Agent ohne Account-Interceptor oder HTTP-Logging. Request-Starts sind im Prozess auf höchstens einen je Sekunde begrenzt. Gleiche geordnete Endpunktpaare teilen laufende Abrufe; der RAM-Cache hält höchstens 64 Ergebnisse, erfolgreiche 24 Stunden und eindeutig unbrauchbare Ergebnisse 15 Minuten. I/O-/Transportfehler, HTTP 408/429 und 5xx werden regulär nur 60 Sekunden zurückgehalten. `Retry-After` akzeptiert Sekunden oder ein HTTP-Datum und setzt eine prozessweite Pause von 60 Sekunden bis 15 Minuten, auch für bereits wartende öffentliche Paare; 429 ohne Header pausiert 60 Sekunden. Die monotone Cachefrist bleibt getrennt vom tatsächlichen Quellenalter: Ein zukünftig datierter oder mehr als 24 Stunden alter Erfolg wird vor Wiederverwendung entfernt. Höchstens 16 unterschiedliche Anfragen dürfen gleichzeitig ausstehen. Das Service-Prefetch betrachtet höchstens den aktuellen Ankunftsabschnitt und dessen Folgeabschnitt, am Einstieg die ersten zwei geeigneten Abschnitte, und blockiert weder API-Polling noch Standortverarbeitung. Der Service hält höchstens acht besuchsbezogene Abschnitte. Ein bereits laufender gemeinsamer Request darf den Cache auch nach Abbruch eines einzelnen wartenden Service-Jobs füllen; er kann keine beendete Fahrt reaktivieren.

Nach der [Anbieterseite](https://routing.openstreetmap.de/about.html) betreibt FOSSGIS den OSRM-Dienst mit OpenStreetMap-Daten. Sie verlangt Attribution, einen Link zur Kartenkorrektur, identifizierbaren User-Agent, höchstens eine Anfrage pro Sekunde und begrenzte Nutzung. Die Ersatzbus-Detailansicht zeigt die Quellenangabe und einen Kartenkorrektur-Link. Die Anbieterseite erklärt außerdem, dass Routenanfragen serverseitig protokolliert werden; die öffentlichen Haltpaare und übliche Verbindungsdaten sind dem Anbieter sichtbar. TODO: Belastung und Eignung des öffentlichen Dienstes bei wachsender Installation prüfen; das Request-Limit gilt pro App-Prozess und ersetzt keine Gesamtkapazitätsplanung.

Vertrag: [offizielle OSRM-HTTP-Dokumentation](https://project-osrm.org/docs/v5.24.0/api/). Geometrien bleiben unpersistiert im RAM. Keine neue Preference, Retrofit-Route, Datenbanktabelle oder Android-Bibliothek. Unpassende oder mehrdeutige Wege verwenden den bestehenden GPS-/API-/Plan-Rückfall; die Stationsengine bleibt unverändert.

## Verwandte Seiten

- [API Überblick](./ueberblick.md)
- [Interne Schnittstellen](./interne-schnittstellen.md)
- [Träwelling-API-Kompatibilität](./traewelling-kompatibilitaet.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
