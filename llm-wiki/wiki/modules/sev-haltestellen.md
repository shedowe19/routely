# SEV-Haltestellen

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Bei Ersatzverkehr (SEV) fehlen in der Träwelling-Halteliste oft die
tatsächlichen Bushalte. Routely liest öffentliche Bahnhofskarten von
bahnhof.de, um plausible Ersatzhalte zu ergänzen — ohne den offiziellen
Linienweg zu behaupten.

## Erkennung

Ein Halt gilt nur dann als SEV-Kandidat, wenn:

- `category` oder `mode` „bus“ ist (case-insensitive), **und**
- der Linienname `^(bus )?(re|rb)\s*\d+` entspricht.

Quelle: `data/sev/SevStopResolver.kt:25-29`.

## Ablauf

1. `SevJourneyEnricher` bindet Start und Ziel eindeutig im Haltestellenverlauf.
2. Nur Halte zwischen diesen beiden (inklusive), ohne Ausfälle, dedupliziert,
   höchstens 64 Slugs.
3. `BahnhofSevRepository` ruft `https://www.bahnhof.de/{slug}/karte` ab.
4. `BahnhofSevParser` extrahiert die eingebetteten Next.js-Daten.
5. `SevStopResolver` wählt einen physischen Kartenpunkt pro Halt und prüft die
   Richtung.

## Validierung

- HTML nur von `bahnhof.de`, ≤ 8 MiB, `text/html`.
- Anker ≤ 1.500 m vom API-Halt, Punkte ≤ 3.000 m vom Bahnhof.
- Eindeutige IDs, gültige Koordinaten, Karte ≤ 24 h alt und nicht
  zukunftsdatieriert.
- Gültigkeitsfenster werden deutsch geparst (`vom/von … bis …`,
  „Richtung X bis …“).
- Eine zusätzliche Richtungsangabe wird nur dann aus dem Fenster entfernt,
  wenn genau ein Punkt dieselbe Orts- und Fristangabe trägt.

## Wann Koordinaten fehlen

Koordinaten werden bewusst zurückgehalten, wenn:

- Gültigkeitsdaten unklar, abgelaufen oder widersprüchlich sind,
- die Besuchsidentität doppelt oder unparsebar ist,
- Labels fehlen oder unbekannt sind,
- mehrere Richtungen konkurrieren.

Die App zeigt dann einen deutschen Grund statt einer geratenen Position.

## Identität und Grenzen

- `visitKey` ist die Halt-UUID oder
  `stationId:arrivalPlanned:departurePlanned`. Ein Listenindex wird nie
  verwendet.
- Duplikate bei Start/Ziel oder umgekehrte Fenster erzeugen keine Anfragen.
- 3 parallele Abrufe, 45 s Batch-Deadline. Erfolgreiche Karten überleben einen
  Timeout des Batches.
- Cache: 64 Einträge, Erfolg 6 h, Fehler 15 min, Single-Flight pro Slug.

## Tests

`BahnhofSevParserTest`, `SevStopResolverTest`, `SevJourneyEnricherTest`,
`SevDirectionalValidityTest`, `RoadRouteSelectionTest`. Abgedeckt:
Payload-Extraktion, Labels, Duplikate, Distanzen, Zeitfenster, Mehrdeutigkeit,
Slug-Sicherheit. Nicht abgedeckt: echte Seitenänderungen ohne Fixture.

## Risiken

- bahnhof.de ist eine Website, keine API. Ein Redesign bricht den Parser.
- Die Karte ist ein öffentlicher Plan, kein amtlicher SEV-Linienweg.
- Eine falsche Richtungszuordnung würde Fahrgäste an den falschen Halt
  schicken; deshalb wird lieber nichts angezeigt.

## Verwandte Seiten

- [Live-Datenquellen](../api/live-datenquellen.md)
- [Streckenverläufe](streckenverlaeufe.md)
- [GPS-Zeiten](gps-zeiten.md)
- [Index](../index.md)
