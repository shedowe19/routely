# Architektur: Datenfluss

## Zweck

Erklärung, wie Daten durch die App fließen.

## Datenfluss

1. Ein Compose-Screen triggert eine Aktion im `ViewModel` (z.B. User drückt "Check-in").
2. Das `ViewModel` aktualisiert seinen `StateFlow` (z.B. `isLoading = true`) und ruft das entsprechende `Repository` auf.
3. Das `Repository` entscheidet, ob Daten aus der lokalen Datenbank (`Room`) oder über das Netzwerk (`Retrofit`) bezogen werden.
4. Bei Netzwerkanfragen führt das `TraewellingApiService` den HTTP-Request aus und liefert Response-Modelle (Gson serialisiert).
5. Das `Repository` reicht die Daten an das `ViewModel` zurück, ggf. nach einer Zwischenspeicherung in der Datenbank (z.B. `StatusDao`).
6. Das `ViewModel` aktualisiert den `StateFlow` mit den neuen Daten.
7. Der Compose-Screen (View) beobachtet den `StateFlow` (`collectAsStateWithLifecycle()`) und recomposed sich mit den neuen Daten.

## Verwandte Seiten

- [Architektur Überblick](./ueberblick.md)
