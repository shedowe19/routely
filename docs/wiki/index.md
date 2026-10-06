# Projekt-Wiki

Dieses Wiki ist das Langzeitgedächtnis des Projekts. Es erklärt Architektur, Module, Features, Schnittstellen, Datenmodelle, Konfiguration und technische Entscheidungen.

Code ist die Quelle der Wahrheit. Das Wiki erklärt die Quelle der Wahrheit.

Der Main-Audit vom 06.10.2026 ergänzt die bestehenden Verträge für [Sitzungen](./module/auth.md), [Feedcache](./module/feed.md), [GPS-Besuchsidentität](./module/gps-zeiten.md), [SEV-Zuordnung](./module/sev-haltestellen.md) und [Android-Lebenszyklus](./module/trip-tracking.md). Prüfziele und tatsächlich ausgeführte CI-Läufe bleiben unter [Tests](./entwicklung/tests.md) getrennt; offene Geräteprüfungen stehen unter [Offene Fragen](./offene-fragen.md).

Die zwölf zusätzlichen Befunde des [Nachreviews von Main f406bad](./entwicklung/main-review-2026-10-06.md) sind vollständig korrigiert und erneut unabhängig gegengeprüft. Historische Nachweise bleiben erhalten; aktuelle Schutzverträge und ausgeführte Ergebnisse stehen bei den Modulen und unter [Tests](./entwicklung/tests.md).

## Projekt

- [Übersicht](./projekt/ueberblick.md)
- [Ziele](./projekt/ziele.md)
- [Begriffe](./projekt/begriffe.md)

## Architektur

- [Überblick](./architektur/ueberblick.md)
- [Datenfluss](./architektur/datenfluss.md)
- [Module](./architektur/module.md)
- [Entscheidungen](./architektur/entscheidungen.md)
- [Externe Abhängigkeiten](./architektur/externe-abhaengigkeiten.md)

## Entwicklung

- [Setup](./entwicklung/setup.md)
- [Lokale Entwicklung](./entwicklung/lokale-entwicklung.md)
- [Tests](./entwicklung/tests.md)
- [Erneuter Main-Review nach der Streckenmigration](./entwicklung/main-review-2026-10-06.md)
- [Build](./entwicklung/build.md)
- [Deployment](./entwicklung/deployment.md)

## Module

- [Module Übersicht](./module/README.md)

## Features

- [Features Übersicht](./features/README.md)

## API und Schnittstellen

- [API Überblick](./api/ueberblick.md)
- [Interne Schnittstellen](./api/interne-schnittstellen.md)
- [Externe Schnittstellen](./api/externe-schnittstellen.md)
- [Träwelling-API-Kompatibilität](./api/traewelling-kompatibilitaet.md)

## Daten

- [Datenmodell](./daten/datenmodell.md)
- [Datenbank](./daten/datenbank.md)
- [Schemas](./daten/schemas.md)
- [Migrationen](./daten/migrationen.md)

## Konfiguration

- [Umgebungsvariablen](./konfiguration/umgebungsvariablen.md)
- [Config-Dateien](./konfiguration/config-dateien.md)
- [Secrets und Sicherheit](./konfiguration/secrets-und-sicherheit.md)
- [PreferencesManager](./konfiguration/preferences-manager.md)

## Entscheidungen

- [06.10.2026: Nachreview-Korrekturen und Versionsclaims](./entscheidungen/2026-10-06-nachreview-korrekturen.md)
- [06.10.2026: GPS-, Mutations- und Releasezustände](./entscheidungen/2026-10-06-befundkorrekturen.md)

- [Entscheidungen](./entscheidungen/README.md)
- [ADR Template](./entscheidungen/adr-template.md)
- [Native Bahn-/Tram-Streckenverläufe](./entscheidungen/2026-10-06-native-streckenverlaeufe.md)

## UI

- [Screens](./ui/screens.md)
- [Komponenten](./ui/komponenten.md)
- [Theme](./ui/theme.md)

## Weitere Seiten

- [Glossar](./glossar.md)
- [Offene Fragen](./offene-fragen.md)
- [Wiki-Pflege](./wiki-pflege.md)

## Spezifische Module

- [Auth](./module/auth.md)
- [Auth-PKCE](./module/auth-pkce.md)
- [Check-in](./module/checkin.md)
- [Feed](./module/feed.md)
- [Notifications](./module/notifications.md)
- [Profile](./module/profile.md)
- [StatusDetail](./module/status-detail.md)
- [TripTracking](./module/trip-tracking.md)
- [GPS-Zeiten](./module/gps-zeiten.md)
- [SEV-Ersatzhaltestellen](./module/sev-haltestellen.md)
- [Fahrterkennung](./module/ride-recognition.md)
- [Fahrtänderungen](./module/trip-changes.md)
- [Reisefortschritt und Live Updates](./module/trip-progress.md)
- [UserProfile](./module/user-profile.md)
- [UserSearch](./module/user-search.md)
- [Widget](./module/widget.md)
- [Settings](./module/settings.md)
- [Points-System](./features/points-enabled.md)

## Verwandte Seiten

- [Modulübersicht](./module/README.md)
- [Features](./features/README.md)
- [Wiki-Pflege](./wiki-pflege.md)
