# Datenmodell

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite erklärt die zentralen Datentypen und die Identitätsregeln, die
verhindern, dass Halte, Status oder Konten verwechselt werden.

## Träwelling-DTOs

`data/model/Models.kt` enthält unter anderem:

| Typ | Bedeutung |
|---|---|
| `Status` | Statusmeldung inklusive `checkin` (Zug), Nutzer, Likes |
| `CheckinInfo` | Fahrt, Linie, Start/Ziel, Zeiten |
| `StopStation` | Halt mit `uuid`, Station, Plan-/Istzeiten, Ausfall |
| `TrainStation` | Station mit `id`, `ibnr`, `rilIdentifier` |
| `DepartureTrip` | Abfahrt mit Linie, Ziel, Gleis, Betreiber |
| `User` | Profil |
| `Notification` | Meldung mit Link |
| `Statistics` | Profilstatistik |

Gson mappt camelCase. `@SerializedName` steht nur dort, wo der Servername
abweicht (`lock_visibility`, `destinationId`, `manualArrival`, …).

## Identitätsregeln

- **Halt ≠ Station.** `StopStation.uuid` identifiziert den Besuch,
  `station.id` die Station. Zwei Züge können dieselbe Station mehrfach
  anfahren.
- `matchesStopover` akzeptiert gleiche UUID oder gleiche Station plus
  identische Plan-Ankunft und Plan-Abfahrt.
- `getNearbyStations` dedupliziert nur nach ID/UUID. Gleicher Name oder gleiche
  Koordinaten beweisen keine Identität.
- `TrainStation.ibnr` wird nur aus dem Identifier-Typ `de_db_ibnr` gelesen,
  `rilIdentifier` aus `de_db_ril100`.
- `StopoversResponse.data` ist eine Map `tripId → Liste`; `allStopovers()`
  flacht sie ab.

## Enums

- `StatusVisibility`: 0 PUBLIC, 1 UNLISTED, 2 FOLLOWERS, 3 PRIVATE,
  4 AUTHENTICATED, 5 TRUSTED.
- `TravelReason`: 0 PRIVATE, 1 BUSINESS, 2 COMMUTE.

Unbekannte Werte dürfen nicht stillschweigend auf einen anderen Wert fallen;
die Tests prüfen die Abbildung.

## Echtzeit- und Geometriemodelle

- `StopRealtimeInfo(fetchedAtMillis, sourceLabel, providerUpdatedAtMillis?)`.
  Standard-Frische 120 s; Labels „älterer Abruf“ und „Aktualität unbekannt“.
- `RoadRouteModels`: Straßenroute mit Punkten, Distanz, Dauer, Quelle.
- `TransitRouteModels`: native Geometrie mit geordneten Besuchen.
- `SevModels`: Karte, Halt, Gültigkeitsfenster, Grund für fehlende Koordinaten.

Alle Geometriemodelle tragen nur öffentliche Haltestellenkoordinaten und
geordnete Besuchsschlüssel, keine Geräteposition.

## Deduplizierung

- Feed-Status werden nicht anhand des Texts dedupliziert.
- Stopovers werden über `uuid` bzw. Station plus Planzeiten zusammengeführt.
- Ergänzende Felder dürfen zusammengeführt werden; widersprüchliche Werte
  erben nie die Provenienz eines anderen Abrufs.
- Ein Halt aus zwei Quellen wird nur zusammengeführt, wenn die Identität
  eindeutig ist.

## Tests

`ApiCompatibilityTest` (Fixture-Formen), `StopoverDeduplicationTest`,
`NearbyStationIdentityTest`, `DepartureOperatorTest`,
`StopRealtimeInfoTest`, `ApiCompatibilityTest`.

## Risiken

- Das Modell ist stark an die reale Träwelling-Antwort gebunden; neue Felder
  werden ignoriert, fehlende Pflichtfelder fallen erst zur Laufzeit auf.
- `StopOperator.id` ist `String`, um numerische und UUID-IDs zu akzeptieren.
  Wer `Int` erwartet, bricht.
- Die Identität eines Halts ist die häufigste Fehlerquelle bei GPS und SEV.

## Verwandte Seiten

- [Träwelling-API](../api/traewelling-api.md)
- [Lokale Speicherung](lokale-speicherung.md)
- [GPS-Zeiten](../modules/gps-zeiten.md)
- [Index](../index.md)
