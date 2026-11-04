# Aktueller Projektstand

Last updated: 2026-11-04

## Meilenstein

`v1.12.0` ist getaggt und dokumentiert (2026-11-03). Der letzte funktionale
Commit `94bf23c` brachte sichere Storage-Recovery und native Bearbeitung von
Reisezeiten; `d485d17` dokumentiert die Veröffentlichung. Arbeitsstand ist
`main` mit uncommitteten Änderungen an `AGENTS.md` und `docs/wiki/` (siehe
Risiken).

## Was funktioniert

- Token-Login gegen eine HTTPS-Träwelling-Instanz, Sessionvalidierung und
  lokaler Logout (`data/repository/AuthRepository.kt`).
- Feed, globale Timeline, Likes, Statusdetail, Bearbeiten und Löschen mit
  serialisierten Schreibvorgängen und Offline-Cache der ersten Seite.
- Check-in inklusive Haltestellensuche, Zielwahl, Sichtbarkeit, manuellen
  Zeiten und genau einem POST (`viewmodel/CheckInSubmission.kt`).
- Live-Begleitung: 60-s-Statuspoll, GPS-Fortschritt, lokale Prognosen,
  Ansagen, Fahrtänderungs-Hinweise, Notification und Widget
  (`service/TripTrackingService.kt`).
- Anonyme DBF/IRIS-Echtzeit für Bahnfahrten, OSRM-Straßenrouten und
  bahnhof.de-SEV-Haltestellen mit strengen Plausibilitätsprüfungen.
- Fahrterkennung als ausdrücklich nutzergestarteter Vorschlagsdienst.
- Storage-Recovery: Bei korrupten Preferences blockiert die App, statt Daten
  stillschweigend zu löschen; ein Reset braucht eine aktuelle Bestätigung.

## Was aktuell nicht funktioniert oder fehlt

- **Dokumentationspfade sind gebrochen.** `README.md` verlinkt
  `docs/wiki/...`, und `.github/workflows/wiki-sync.yml` beobachtet
  `docs/wiki/**`. Im Arbeitsbaum ist `docs/wiki/` vollständig gelöscht
  (uncommitted). Siehe [Dokumentationspfade](contradictions/2026-11-04-dokumentationspfade.md).
- **OAuth ist unerreichbar.** Manifest-Filter, PKCE-Helfer und
  `exchangeCodeForToken` existieren, aber kein Produktivpfad ruft sie auf;
  `MainActivity` ignoriert `intent.data`.
- **Keine Geräte-/UI-Tests.** Alle Tests sind JVM-Unit-Tests; Service-
  Lebenszyklus, Notifications, Widget und Compose-Verhalten sind ungetestet.
- **`targetSdk 34` bei `compileSdk 36`.** Der Android-15-Callback `onTimeout`
  ist implementiert, sein Verhalten auf allen OEMs ist aber nicht verifiziert.
- **Feed-Cache wird beim Logout nicht global gelöscht.** Room-Zeilen sind an
  einen Hash aus Server-URL und Token gebunden und bleiben nach Logout oder
  Tokenwechsel liegen.

## Als Nächstes

1. Entscheidung zu `docs/wiki/`: Pfade in README/CI auf `llm-wiki/` umstellen
   oder den alten Pfad wiederherstellen.
2. OAuth-Lücke schließen: entweder Browser-Flow implementieren (Deep Link
   verarbeiten, Refresh speichern) oder Filter und toten Code entfernen.
3. Geräte-Testmatrix für Tracking, Notification-Aktionen und Widget aufsetzen.
4. Cache-Löschpolitik für Logout/Tokenwechsel festlegen.
5. `targetSdk`-Entscheidung und Verhalten von `onTimeout` auf Android 15+
   prüfen.

## Aktive Risiken

- **Doppelte Wahrheit bei der Dokumentation:** Der Arbeitsbaum entfernt 65
  Wiki-Dateien; dieser Stand ist nicht committet. Ein versehentlicher
  `git checkout` würde die Löschung rückgängig machen.
- **Klartext-Token:** `access_token`, `refresh_token`, `client_id` und
  `client_secret` liegen in einem app-privaten DataStore, nicht im Keystore.
  Backups sind per `backup_rules.xml` und `data_extraction_rules.xml`
  ausgeschlossen.
- **Sicherheitskritische Schreibpfade:** Ein HTTP-Commit darf nie als
  wiederholbar gelten. Die Typen `CheckInAcceptedException` und
  `StatusWriteAcceptedException` tragen diese Invariante.
- **Externe, unversionierte Schnittstellen:** DBF/IRIS v3, OSRM und
  bahnhof.de können sich ohne Vorwarnung ändern; Parser und Tests sind
  entsprechend defensiv.

## Verwandte Seiten

- [Überblick](overview.md)
- [Tasks](tasks.md)
- [Log](log.md)
- [Architektur](architecture/ueberblick.md)
