# Architektur: Module

## Zweck

Übersicht über die internen App-Packages/Schichten.

## Wichtige Packages

- `de.traewelling.app.data`: Beinhaltet API (Retrofit), lokale DB (Room), Repositories und Models.
- `de.traewelling.app.data.sev`: Öffentlicher Bahnhofskartenabruf, HTML-/GeoJSON-Parser, Besuchs-/Datums-/Richtungsauflösung und begrenzte asynchrone Anreicherung ([SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)).
- `de.traewelling.app.data.routing`: Zwei getrennte Geometriequellen: sessiongebundener nativer Träwelling-Streckenverlauf und anonymer öffentlicher OSRM-Abruf für SEV. Parser und begrenzte RAM-Caches prüfen geordnete Besuchs-/Endpunktbindung; [GPS-Zeitauswertung](../module/gps-zeiten.md) und gerichtete Trackinghilfen projizieren lokal.
- `de.traewelling.app.ui`: Beinhaltet Compose Navigation, Screens und Theme/Components.
- `de.traewelling.app.viewmodel`: MVVM ViewModels für jeden Screen.
- `de.traewelling.app.service` & `widget`: Hintergrundservices (z.B. LocationTracking/TripTracking) und Homescreen Widgets.

## Flutter-Migrationsstand vom 07.10.2026

Die plattformübergreifende Anwendung liegt unter `flutter/`; der bisherige Kotlin-/Compose-Quellstand unter `app/` bleibt eine Verhaltensreferenz. Aktuelle Schichten, Funktionsvergleich und Plattformgrenzen stehen in der [Flutter-Architektur](../architektur/flutter-migration.md), Werkzeugketten und Releasepfade unter [Flutter-Entwicklung](../entwicklung/flutter.md). Die übrigen Kotlin-Dateipfade auf dieser Seite beschreiben den erhaltenen Ausgangsstand.

## Verwandte Seiten

- [Module Übersicht](../module/README.md)
