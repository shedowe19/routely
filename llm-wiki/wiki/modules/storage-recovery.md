# Storage-Recovery

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Die Storage-Recovery verhindert, dass korrupte oder nicht lesbare Preferences
stillschweigend gelöscht werden. Stattdessen blockiert die App und verlangt eine
bewusste Bestätigung für einen Reset.

## Zustandsmaschine

`PreferenceStorage` führt einen Gesundheitszustand:

```
LOADING → READY
   │
   ├─ transienter Fehler → RETRYING (250 ms, 750 ms, max. 3 Versuche)
   │        └─ weiterhin Fehler → IO_ERROR
   └─ Korruption → CORRUPTED
```

- `READY` bedeutet nicht automatisch „benutzbar“. Die UI braucht zusätzlich
  einen frisch gelesenen, bestätigten Snapshot nach der aktuellen
  Storage-Revision.
- Ein zwischengespeicherter Präsentations-Snapshot darf keine Interaktion
  erlauben.

## Blockierende Oberfläche

- Solange die Session nicht bestätigt ist, zeigt `MainActivity` einen
  modalen `StorageRecoveryDialog`.
- Der Dialog blockiert Touch, Zurück und Accessibility-Fokus.
- Ein Reset ist nur mit der aktuellen `failureId` möglich. Eine alte
  Bestätigung kann keinen neueren Fehler quittieren.
- Nach erfolgreichem Reset entsteht eine frische, abgemeldete Revision; Token,
  aktive Fahrt und Trackingzustand sind gelöscht, die Fahrterkennung ist aus.

## Warum ein Probe-Schreibvorgang

Ein DataStore-Update mit unveränderten Werten kann ohne echten Disk-I/O
erfolgen. Nach einem Schreibfehler führt die Recovery deshalb einen
Probe-Schreibvorgang auf `storage_probe_revision` aus, bevor sie wieder
`READY` meldet.

## Garantien

- Fehlgeschlagene Schreibvorgänge werden nie optimistisch veröffentlicht.
- Fehlgeschlagene Schreibvorgänge werden nicht automatisch wiederholt, wenn
  sie Geschäftsaktionen darstellen.
- Ein alter Beobachter oder eine alte Fehlermeldung kann keinen neueren
  Zustand zurückrollen.
- Ein Reset löscht nur nach ausdrücklicher, aktueller Zustimmung.

## Zusammenwirken mit dem Tracking

`StorageRecoveryServiceBoundary` pausiert den Tracking-Service genau einmal,
wenn Preferences nicht lesbar oder schreibbar sind. Der gespeicherte
Reisezustand bleibt erhalten; das Widget zeigt „Begleitung pausiert – App
öffnen“. Erst nach Recovery und sichtbarem Neustart läuft die Begleitung
weiter.

## Tests

- `PreferenceStorageTest` — Retry-Budget, Bestätigung, Probe, Abbruch.
- `PreferenceStorageFileTest` — echte DataStore-Bytes, Reset-Dauerhaftigkeit.
- `StorageRecoveryPresentationTest` — Kaltstart, Revisionen, Reset-Berechtigung.
- `StorageRecoveryServiceBoundaryTest` — einmalige Pause.
- `StorageActionTest` — Fehler genau einmal melden.

## Risiken

- Die App ist bei Storage-Fehler absichtlich nicht benutzbar. Das ist ein
  bewusster Trade-off gegen Datenverlust.
- Ein Reset ist destruktiv und nicht rückgängig zu machen.
- Die Wiederherstellung ist prozesslokal; es gibt keinen Export der alten
  Bytes vor dem Reset.

## Verwandte Seiten

- [Lokale Speicherung](../data/lokale-speicherung.md)
- [Auth und Sitzung](auth-und-sitzung.md)
- [Entscheidung: Storage-Recovery](../decisions/2026-11-03-storage-recovery.md)
- [Index](../index.md)
