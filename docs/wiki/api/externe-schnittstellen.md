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

## Verwandte Seiten

- [API Überblick](./ueberblick.md)
- [Interne Schnittstellen](./interne-schnittstellen.md)
- [Träwelling-API-Kompatibilität](./traewelling-kompatibilitaet.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
