# Öffentliche OSRM-Testantworten

Die unveränderten JSON-Antworten wurden am 2026-10-06 mit anonymen GET-Anfragen
vom [FOSSGIS-Routingdienst](https://routing.openstreetmap.de/about.html) geladen.
Die Eingaben sind öffentliche Haltestellenkoordinaten von Duisburg, Mülheim und
Essen. Es wurden keine Gerätepositionen, Kontodaten oder Zugangsdaten übertragen.
Die beiden Anfragen wurden mit mindestens einer Sekunde Abstand gestartet.

| Datei | Öffentliche Ausgangs- und Zielkoordinaten (Breite, Länge) |
| --- | --- |
| `muelheim-essen-osrm.json` | `(51.43175246, 6.88538831)` → `(51.45018831, 7.0101172)` |
| `duisburg-muelheim-osrm.json` | `(51.42804102, 6.77808449)` → `(51.43175246, 6.88538831)` |

Quellendpunkte verwenden OSRM `driving`, vollständige GeoJSON-Geometrie und bis
zu zwei Alternativen zusätzlich zur Hauptroute:

- [Mülheim → Essen](https://routing.openstreetmap.de/routed-car/route/v1/driving/6.88538831,51.43175246;7.0101172,51.45018831?geometries=geojson&overview=full&steps=false&alternatives=2)
- [Duisburg → Mülheim](https://routing.openstreetmap.de/routed-car/route/v1/driving/6.77808449,51.42804102;6.88538831,51.43175246?geometries=geojson&overview=full&steps=false&alternatives=2)

Beide gespeicherten Antworten enthalten jeweils zwei Straßenrouten. Sie dienen
Parser- und Geometrieregressionen ohne Netzwerkzugriff im Test. Die Routen sind
Kandidaten des öffentlichen Autorouters; sie belegen keinen offiziellen
SEV-Linienweg und ihre OSRM-Fahrzeiten sind keine Busprognosen.

Routing: [FOSSGIS e.V.](https://www.fossgis.de/),
[OSRM](https://project-osrm.org/). Kartendaten ©
[OpenStreetMap-Mitwirkende](https://www.openstreetmap.org/copyright),
verfügbar unter der [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/1-0/).
[Kartendaten korrigieren](https://www.openstreetmap.org/fixthemap).
