# Architektur: Überblick

## Zweck

Beschreibt die grobe Systemarchitektur und Schichten der App.

## Architektur

Die App folgt dem Model-View-ViewModel (MVVM) Muster:

- **UI Layer**: Besteht aus Jetpack Compose Screens (`ui/screens`) und wiederverwendbaren Komponenten (`ui/components`). Sie konsumieren StateFlows aus den ViewModels.
- **Presentation Layer**: ViewModels (`viewmodel/`) verwalten den UI State (`StateFlow`) und behandeln Business-Logik und API-Aufrufe mithilfe von Coroutines.
- **Data Layer**: Repositories (`data/repository`) abstrahieren die Datenquellen (Network via Retrofit, Local via Room/Preferences).
- **Model**: DTOs und Datenbank-Entitäten (`data/model`, `data/local`).

## Flutter-Migrationsstand vom 07.10.2026

Die plattformübergreifende Anwendung liegt unter `flutter/`; der bisherige Kotlin-/Compose-Quellstand unter `app/` bleibt eine Verhaltensreferenz. Aktuelle Schichten, Funktionsvergleich und Plattformgrenzen stehen in der [Flutter-Architektur](../architektur/flutter-migration.md), Werkzeugketten und Releasepfade unter [Flutter-Entwicklung](../entwicklung/flutter.md). Die übrigen Kotlin-Dateipfade auf dieser Seite beschreiben den erhaltenen Ausgangsstand.

## Verwandte Seiten

- [Datenfluss](./datenfluss.md)
- [Module](./module.md)
