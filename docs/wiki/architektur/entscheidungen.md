# Architektur: Entscheidungen

## Zweck

Kurze Zusammenfassung technischer Entscheidungen, für die es eventuell keine eigene ADR gibt.

## Entscheidungen

- **Jetpack Compose als UI Framework**: Gewählt für moderne, deklarative UI-Entwicklung.
- **Coroutines & StateFlow**: Gewählt für asynchrone Aufgaben und reaktives State Management (statt RxJava oder LiveData).
- **Retrofit & Gson**: Gewählt für die Anbindung an die Träwelling JSON-REST-API, wobei auf `@SerializedName` zur strikten Mappung geachtet wird.
- **Room Database**: Gewählt für lokales Caching und Offline-Fähigkeit (`StatusDao`).
- **Globaler NavHost**: Core Tabs (Feed, Check-in, Meldungen, Profil) sind innerhalb eines `HorizontalPager` verpackt für seitliches Swipen, während tiefere Navigation als unabhängige Routen konzipiert ist.
- **App Rename**: Am 28.04.2026 wurde die App von "Träwelling" bzw. "Träwelling Android" zu "Routely" umbenannt. Die zugrunde liegende Plattform und API bleiben weiterhin unter dem Namen "Träwelling" bestehen. Dies dient einer klareren Unterscheidung zwischen dem Client und dem Backend. Für weitere Details, siehe [ADR App Rename](../entscheidungen/2026-04-28-app-rename-routely.md).

## Verwandte Seiten

- [Entscheidungen](../entscheidungen/README.md)
