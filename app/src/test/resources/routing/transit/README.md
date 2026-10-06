# Öffentliche Träwelling-Streckenfixtures

Die originalen GeoJSON-Antworten wurden am 06.10.2026 anonym per GET abgerufen. Die zusätzlichen `*-stops.json` enthalten ausschließlich die geordneten öffentlichen Halte innerhalb des Check-ins: Halt-UUID, Stationsidentität, öffentliche Stationskoordinaten, Planzeiten und Ausfallmarker. Die Einstiegs-/Ziel-UUIDs wurden beim Abruf gegen den Status geprüft; dessen Nutzerprofil wurde nicht gespeichert. Tokens, Gerätepositionen und Bewegungshistorien sind nicht enthalten.

| Fixture | Quelle | Öffentliche Haltliste | Abruf (UTC) | Inhalt |
| --- | --- | --- | --- | --- |
| `public-ice-929` | `https://traewelling.de/api/v1/polyline/9258278` | `https://traewelling.de/api/v1/stopovers/8975982` | 19:30:17 | ICE 929 Frankfurt(Main)Hbf–Nürnberg Hbf, 3.236 Punkte, fünf Check-in-Halte, ungefähr 226 km |
| `public-tram-7` | `https://traewelling.de/api/v1/polyline/9258322` | `https://traewelling.de/api/v1/stopovers/8978390` | 19:30:40 | Tram 7 Weixdorf–Dresden Bischofsplatz, 299 Punkte, 18 Check-in-Halte, ungefähr 9,4 km |

Die Testdaten sind eingefrorene Antworten, keine Garantie für die aktuelle Erreichbarkeit oder den aktuellen offiziellen Gleisweg. Träwelling kann echte Teilgeometrie und Stationssehnen mischen; der Parser akzeptiert nur eindeutig gebundene Teilabschnitte mit eigenem Kurvenbeleg. Die Tests senden keine Netzwerkanfragen.
