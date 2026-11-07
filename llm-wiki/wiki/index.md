# Wiki-Index

Routely ist eine Android-App (Kotlin, Jetpack Compose) für die
Träwelling-Community: Check-in, Live-Begleitung mit GPS und Echtzeit,
Statusmeldungen, Benachrichtigungen und Widget. Dieses Wiki ist das dauerhafte
Projektgedächtnis; es erklärt den Code, ersetzt ihn aber nicht.

## Hier starten

1. [Projektüberblick](overview.md) — Was, für wen, Stack, Befehle, Status.
2. [Aktueller Projektstand](current-project-state.md) — Was läuft, was fehlt.
3. [Tasks](tasks.md) — offene, prüfbare Arbeit.
4. [Log](log.md) — was zuletzt getan wurde.
5. [Architekturüberblick](architecture/ueberblick.md) — Schichten und Grenzen.

## Architektur

- [Architekturüberblick](architecture/ueberblick.md) — Schichten, Bausteine, bewusste Grenzen.
- [Datenfluss](architecture/datenfluss.md) — Lesen, Schreiben, Tracking, Anreicherung.
- [Externe Abhängigkeiten](architecture/externe-abhaengigkeiten.md) — Träwelling, DBF, OSRM, bahnhof.de und ihr Ausfallverhalten.

## API und Schnittstellen

- [Träwelling-API](api/traewelling-api.md) — genutzte Endpunkte, Auth, Fehler- und Schreibsemantik.
- [Live-Datenquellen](api/live-datenquellen.md) — DBF/IRIS, Polyline, OSRM, SEV: Grenzen und Provenienz.

## Module

- [Datenzugriff und Repositories](modules/datenzugriff.md) — Cache, Mutations-Ledger, Revisionsschutz.
- [Tracking-Service](modules/tracking-service.md) — Lebenszyklus, GPS-Pipeline, Notification, Widget.
- [GPS-Zeiten](modules/gps-zeiten.md) — Quellenreihenfolge und lokale Prognosen.
- [Fahrterkennung](modules/fahrterkennung.md) — Vorschlagsdienst und Matching-Kriterien.
- [Check-in](modules/checkin.md) — Station, Fahrt, Ziel, genau ein POST.
- [Feed und Statusdetails](modules/feed-und-status.md) — Likes, Timeline, Bearbeiten, Löschen.
- [Benachrichtigungen](modules/benachrichtigungen.md) — Träwelling-Meldungen, Lesen, Link-Auflösung.
- [Auth und Sitzung](modules/auth-und-sitzung.md) — Token-Login, Revisionen, OAuth-Lücke.
- [Storage-Recovery](modules/storage-recovery.md) — blockierte Oberfläche, Zustimmung, Probe-Schreiben.
- [SEV-Haltestellen](modules/sev-haltestellen.md) — Ersatzbus-Erkennung und bahnhof.de-Karten.
- [Streckenverläufe](modules/streckenverlaeufe.md) — native Polylinien und OSRM.
- [Einstellungen](modules/einstellungen.md) — Preferences, TTS, Theme, Storage-Status.
- [Profile und Nutzersuche](modules/profil-und-nutzer.md) — eigene/fremde Profile, Folgen, Suche.

## Oberfläche

- [UI und Design](frontend/ui-und-design.md) — Navigation, Screens, Theme, Formatierung.

## Daten

- [Datenmodell](data/datenmodell.md) — DTOs, Identitätsregeln, Enums.
- [Lokale Speicherung](data/lokale-speicherung.md) — Room, DataStore, Aufbewahrung, Backup-Ausschluss.

## Sicherheit

- [Sicherheit und Datenschutz](security/sicherheit.md) — HTTPS, Token-Speicherung, Datensparsamkeit, Secrets.

## Betrieb

- [Build und Release](infrastructure/build-und-release.md) — Gradle, CI, Release-Guard, Signatur.
- [Teststrategie](testing/teststrategie.md) — was JVM-Tests beweisen und was nicht.

## Entscheidungen

- [Entscheidungsindex](decisions/README.md) — ADR-Liste und Regeln.
- [ADR-Vorlage](decisions/adr-template.md)
- [2026-10-06: Native Streckenverläufe](decisions/2026-10-06-native-streckenverlaeufe.md)
- [2026-10-10: Anonyme DBF/IRIS-Echtzeit](decisions/2026-10-10-anonyme-dbf-live-daten.md)
- [2026-11-03: Sichere Storage-Recovery](decisions/2026-11-03-storage-recovery.md)

## Offene Fragen

- [Offene Fragen](questions/offene-fragen.md) — OAuth, Cache-Aufbewahrung, Ziel-SDK, Gerätetests, Lizenz.

## Widersprüche

- [Widerspruchsindex](contradictions/README.md)
- [Dokumentationspfade zeigen auf gelöschtes docs/wiki](contradictions/2026-11-04-dokumentationspfade.md)

## Analysen

Noch keine. Reusable Antworten, die mehrere Seiten synthetisieren, gehören nach
`analyses/YYYY-MM-DD-kurzer-titel.md` und werden hier verlinkt.

## Quellen

Kein `raw/`-Ordner. Die Initialisierung hat die frühere Dokumentation unter
`docs/wiki/` auf ausdrückliche Entscheidung nicht als Quelle übernommen.

## Zuletzt geändert

- [Changelog](changelog.md) — 2026-11-04: Initialisierung.
- [Log](log.md) — 2026-11-04: Inventur und Aufbau.

## Guardrails

- Quellcode ist die Wahrheit; dieses Wiki erklärt ihn nur.
- Keine Secrets, Tokens oder Kontodaten dokumentieren — nur Pfade und
  Variablennamen.
- Ein HTTP-Commit wird nie als wiederholbar behandelt.
- Ohne eindeutige Daten wird nichts geschätzt oder geraten.
- Korrupte Preferences werden nie stillschweigend gelöscht.
- Physische Geräteprüfungen sind nicht durch Unit-Tests ersetzt.
