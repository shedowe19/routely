# Feed und Statusdetails

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite bündelt die beiden eng gekoppelten Lese-/Schreibbereiche: den Feed
mit Likes und der Statusdetail-Screen mit Haltestellenverlauf, Bearbeiten und
Löschen.

## Feed

- Zwei Feeds: persönliches Dashboard und globale Timeline.
- Beide teilen sich einen Controller-Typ, aber getrennte Cache-Partitionen.
- Seiten > 1 werden nicht gecacht.
- Ein Wechsel des Feed-Typs darf eine laufende Like-Operation nicht verlieren.
- Ein Like ist optimistisch: Die Karte ändert sich sofort, ein Fehler rollt auf
  den zuletzt bestätigten Stand zurück.
- Ein GET, das vor einem bestätigten Schreibvorgang gestartet wurde, darf
  dessen Ergebnis nicht überschreiben. Dafür sorgen Generationen und ein
  Intent-Ledger pro Status.
- Ein unbekannter Status wird durch ein Ereignis nicht in den Feed eingefügt.

## Statusdetail

- Lädt Status und Stopovers konsistent (siehe
  [Datenzugriff](datenzugriff.md)).
- Aktualisiert alle 30 s, solange der Screen beobachtet wird; pausiert während
  Bearbeiten, Aktualisieren oder Löschen.
- Ein fehlgeschlagener Stopover-Refresh behält nur Haltestellen, die noch zu
  Status, Fahrt und Fahrplan passen.
- SEV-Anreicherung läuft nach dem sichtbaren Snapshot, blockiert die Anzeige
  nicht und ist an Generation und Session gebunden.
- Der Haltestellenverlauf nutzt einen einzigen Cursor: GPS-Fortschritt schlägt
  Uhr-Fortschritt; ein nicht zugeordneter GPS-Besuch führt zu „wartend“, nicht
  zu einem Sprung.

## Bearbeiten

- Gesendet werden nur Deltas: Text, manuelle Zeiten, Ziel, Sichtbarkeit.
- Ein Hintergrund-Refresh darf unveränderte Felder nicht auf den Server
  zurückschreiben.
- Eine Zieländerung löscht die alte manuelle Ankunft.
- Das Leeren einer manuellen Zeit sendet einen leeren Wert, kein Weglassen.
- Eine Moderationssperre verhindert Sichtbarkeitsänderungen.
- Zeitvalidierung nutzt die effektiven Werte nach dem Entfernen von
  Overrides. Abfahrt wird gegen die aktuelle Ankunft geprüft; Gleichstand ist
  erlaubt.

## Löschen

- Server-first. Erst nach Bestätigung wird lokal aufgeräumt.
- Die Tracking-Bereinigung ist auf 15 s begrenzt und darf einen bestätigten
  Löschvorgang nicht in einen Fehler verwandeln.
- Ein bereits entsorgter Screen darf die Abschluss-Navigation nicht auslösen.

## Tests

`FeedControllerTest`, `FeedLikeOrderingTest`, `FeedTabSwitchLikeTest`,
`StatusDetailReadTest`, `StatusDetailPresentationTest`,
`StatusEditRequestTest`, `StatusEditTimeValidationTest`,
`DeletedStatusCompletionTest`, `StatusTimeCommitRepositoryTest`.

## Risiken

- Optimistische Updates und parallele GETs sind die häufigste Fehlerquelle.
  Neue Aktionen müssen dieselbe Generationen-/Ledger-Logik verwenden.
- Der Detail-Screen hat viele Zustandsfelder; ein Reset darf keinen laufenden
  Schreibvorgang verlieren.
- Bearbeiten und Löschen teilen sich den Mutations-Ledger mit dem Feed. Eine
  Änderung an der Reihenfolge betrifft alle drei Bereiche.

## Verwandte Seiten

- [Datenzugriff](datenzugriff.md)
- [Datenfluss](../architecture/datenfluss.md)
- [UI und Design](../frontend/ui-und-design.md)
- [Index](../index.md)
