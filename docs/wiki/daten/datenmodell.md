# Daten: Überblick

## Zweck

Erklärt, wie Daten im Netzwerk modelliert und lokal gespeichert sind.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/data/model/Models.kt`
- `app/src/main/kotlin/de/traewelling/app/data/local/StatusEntity.kt`

## Modelle (Retrofit / Gson)

Alle Felder sind mit `@SerializedName` annotiert, um fehlerhaftes Mapping zu vermeiden.

### Auth-Modelle

| Modell               | Beschreibung                                                      |
| -------------------- | ----------------------------------------------------------------- |
| `OAuthTokenResponse` | Token-Antwort mit accessToken, refreshToken, tokenType, expiresIn |

### User-Modelle

| Modell         | Beschreibung                                                                                                                       |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `UserResponse` | Wrapper für User-Daten                                                                                                             |
| `User`         | Nutzerdaten: id, uuid, username, displayName, profilePicture, bio, totalDistance, totalDuration, points, following, muted, blocked |

### Status-Modelle

| Modell                 | Beschreibung                                                                                                                                                                   |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `StatusListResponse`   | Wrapper für Liste von Statusen mit PaginationLinks                                                                                                                             |
| `SingleStatusResponse` | Wrapper für einzelnen Status                                                                                                                                                   |
| `Status`               | Check-in-Status: id, body, createdAt, likes, liked, visibility, business, user, checkin, event, tags                                                                           |
| `StatusUser`           | User-Kurzform für Status                                                                                                                                                       |
| `CheckinInfo`          | Check-in-Details: hafasId, category, mode, lineName, distanceMeters, points, duration, origin, destination, operator, trip, number, routeColor, manualDeparture, manualArrival |
| `StopOperator`         | Betreiber-Info: id, name, uuid                                                                                                                                                 |

### Station-Modelle

| Modell                  | Beschreibung                                                                                                                                                                                                                                                      |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `TrainStation`          | Bahnhof: id, ibnr, name, rilIdentifier, latitude, longitude                                                                                                                                                                                                       |
| `StopStation`           | Halt mit Zeitdaten und optionalen Koordinaten: id, name, rilIdentifier, evaIdentifier, arrival, departure, platform, cancelled, isArrivalDelayed, isDepartureDelayed, arrivalReal, departureReal, arrivalPlatformReal, departurePlatformReal, latitude, longitude |
| `StationSearchResponse` | Liste von TrainStation                                                                                                                                                                                                                                            |
| `DepartureResponse`     | Liste von DepartureTrip                                                                                                                                                                                                                                           |

### Trip-Modelle

| Modell              | Beschreibung                                                                        |
| ------------------- | ----------------------------------------------------------------------------------- |
| `DepartureTrip`     | Abfahrt: tripId, line, direction, plannedWhen, realWhen, delay, platform, cancelled |
| `HafasLine`         | HAFAS-Linieninfo: name, fahrtNr, product, mode, operator                            |
| `TripResponse`      | Wrapper für TripDetails                                                             |
| `TripDetails`       | Trip mit Stopovers: id, lineName, category, stopovers                               |
| `StopoversResponse` | Map von tripId zu StopStation-Liste                                                 |

### Check-in-Modelle

| Modell                | Beschreibung                                                                                                                 |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `TravelReason`        | Enum für den Reisegrund beim Check-in: PRIVATE=0, BUSINESS=1, COMMUTE=2                                                      |
| `CheckInRequest`      | Request für Check-in: tripId, lineName, startStationId, destinationStationId, departure, arrival, body, business, visibility |
| `CheckInResponse`     | Antwort mit Status und Points                                                                                                |
| `CheckInResult`       | Enthält Status und CheckInPoints                                                                                             |
| `UpdateStatusRequest` | PUT-Request für Status-Updates                                                                                               |

### Statistik-Modelle

| Modell               | Beschreibung                                      |
| -------------------- | ------------------------------------------------- |
| `StatisticsResponse` | Wrapper für StatisticsData                        |
| `StatisticsData`     | Statistiken: categories, operators, time, purpose |
| `StatEntry`          | Kategorie/Betreiber-Stat: name, count, duration   |
| `StatDay`            | Tagesstatistik: date, count, duration             |

### Notification-Modelle

| Modell                     | Beschreibung                                                                      |
| -------------------------- | --------------------------------------------------------------------------------- |
| `NotificationListResponse` | Liste mit Pagination                                                              |
| `Notification`             | notification: id, type, lead, notice, link, readAt, createdAt, createdAtForHumans |

### Pagination

| Modell            | Beschreibung                          |
| ----------------- | ------------------------------------- |
| `PaginationLinks` | first, last, prev, next URLs          |
| `PaginationMeta`  | currentPage, lastPage, total, perPage |

## Wichtige Extensions

### List<StopStation>.deduplicate()

```kotlin
fun List<StopStation>.deduplicate(): List<StopStation>
```

Dedupliziert aufeinanderfolgende Haltestellen mit gleichen geplanten Zeiten. Wird bei Trip-Details und Stopovers verwendet, da die API manchmal Duplikate liefert (z.B. "Nettetal Kaldenkirchen Bf" und "Kaldenkirchen").

### StopoversResponse.allStopovers()

```kotlin
fun StopoversResponse.allStopovers(): List<StopStation>
```

Flacht die Map von tripId zu StopStation-Liste in eine flache Liste.

## Manuelle Zeitedits

Die API unterstützt manuelle Korrekturen von Abfahrts-/Ankunftszeiten:

- `manualDeparture`: Manuell korrigierte Abfahrtszeit
- `manualArrival`: Manuell korrigierte Ankunftszeit

Diese werden im CheckInInfo-Modell gespeichert und von TripTrackingService bei der Anzeige berücksichtigt.

## Stopover-Koordinaten

`StopStation` enthält optionale Felder `latitude` und `longitude`. Diese werden vom `TripTrackingService` verwendet, um während einer aktiven Fahrt eine GPS-basierte ETA zum nächsten Halt zu schätzen. Wenn die API für Stopovers keine Koordinaten liefert oder kein aktueller Standort verfügbar ist, nutzt die App weiterhin die regulären API-/Fahrplanzeiten.

## Reisegrund

Der Reisegrund wird beim Check-in im Feld `business` übertragen. Das Android-Modell verwendet dafür `TravelReason` mit den API-Werten `0` (Privat), `1` (Geschäftlich) und `2` (Arbeitsweg). Standard ist `TravelReason.PRIVATE`.

## Verwandte Seiten

- [Datenbank](./datenbank.md)
- [Schemas](./schemas.md)
- [Check-in](../module/checkin.md)
