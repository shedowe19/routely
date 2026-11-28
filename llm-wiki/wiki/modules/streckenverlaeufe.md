# Streckenverläufe

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Für GPS-Fortschritt braucht die App den tatsächlichen Weg zwischen Halten,
nicht nur gerade Linien. Routely nutzt dafür zwei Quellen: native
Träwelling-Polylinien und OSRM-Straßenrouten.

## Auswahl

| Verkehrsmittel | Bevorzugte Quelle | Fallback |
|---|---|---|
| Bahn, Tram | native Träwelling-Polylinie | keine Straßenroute |
| SEV-Bus | OSRM-Straßenroute | keine Kurve, Abschnitt bleibt unbestätigt |

Die Auswahl liegt in `service/RoadRouteSelection.kt` und
`service/TransitRouteTracking.kt`.

## Native Polylinie

- `GET {server}/api/v1/polyline/{statusId}` mit Bearer-Token.
- Ein Feature, dessen `properties.statusId` exakt passt.
- 2…256 eindeutige Besuche; Start/Ziel nicht ausgefallen.
- Endpunkte ≤ 250 m vom Halt.
- Jeder aktive Abschnitt braucht einen eigenen Kurvenbeleg. Eine verdichtete
  Stationssehne wird verworfen.
- Besuche werden global eindeutig und geordnet gebunden. Mehrdeutigkeit
  (Schleifen, Kreuzungen, fast gleiche Kandidaten) ergibt `null`.
- Cache pro Session: 8 Einträge, 15 min hart, 14 min sanfte Aktualisierung,
  2 min Fehler.

## OSRM

- Nur öffentliche Haltestellenkoordinaten.
- `driving`, GeoJSON, vollständige Übersicht, bis zu zwei Alternativen.
- Grenzen: 100 m…50 km Luftlinie, 5.000 Punkte, 2 MiB, 150 m Snap,
  ±10 %/150 m zwischen deklarierter und geometrischer Länge.
- Bis zu drei Alternativen werden verglichen; eine Straßenroute muss
  konsistent sein, nicht nur die erste Antwort.
- Global mindestens 1 s zwischen Starts; Erfolg 24 h, transient 60 s,
  deterministisch 15 min.

## Bindung an den Fortschritt

- Eine Geometrie wird nur übernommen, wenn sie zur geordneten Haltfolge und
  zum aktuellen Cursor passt.
- Eine verspätete Antwort für eine alte Generation wird verworfen.
- Der Service lädt Geometrie außerhalb des Zustands-Mutex und übernimmt sie
  nur unter Sperre.
- Ohne bestätigte Kurve bleibt der Abschnitt „unbestätigt“; die App rät keinen
  Fortschritt.

## Tests

`TransitRouteParserTest`, `TransitRouteRepositoryTest`,
`TrackingRouteGeometryRemainingPathTest`, `GpsRailGeometryTest`,
`GpsRoadRouteValidationTest`, `GpsRoadConsensusTest`,
`NativeRailGpsTest`, `RoadRouteParserTest`, `RoadRouteRepositoryTest`.

## Risiken

- Beide Quellen sind extern und unversioniert.
- OSRM liefert Autostraßen, keinen Buslinienweg. Die Route ist nur ein
  geometrischer Kandidat.
- Die native Polyline kann echte Teilgeometrie und Stationssehnen mischen; der
  Parser akzeptiert nur eindeutig gebundene Teilstücke.

## Verwandte Seiten

- [Live-Datenquellen](../api/live-datenquellen.md)
- [GPS-Zeiten](gps-zeiten.md)
- [SEV-Haltestellen](sev-haltestellen.md)
- [Index](../index.md)
