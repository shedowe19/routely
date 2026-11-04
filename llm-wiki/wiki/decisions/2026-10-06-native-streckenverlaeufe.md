# ADR: Native Streckenverläufe für Bahn und Tram

Status: accepted
Datum: 2026-10-06
Last verified: 2026-11-04
Confidence: high

## Kontext

Der GPS-Fortschritt braucht den tatsächlichen Weg zwischen Halten. Gerade
Stationssehnen führen bei Kurven, Parallelstrecken und Schleifen zu falschen
Projektionen. Träwelling liefert für viele Fahrten eine native
GeoJSON-Polylinie (`GET api/v1/polyline/{statusId}`).

Belege: Commit `028ffe3` „Use native trip polylines for GPS tracking and update
compatible dependencies“, `9415e29` (Dokumentation der Geometrie- und
Abhängigkeitsprüfung), `data/routing/TransitRouteParser.kt`,
`service/TransitRouteTracking.kt`.

## Entscheidung

- Für Bahn und Tram wird die native Träwelling-Polylinie bevorzugt.
- OSRM wird nur für Straßenabschnitte (SEV) verwendet.
- Eine Geometrie wird nur übernommen, wenn sie eindeutig an die geordnete
  Haltfolge gebunden ist.
- Jeder aktive Abschnitt braucht einen eigenen Kurvenbeleg; eine verdichtete
  Stationssehne wird verworfen.
- Ohne bestätigte Kurve bleibt der Abschnitt unbestätigt; es wird kein
  Fortschritt geraten.

## Folgen

- Positiv: Weniger Fehlprojektionen, belastbarere Ansagen und Prognosen.
- Negativ: Mehr Parser-Komplexität, zwei Geometriepfade, zusätzliche Caches
  und umfangreiche Fixtures.
- Invarianten: 2…256 eindeutige Besuche, Start/Ziel nicht ausgefallen,
  Endpunkte ≤ 250 m, global eindeutige Zuordnung.
- Tests: `TransitRouteParserTest`, `TransitRouteRepositoryTest`,
  `NativeRailGpsTest`, `GpsRailGeometryTest`,
  `TrackingRouteGeometryRemainingPathTest`.

## Alternativen

- **Gerade Stationssehnen:** verworfen, weil sie Kurven und Parallelstrecken
  nicht abbilden.
- **Eigenes Routing für Bahn:** verworfen, weil kein öffentlicher Bahnrouter
  mit belastbaren Gleisdaten verfügbar ist und die native Quelle bereits
  fahrtspezifisch ist.
- **OSRM für alles:** verworfen, weil Autostraßen keinen Schienenweg abbilden.

## Quellen

- Commit `028ffe3`, `9415e29`
- `data/routing/TransitRouteParser.kt:34-189`
- `service/TransitRouteTracking.kt`
- `app/src/test/resources/routing/transit/README.md`
