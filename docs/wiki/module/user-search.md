# Modul: UserSearch

## Zweck

Ermöglicht die Suche nach anderen Träwelling-Nutzern anhand ihres Benutzernamens.

## Kontext

Aufgerufen vom Feed-Screen über die Suchen-Schaltfläche. Navigiert nach Auswahl eines Nutzers zum UserProfileScreen.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/UserSearchScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/UserSearchViewModel.kt`

## Verhalten

### Suchprozess

1. Nutzer gibt Benutzername ein
2. Nach 350ms Debounce wird `repo.searchUsers(query)` aufgerufen
3. Ergebnisse werden als LazyColumn mit kartenartigen UserListItems angezeigt

Jede neue Eingabe beendet den bisherigen Suchauftrag, leert alte Treffer und erhöht die Suchgeneration. Selbst eine verspätete API-Antwort darf nur zur weiterhin aktuellen Eingabe veröffentlicht werden. Leere Eingabe startet keine Anfrage; Sitzungwechsel beendet das ViewModel über den [Auth-Store](./auth.md).

### UI-Darstellung

- Suchzustände, leere Ergebnisse und Ladezustände werden via `StateMessage` angezeigt
- Treffer werden als abgerundete Cards mit Avatar, Anzeigename, Benutzername und Chevron dargestellt

### UI-Zustand (UserSearchUiState)

| Feld            | Typ        | Beschreibung          |
| --------------- | ---------- | --------------------- |
| `query`         | String     | Aktueller Suchbegriff |
| `searchResults` | List<User> | Suchergebnisse        |
| `isLoading`     | Boolean    | Ladezustand           |
| `error`         | String?    | Fehlermeldung         |

## Abhängigkeiten

- **TraewellingRepository**: `searchUsers(query)` für API-Aufruf
- **StateMessage**: Einheitliche UI für Such-, Lade- und Empty-States

## Offene Fragen

- Keine spezifischen aktuell.

## Flutter-Umsetzung

`flutter/lib/features/users/` schützt die Suche durch Anfragegenerationen und kontogebundene Controller. Weitere Details stehen in der [Flutter-Architektur](../architektur/flutter-migration.md). Die bisherigen Kotlin-Verträge bleiben die Verhaltensreferenz.

## Verwandte Seiten

- [UserProfile](./user-profile.md)
- [Feed](./feed.md)
