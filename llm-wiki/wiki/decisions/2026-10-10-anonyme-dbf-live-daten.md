# ADR: Anonyme DBF/IRIS-Echtzeit

Status: accepted
Datum: 2026-10-10
Last verified: 2026-11-04
Confidence: high

## Kontext

Die Träwelling-API liefert nicht für jede Bahnfahrt aktuelle Ist-Zeiten und
Gleise. DBF/IRIS v3 stellt anonyme Stationsbretter bereit. Ein zusätzlicher
Account oder API-Schlüssel wäre für einen reinen Client unverhältnismäßig.

Belege: Commit `9340072` „Add anonymous DBF IRIS live data for rail journeys“,
`README.md:22`, `data/dbf/DbfRealtimeRepository.kt`,
`app/src/test/resources/dbf/README.md`.

## Entscheidung

- DBF/IRIS v3 wird anonym für Bahnfahrten ergänzt.
- Nur Bahn-Kategorien, nicht Bus, und nur mit positiver Zugnummer.
- Die Funktion ist über `public_rail_realtime_enabled` abschaltbar
  (Standard: an).
- Jedes Feld behält sein eigenes Lesealter; eine Gleisänderung erneuert keine
  alte Zeit.
- Ausfall wird nur gemeldet, wenn alle angefragten Ereignisse übereinstimmen.
- Bei Mehrdeutigkeit oder fehlender Übereinstimmung wird nichts übernommen.

## Folgen

- Positiv: Aktuellere Gleise und Verspätungen ohne Konto oder Schlüssel.
- Negativ: Abhängigkeit von einer unversionierten Fremdquelle; strenge
  Ratenlimits und Parser-Grenzen.
- Datenschutz: Kein Token, kein Konto, keine Geräteposition wird gesendet.
- Tests: `DbfMatcherTest`, `DbfJsonParserTest`, `DbfRealtimeRepositoryTest`.

## Alternativen

- **Authentifizierte DB-API:** verworfen, weil kein passender Zugang für einen
  Client ohne Backend existiert.
- **Scraping der DB-Webseite:** verworfen, weil instabiler und ohne klaren
  Maschinenvertrag.
- **Keine Anreicherung:** verworfen, weil die App sonst bei fehlender
  Träwelling-Echtzeit nur Planzeiten zeigen würde.

## Quellen

- Commit `9340072`, `21f1472`
- `data/dbf/DbfMatcher.kt:23-72`
- `data/dbf/DbfRealtimeRepository.kt:49-272`
- `app/src/test/resources/dbf/README.md`
