# Entscheidungen

## Zweck

Übersicht technischer Entscheidungen (ADRs).

## Liste der Entscheidungen

- [07.10.2026: GPS-Prognose auf gemeinsamem SEV-Restweg](./2026-10-07-sev-gemeinsamer-restweg.md)
- [07.10.2026: Aktive Fahrtrevision und getrennte GPS-Ereignisse](./2026-10-07-aktive-fahrt-und-beobachtungen.md)

- [06.10.2026: Nachreview-Korrekturen und Versionsclaims](./2026-10-06-nachreview-korrekturen.md)
- [06.10.2026: GPS-, Mutations- und Releasezustände](./2026-10-06-befundkorrekturen.md)

- [28.04.2026: App-Umbenennung zu Routely](./2026-04-28-app-rename-routely.md)
- [29.04.2026: Dark Mode und Settings](./2026-04-29-dark-mode-und-settings.md)
- [06.10.2026: Native Streckenverläufe für die GPS-Begleitung](./2026-10-06-native-streckenverlaeufe.md)

Weitere kleinere Architektur-Entscheidungen sind in [Architektur Entscheidungen](../architektur/entscheidungen.md) dokumentiert.

## Flutter-Migrationsstand vom 07.10.2026

Die plattformübergreifende Anwendung liegt unter `flutter/`; der bisherige Kotlin-/Compose-Quellstand unter `app/` bleibt eine Verhaltensreferenz. Aktuelle Schichten, Funktionsvergleich und Plattformgrenzen stehen in der [Flutter-Architektur](../architektur/flutter-migration.md), Werkzeugketten und Releasepfade unter [Flutter-Entwicklung](../entwicklung/flutter.md). Die übrigen Kotlin-Dateipfade auf dieser Seite beschreiben den erhaltenen Ausgangsstand.

## Verwandte Seiten

- [Architektur Entscheidungen](../architektur/entscheidungen.md)
- [ADR Template](./adr-template.md)
- [App-Umbenennung zu "Routely"](./2026-04-28-app-rename-routely.md)
- [Dark Mode und Settings](./2026-04-29-dark-mode-und-settings.md)
