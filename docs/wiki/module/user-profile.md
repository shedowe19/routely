# Modul: UserProfile

## Zweck

Zeigt Profile anderer Nutzer mit deren Check-in-Historie und Follow-Funktionalität.

## Kontext

Der Nutzer kann Profile anderer Nutzer aufrufen über den Feed oder die Benutzer-Suche. Das Profil zeigt vergangene Fahrten und ermöglicht Follow/Unfollow.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/UserProfileScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/UserProfileViewModel.kt`

## Verhalten

### Lade-Prozess

1. `loadUserProfile(username)` lädt User-Daten und Status-Historie
2. `loadMoreStatuses()` paginiert durch vergangene Check-ins
3. `toggleFollow()` folgt oder entfolgt einem Nutzer

### Follow-Logik

- Folgt bereits: `POST /api/v1/user/{id}/follow`
- Follend bereits: `DELETE /api/v1/user/{id}/follow`
- Private Profile zeigen "Angefragt" (followPending) statt "Folgen"

### Auto-Load-More

Wenn der Nutzer in der LazyColumn scrollt und noch mehr Status-Seiten verfügbar sind (via `links.next`), werden automatisch weitere geladen.

## UI-Zustand (UserProfileUiState)

| Feld              | Typ          | Beschreibung                   |
| ----------------- | ------------ | ------------------------------ |
| `isLoading`       | Boolean      | Ladezustand                    |
| `user`            | User?        | Geladene Nutzerdaten           |
| `statuses`        | List<Status> | Check-in-Historie              |
| `hasMore`         | Boolean      | Weitere Seiten verfügbar       |
| `currentPage`     | Int          | Aktuelle Seiten-Nummer         |
| `isFollowLoading` | Boolean      | Follow/Unfollow in Bearbeitung |

## Abhängigkeiten

- **TraewellingRepository**: Für API-Aufrufe (getUserProfile, getUserStatuses, followUser, unfollowUser)
- **PreferencesManager**: Für Auth-Token

## Offene Fragen

- Keine spezifischen aktuell.

## Verwandte Seiten

- [Feed](./feed.md)
- [API Überblick](../api/ueberblick.md)
