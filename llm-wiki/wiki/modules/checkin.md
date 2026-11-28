# Check-in

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Der Check-in führt von der Stationssuche über Fahrt und Ziel bis zum
Träwelling-Status. Er ist der schreibkritischste Ablauf der App: Ein zweiter
POST könnte einen doppelten Check-in erzeugen.

## Ablauf

1. **Station:** Suche per Autocomplete oder Umkreissuche. Die Umkreissuche
   nutzt eine feste Bounding-Box und dedupliziert nach ID/UUID, nicht nach
   Name oder Koordinaten.
2. **Abfahrten:** Aktuelles Board ohne `when`, historische Suche mit
   unveränderlichem UTC-Zeitpunkt.
3. **Fahrt:** `getTrip` liefert Haltestellen und Linieninformationen.
4. **Ziel:** Nur gültige spätere Halte sind wählbar. Bei mehrdeutigem
   Einstieg wird abgebrochen, statt zu raten.
5. **Optionen:** Sichtbarkeit (0–5), Reisegrund, StatusText, manuelle Abfahrt
   und Ankunft.
6. **Absenden:** genau ein POST mit den geplanten Haltemarkern.
7. **Korrektur:** Nur falls der Nutzer Zeiten geändert hat, folgt ein PUT auf
   den neu erzeugten Status.
8. **Tracking:** Aktivierung nur nach erfolgreichem Abschluss, nur bei
   zukünftiger Ankunft, mit 15-s-Grenze und ohne eine andere aktive Fahrt zu
   ersetzen.

## Schreibregeln

- `CheckInSubmission.create` wird genau einmal aufgerufen. Ein akzeptierter,
  aber unvollständiger Response wird als abgeschlossen mit Warnung behandelt.
- Manuelle Zeiten werden **nicht** in den POST eingebettet, sondern als
  separater PUT gesendet. So bleibt der Check-in selbst unabhängig von der
  Zeitkorrektur.
- Nur vom Nutzer geänderte Zeiten werden gesendet. Eine Korrektur friert das
  andere Ereignis nicht ein.
- Eine einseitige Korrektur ist erlaubt; ungültige oder umgekehrte Zeiten
  werden abgelehnt.
- Sichtbarkeit wird immer explizit gesendet; Standard ist öffentlich.
- Ein lokaler Speicherfehler vor dem POST verhindert den HTTP-Aufruf. Ein
  Fehler nach dem POST macht die Operation nicht rückgängig.

## Navigation

- Zurück, Reset und Bearbeiten sind gesperrt, solange ein POST oder eine
  Korrektur laufen könnte (`CheckInNavigationGuard`).
- Ein Zurück aus einem erkannten Ride darf keine früheren Abfahrten
  wiederverwenden.
- Ein offscreen liegender Check-in-Tab delegiert Zurück an die Activity.
- Das Ziel wird nach Erfolg als Statusdetail geöffnet; das Tracking läuft
  parallel an.

## Tests

- `CheckInSubmissionTest` — genau ein POST, Teil-erfolge, Sichtbarkeit,
  einseitige Korrekturen.
- `CheckInStorageCommitTest` — akzeptierte Anlage trotz Speicherfehler.
- `CheckInTrackingCompletionTest` — historische Fahrt, laufende Fahrt,
  Fremdfahrt, 15-s-Grenze.
- `CheckInSelectionTest`, `CheckInDepartureSearchTest`,
  `CheckInNavigationGuardTest`, `CheckInBackNavigationTest`.
- `CheckInConflictRepositoryTest` — 409-Konflikte.

## Risiken

- Ein Check-in kann serverseitig angenommen und lokal trotzdem unklar sein.
  Die UI muss „akzeptiert“ von „fehlgeschlagen“ unterscheiden.
- Die Umkreissuche nutzt eine feste Box; der Parameter
  `announcement_radius_meters` gilt nur für Ansagen.
- Der Flow ist zustandsreich. Änderungen an `CheckInUiState` müssen die
  Guard- und Submission-Tests mitziehen.

## Verwandte Seiten

- [Datenzugriff](datenzugriff.md)
- [Fahrterkennung](fahrterkennung.md)
- [Träwelling-API](../api/traewelling-api.md)
- [Index](../index.md)
