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
3. Ergebnisse werden als LazyColumn mit UserListItems angezeigt

### UI-Zustand (UserSearchUiState)

| Feld            | Typ        | Beschreibung          |
| --------------- | ---------- | --------------------- |
| `query`         | String     | Aktueller Suchbegriff |
| `searchResults` | List<User> | Suchergebnisse        |
| `isLoading`     | Boolean    | Ladezustand           |
| `error`         | String?    | Fehlermeldung         |

## Abhängigkeiten

- **TraewellingRepository**: `searchUsers(query)` für API-Aufruf

## Offene Fragen

- Keine spezifischen aktuell.

## Verwandte Seiten

- [UserProfile](./user-profile.md)
- [Feed](./feed.md)
