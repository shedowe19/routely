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

`apiResult` reicht `CancellationException` unverändert weiter; ein abgebrochener Auftrag wird weder als gewöhnlicher API-Fehler noch als Offline-Erfolg verarbeitet. Feed-Abrufe verwenden einen atomaren `AuthSession`-Snapshot und prüfen ihn vor Cache-/Antwortübernahme erneut. Der [Feed-Cache](../module/feed.md) ist nach Server, Zugangsdaten und Feedart getrennt.

Stations- und Zeitdaten werden über die Helfer des Datenmodells gelesen: `stationId`, `stationName`, `stationIdentifier(type)`, `effectiveArrival`, `effectiveDeparture` und `matchesStopover(other)`. Damit verwenden UI, ViewModels und Tracking denselben API-Vertrag.

### AuthRepository

Authentifizierungs-Operationen.

```kotlin
class AuthRepository(prefs: PreferencesManager)
```

**Methoden:**

- `loginWithToken(serverUrl, token)` - HTTPS-Server und Token prüfen, danach vollständige Session atomar speichern
- `validateCurrentSession()` - gespeicherte Session prüfen; nur 401/403 löschen genau diese noch aktuelle Session
- `exchangeCodeForToken(...)` - OAuth Token Exchange
- `refreshAccessToken()` - Token erneuern
- `fetchAndSaveCurrentUser()` - User laden
- `logout()` - Abmelden

OAuth-Methoden sind vorhandene Helfer, kein angebundener Login-/Auto-Refresh. Vergleichende Session-Schreiboperationen in `AuthSessionStore` verhindern, dass verspätete Antworten eine neuere Anmeldung überschreiben oder löschen. Logout entfernt die lokale Sitzung vor dem optionalen Netzwerkaufruf.

### TransitRouteRepository

`TransitRouteRepository(session: AuthSession)` lädt den nativen Status-Linienzug über einen eigenen HTTPS-OkHttp-Client. `getRoute(request: TransitRouteRequest)` liefert optional eine besuchsgebundene `TransitRouteGeometry`; `close()` beendet gemeinsame Abrufe und löscht den sessiongebundenen RAM-Cache. Der Request enthält die vollständige eigene Besuchsfolge, keine Gerätepositionen. Dieser Geometrieabruf ist kein neuer Retrofit-/Status-PUT-Vertrag. Parser-, Cache- und Privacy-Grenzen stehen unter [Externe Schnittstellen](./externe-schnittstellen.md) und [GPS-Zeiten](../module/gps-zeiten.md).

## Retrofit Services

### TraewellingApiService

Alle API-Aufrufe zur Träwelling API.

### OAuthApiService

OAuth-Token-Austausch (Authorization Code + PKCE, Refresh Token).

## Room Database

### AppDatabase

```kotlin
@Database(entities = [StatusEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase()
```

### StatusDao

```kotlin
@Dao
interface StatusDao {
    suspend fun getStatuses(type: String): List<StatusEntity>
    suspend fun insertStatuses(statuses: List<StatusEntity>)
    suspend fun clearStatuses(type: String)
    suspend fun replaceStatuses(type: String, statuses: List<StatusEntity>)
}
```

`replaceStatuses` ersetzt eine Feedpartition innerhalb einer Room-Transaktion; der zusammengesetzte Primärschlüssel ist unter [Schemas](../daten/schemas.md) dokumentiert.

## PreferencesManager (DataStore)

Siehe [PreferencesManager](../konfiguration/preferences-manager.md)

## Verwandte Seiten

- [Datenbank](../daten/datenbank.md)
- [Datenmodell](../daten/datenmodell.md)
- [Träwelling-API-Kompatibilität](./traewelling-kompatibilitaet.md)
