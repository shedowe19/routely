# ADR: Sichere Storage-Recovery

Status: accepted
Datum: 2026-11-03
Last verified: 2026-11-04
Confidence: high

## Kontext

Session, Einstellungen und Trackingzustand liegen in einem DataStore. Eine
Korruption oder ein Lesefehler darf nicht dazu führen, dass Token, aktive Fahrt
oder Einstellungen stillschweigend verschwinden. Gleichzeitig darf eine
kaputte Datei die App nicht dauerhaft unbenutzbar machen.

Belege: Commit `94bf23c` „Add safe storage recovery and native journey time
editing“, `util/PreferenceStorage.kt`,
`util/StorageRecoveryPresentation.kt`, `ui/components/StorageRecoveryDialog.kt`.

## Entscheidung

- Die App blockiert die Oberfläche, solange kein frisch bestätigter
  Storage-Snapshot vorliegt.
- Ein Reset ist nur mit der aktuellen `failureId` möglich.
- Ein Reset löscht Token, aktive Fahrt und Trackingzustand und erzeugt eine
  frische, abgemeldete Sessionrevision.
- Nach einem Schreibfehler wird ein Probe-Schreibvorgang ausgeführt, bevor
  wieder `READY` gemeldet wird.
- Fehlgeschlagene Schreibvorgänge werden weder optimistisch veröffentlicht
  noch automatisch wiederholt.
- Der Tracking-Service pausiert bei Storage-Fehler genau einmal und bewahrt
  den gespeicherten Reisezustand.

## Folgen

- Positiv: Kein stiller Datenverlust; klare Nutzerentscheidung.
- Negativ: Die App ist bei Storage-Fehler absichtlich nicht benutzbar; ein
  Reset ist destruktiv.
- Invarianten: Präsentations-Snapshots autorisieren keine Interaktion; alte
  Beobachter können keinen neueren Zustand zurückrollen.
- Tests: `PreferenceStorageTest`, `PreferenceStorageFileTest`,
  `StorageRecoveryPresentationTest`, `StorageRecoveryServiceBoundaryTest`,
  `StorageActionTest`.

## Alternativen

- **Automatisch löschen und neu starten:** verworfen, weil Token und aktive
  Fahrt ohne Zustimmung verloren gingen.
- **Auf Standardwerte zurückfallen:** verworfen, weil ein Lesefehler dann als
  gültige leere Konfiguration erscheint.
- **Crash mit Fehlerdialog:** verworfen, weil der Nutzer keine sichere
  Wiederherstellung hätte.

## Quellen

- Commit `94bf23c`
- `util/PreferenceStorage.kt:31-243`
- [Storage-Recovery](../modules/storage-recovery.md)
