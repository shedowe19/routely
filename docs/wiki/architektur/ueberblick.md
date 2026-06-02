# Architektur: Überblick

## Zweck

Beschreibt die grobe Systemarchitektur und Schichten der App.

## Architektur

Die App folgt dem Model-View-ViewModel (MVVM) Muster:

- **UI Layer**: Besteht aus Jetpack Compose Screens (`ui/screens`) und wiederverwendbaren Komponenten (`ui/components`). Sie konsumieren StateFlows aus den ViewModels.
- **Presentation Layer**: ViewModels (`viewmodel/`) verwalten den UI State (`StateFlow`) und behandeln Business-Logik und API-Aufrufe mithilfe von Coroutines.
- **Data Layer**: Repositories (`data/repository`) abstrahieren die Datenquellen (Network via Retrofit, Local via Room/Preferences).
- **Model**: DTOs und Datenbank-Entitäten (`data/model`, `data/local`).

## Verwandte Seiten

- [Datenfluss](./datenfluss.md)
- [Module](./module.md)
