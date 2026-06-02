# API: Interne Schnittstellen

## Zweck

Dokumentation der internen Schnittstellen und Interfaces innerhalb der App.

## Daten-Layer

### TraewellingRepository

Zentrale Datenquelle für API-Aufrufe.

```kotlin
class TraewellingRepository(context: Context, prefs: PreferencesManager)
```

**Methoden:**

- `getDashboard(page)` / `getGlobalFeed(page)` - Feed mit Pagination
- `likeStatus(id)` / `unlikeStatus(id)` / `deleteStatus(id)` / `updateStatus(id, request)` - Status-Aktionen
- `searchStations(query)` / `getNearbyStations(lat, lon)` - Bahnhofssuche
- `getStationDepartures(stationId)` - Abfahrten
- `getTrip(hafasTripId, lineName)` - Trip-Details
- `checkIn(request)` - Check-in inklusive Reisegrund (`CheckInRequest.business`)
- `getStatistics()` - Statistiken
- `getCurrentUser()` / `getUserProfile(username)` / `getUserStatuses(username, page)` - Profile
- `searchUsers(query)` - Benutzer-Suche
- `getStatusDetail(statusId)` / `getStopovers(tripId)` - Status-Details
- `followUser(id)` / `unfollowUser(id)` - Follow
- `getNotifications(page)` / `getUnreadNotificationCount()` / `markNotificationRead(id)` / `markAllNotificationsRead()` - Notifications

### AuthRepository

Authentifizierungs-Operationen.

```kotlin
class AuthRepository(prefs: PreferencesManager)
```

**Methoden:**

- `exchangeCodeForToken(...)` - OAuth Token Exchange
- `refreshAccessToken()` - Token erneuern
- `fetchAndSaveCurrentUser()` - User laden
- `logout()` - Abmelden

## Retrofit Services

### TraewellingApiService

Alle API-Aufrufe zur Träwelling API.

### OAuthApiService

OAuth-Token-Austausch (Authorization Code + PKCE, Refresh Token).

## Room Database

### AppDatabase

```kotlin
@Database(entities = [StatusEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase()
```

### StatusDao

```kotlin
@Dao
interface StatusDao {
    suspend fun getStatuses(type: String): List<StatusEntity>
    suspend fun insertStatuses(statuses: List<StatusEntity>)
    suspend fun clearStatuses(type: String)
}
```

## PreferencesManager (DataStore)

Siehe [PreferencesManager](../konfiguration/preferences-manager.md)

## Verwandte Seiten

- [Datenbank](../daten/datenbank.md)
