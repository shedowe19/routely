# Live-Datenquellen

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Routely kombiniert vier öffentliche Datenquellen mit den Träwelling-Daten.
Diese Seite beschreibt, wann eine Quelle verwendet wird, welche Felder sie
liefern darf und wann sie bewusst schweigt.

## Quellenübersicht

| Quelle | Liefert | Wird verwendet für | Abschaltbar |
|---|---|---|---|
| Träwelling-API | Plan- und Echtzeitzeiten, Gleise, Ausfälle | Basis aller Fahrten | nein |
| DBF/IRIS v3 | Bahn-Echtzeit pro Station | Bahn-Halte (nicht Bus) | ja, `public_rail_realtime_enabled` |
| Träwelling-Polyline | Kurvengeometrie einer Fahrt | Bahn und Tram | nein |
| OSRM | Straßenrouten | SEV-Abschnitte | nein |
| bahnhof.de | Ersatzhaltestellen und Kartenpunkte | SEV-Abschnitte | nein |

## DBF/IRIS v3

**Zweck:** anonyme Echtzeit für Bahnfahrten ergänzen, ohne ein Konto oder einen
zweiten API-Schlüssel.

**Ablauf**

1. Nur wenn die Funktion aktiviert ist, Haltestellen vorhanden sind, die
   Kategorie eine Bahn ist, der Modus nicht Bus ist und `journeyNumber > 0`.
2. Kandidaten liegen im Fenster −60 min…+120 min um jetzt; höchstens zwei
   Stationen und sechs Besuche.
3. Abruf pro Station, Gesamtdeadline 8 s.
4. `DbfMatcher` akzeptiert nur die exakt angefragte EVA und eine positive
   Zugnummer. Mehrdeutige Zeilen, fremde Station oder doppelte DST-Stunde
   liefern `null`.
5. `DbfStopDelta` wird feldweise angewendet: Verspätung, Gleis, Ausfall.

**Grenzen**

- Verspätungen nur im Bereich −180…720 min und nur wenn
  `missingRealtime != true`. `delay == 0` gilt nicht als Pünktlichkeitsbeweis.
- `isCancelled == false` wird als „Feld abwesend“ behandelt, nicht als
  „nicht ausgefallen“, weil v3 eine teilweise Aufhebung nicht beweisen kann.
- Ausfall wird nur gemeldet, wenn alle angefragten Ereignisse übereinstimmen.
- Ein reines Gleisupdate erneuert keine alte Zeit; jedes Feld behält sein
  eigenes Lesealter.
- 2 MiB Body, UTF-8 strikt, keine Redirects, keine fremde Origin.
- 10 Abrufe/Minute global, 1 Abruf/Station/Minute; fehlgeschlagene Abrufe
  verbrauchen ebenfalls Quote.
- HTTP `Date`/`Age` älter als 180 s oder mehr als 60 s in der Zukunft wird
  verworfen.

**Warum so streng:** Das v3-Schema enthält keine Stations-EVA, kein Datum,
keine Trip-ID und keinen Provider-Zeitstempel. Eine falsche Zuordnung würde
eine fremde Fahrt als die eigene anzeigen.

## Native Streckengeometrie (Träwelling-Polyline)

**Zweck:** echte Kurven statt gerader Linien zwischen Haltestellen.

- `GET {server}/api/v1/polyline/{statusId}` mit Bearer-Token.
- Akzeptiert nur ein `FeatureCollection`-Feature mit passender `statusId`.
- 2…256 eindeutige Besuchsschlüssel; Start/Ziel nicht ausgefallen; Endpunkte
  ≤ 250 m vom Halt.
- Jeder aktive Abschnitt braucht einen eigenen Kurvenbeleg. Eine verdichtete
  Stationssehne wird verworfen.
- Cache pro Session: 15 min hart, 14 min sanfte Aktualisierung. Fremde Origin,
  401/403/406 und unparsbare Bodies werden nicht negativ gecacht.

## OSRM

**Zweck:** Straßenverlauf für SEV-Abschnitte, wenn keine native Geometrie
existiert.

- Anonyme Anfrage an den FOSSGIS-Routingdienst, `driving`, GeoJSON,
  vollständige Übersicht, bis zu zwei Alternativen.
- Nur öffentliche Haltestellenkoordinaten; keine Geräteposition.
- Grenzen: 100 m…50 km Luftlinie, 5.000 Punkte, 2 MiB, 150 m Snap,
  ±10 %/150 m Längenabweichung.
- Mindestens 1 s zwischen globalen Starts; Erfolg 24 h, transienter Fehler
  60 s, deterministischer Fehler 15 min.
- OSRM-Zeiten sind keine Busprognosen und belegen keinen offiziellen
  Linienweg.

## bahnhof.de SEV

**Zweck:** Ersatzhaltestellen für Bus RE/RB finden, die in der Träwelling-
Halteliste fehlen.

- Nur Busse mit Linienname `Bus RE…` oder `Bus RB…`.
- Nur Halte zwischen eindeutig gebundenem Start und Ziel, ohne Ausfälle,
  dedupliziert, höchstens 64 Slugs.
- 3 parallele Abrufe, 45 s Batch-Deadline; erfolgreiche Karten überleben einen
  Timeout des Batches.
- HTML nur von `bahnhof.de`, ≤ 8 MiB, `text/html`; Anker ≤ 1.500 m, Punkte
  ≤ 3.000 m vom Bahnhof; Karte ≤ 24 h alt.
- Koordinaten werden bewusst zurückgehalten, wenn Gültigkeitsdaten unklar oder
  abgelaufen sind, Besuchsidentitäten doppelt sind, Labels fehlen oder mehrere
  Richtungen konkurrieren.

## Anzeige und Quellenwahl

`StopRealtimeInfo` trägt `fetchedAtMillis`, `sourceLabel` und optional
`providerUpdatedAtMillis`. Standard-Frische ist 120 s. Angezeigt wird
„älterer Abruf“ oder „Aktualität unbekannt“, wenn die Quelle das nicht belegen
kann. Der Abrufzeitpunkt ist nie der Provider-Zeitpunkt.

Für Reisezeiten gilt die Reihenfolge aus
[GPS-Zeiten](../modules/gps-zeiten.md): GPS beobachtet → GPS geschätzt →
manuell → API-Echtzeit → Fahrplan.

## Risiken

- Alle drei öffentlichen Dienste sind unversionierte Fremdsysteme. Änderungen
  fallen nur über Parser-Tests und Fixtures auf.
- DBF v3 ist bewusst konservativ; fehlende Echtzeit bleibt sichtbar, statt
  geschätzt zu werden.
- SEV-Karten sind kein amtlicher Linienweg. Die App nutzt sie nur als
  Haltestellenkandidaten mit Distanzprüfung.

## Verwandte Seiten

- [Externe Abhängigkeiten](../architecture/externe-abhaengigkeiten.md)
- [SEV-Haltestellen](../modules/sev-haltestellen.md)
- [Streckenverläufe](../modules/streckenverlaeufe.md)
- [Index](../index.md)
