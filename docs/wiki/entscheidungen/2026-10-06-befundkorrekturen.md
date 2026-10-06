# Entscheidung: Belegbare GPS-, Mutations- und Releasezustände

## Datum und Status

06.10.2026 — Akzeptiert.

## Zweck und Kontext

Die 16 Befunde des [erneuten Main-Reviews](../entwicklung/main-review-2026-10-06.md) zeigen, dass eine Antwort oder Position nur innerhalb ihrer eigenen Zeit-, Besuchs-, Konto- und Veröffentlichungsbasis übernommen werden darf. Die Korrektur ergänzt diese Grenzen ohne neue Standortübertragung, automatischen öffentlichen Check-in oder Änderung der Android-Zielversion.

## Wichtige Dateien

- `service/StationTrackingEngine.kt`, `TrackingLocationObservation.kt`, `TripTrackingService.kt`, `RideRecognitionService.kt`
- `viewmodel/StatusEditRequest.kt`, `CheckInSubmission.kt`, `NotificationController.kt`, `FeedController.kt`
- `data/repository/StatusMutation.kt`, `TraewellingRepository.kt`, `AuthRepository.kt`
- `.github/scripts/release_guard.py`, `.github/workflows/android.yml`
- `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`

Kotlin-Pfade beziehen sich auf `app/src/main/kotlin/de/traewelling/app/`.

## Entscheidungen

1. Standortbelege werden vor jedem physischen Fortschritt gemeinsam auf plausible kurze Bewegung geprüft. Verworfene Ausreißer werden nicht die Referenz des nächsten Fixes; echte längere Signalpausen benutzen weiterhin die getrennte Wiederverankerung. Alter und Reihenfolge stammen aus `elapsedRealtimeNanos`, darstellbare Ereigniszeiten aus der aktuellen Uhr minus monotonem Alter. Ein Uhrsprung verwirft Bewegungs-/Zeitbelege, erhält aber Besuchscursor und Ansagededuplizierung.
2. FLP-/TTS-Initialisierung hat begrenzte Retryserien und Besitzerkennungen. Entzogene Berechtigung oder ausgeschaltete Ortung erlauben keine automatische Neufreigabe. TTS-Konfigurationswechsel erneuert ausschließlich die zuständige Engine; wartende Ansagen werden vor Wiederholung auf den aktuellen Besuch geprüft.
3. Formularabsicht wird gegen den Editor-Anfangssnapshot bestimmt. Planbesuchsmarker bleiben im Check-in-POST; manuelle Istzeiten folgen als PUT derselben Sitzung. Eine erfolgreiche Servermutation wird durch einen lokalen Folgefehler nicht rückwirkend als erneut auszuführende Mutation angeboten.
4. Meldungs- und Feedcontroller bekommen kontrollierbare Coroutine-Scope-/Gateway-Grenzen. Bestätigte Lesemarkierungen überlagern ältere Listenantworten; Count-Anfragen sind separat geordnet. Statusmutationen invalidieren die eigene Credentialcachepartition unabhängig von einem späteren Auth-Revisionswechsel. UI-Ereignisse gelten ausschließlich für die passende aktive Sitzung; alte GETs dürfen keine gelöschte Karte oder alte Room-Seite zurückbringen.
5. Sicherungsregeln schließen den gemischten DataStore und Datenbanken in alten/neuen Android-Regelwerken aus Cloud- und Geräteübertragung aus. Einstellungen in demselben DataStore werden daher ebenfalls nicht übertragen; lokale Speicherung bleibt erhalten. OAuth-Refresh behält einen nicht ersetzten Refresh-Token und löscht eine passende Sitzung nur bei belegtem `invalid_grant`.
6. Releases werden neu reserviert und ausschließlich über ihre eigene Draft-ID publiziert. Eine dauerhafte Legacy-Codeuntergrenze und überprüfte künftige Versionsassets bestimmen den nächsten Code. Tag, Checkout und APK werden an denselben Commit gebunden; APK-Manifest, Größe und SHA-256 müssen vor Veröffentlichung stimmen. Vorhandene Versionen werden nie überschrieben.

## Konsequenzen und Alternativen

Die Tests verwenden synthetische GPS-Folgen, virtuelle Coroutine-Zeit und Offline-GitHub-Doubles. Dies macht verzögerte Antworten reproduzierbar, beweist jedoch keine Geräte-/OEM-/TTS- oder reale Releasezustellung. Unrealistisch schnelle historische GPS-Testfolgen werden zeitlich plausibel gemacht; die Schutzgrenzen werden nicht zum Bestehen alter Fixtures gelockert.

Ein Uhrzeit-only Fortschritt, pauschales Zusammenlegen naher Stationsnamen, Wiederholen eines erfolgreichen POST nach fehlgeschlagenem PUT und Aktualisieren bestehender Releaseassets wurden verworfen. Ein Releasefehler nach Reservierung kann einen Tag oder unveröffentlichten Draft zurücklassen; der Workflow bereinigt ihn nicht automatisch. Eine größere Settings-/Credential-Speichertrennung und reale Gerätestests bleiben getrennte Aufgaben.

## Abhängigkeiten und offene Fragen

Produktive Bibliothekspins bleiben unverändert. Nur `kotlinx-coroutines-test` wird als Testabhängigkeit in derselben Version wie Coroutines ergänzt. Releaseguards benötigen Python 3 mit Standardbibliothek und GitHub REST; es gibt keine neue produktive Drittanbieterabhängigkeit.

- TODO: Display-aus-/Doze-, Uhrsprung-, Standort-/TTS-Fehler- und Backup-Restore-Matrix auf Android/Samsung prüfen.
- TODO: Ersten neuen signierten Release samt tatsächlichen veröffentlichten Assets prüfen.

## Verwandte Seiten

- [Main-Review und Korrekturen](../entwicklung/main-review-2026-10-06.md)
- [Tests](../entwicklung/tests.md)
- [Deployment](../entwicklung/deployment.md)
- [TripTracking](../module/trip-tracking.md)
- [Feed](../module/feed.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
