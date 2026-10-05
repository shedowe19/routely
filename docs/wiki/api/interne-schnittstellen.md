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
- `searchStations(query)` / `getNearbyStations(lat, lon)` - Textsuche beziehungsweise Stationssuche über `GET /api/v1/stations`; der zweite Aufruf berechnet aus Breite/Länge die Query-Parameter `min_lat`, `max_lat`, `min_lon`, `max_lon` für eine Bounding-Box.
- `getStationDepartures(stationId)` - Abfahrten
- `getTrip(hafasTripId, lineName)` - Trip-Details
- `checkIn(request)` - Check-in inklusive Reisegrund (`CheckInRequest.business`)
- `getStatistics()` - Statistiken
- `getCurrentUser()` / `getUserProfile(username)` / `getUserStatuses(username, page)` - Profile
- `searchUsers(query)` - Benutzer-Suche
- `getStatusDetail(statusId)` / `getStopovers(tripId)` - Status-Details
- `followUser(id)` / `unfollowUser(id)` - Follow
- `getNotifications(page)` / `getUnreadNotificationCount()` / `markNotificationRead(id)` / `markAllNotificationsRead()` - Notifications

Bei einem Check-in-Konflikt (HTTP 409) wertet das Repository `data.conflicts` als Liste vollständiger `Status`-Objekte aus. Fehlermeldungen hängen nicht von den veralteten Feldern `message.status_id` und `message.lineName` ab. Der Serverfehler wird nicht mehr als unverarbeitetes JSON in die Check-in-Oberfläche übernommen.

Stations- und Zeitdaten werden über die Helfer des Datenmodells gelesen: `stationId`, `stationName`, `stationIdentifier(type)`, `effectiveArrival`, `effectiveDeparture` und `matchesStopover(other)`. Damit verwenden UI, ViewModels und Tracking denselben API-Vertrag.

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
- [Datenmodell](../daten/datenmodell.md)
- [Träwelling-API-Kompatibilität](./traewelling-kompatibilitaet.md)
