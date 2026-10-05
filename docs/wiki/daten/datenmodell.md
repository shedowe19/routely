# Daten: Überblick

## Zweck

Erklärt, wie Daten im Netzwerk modelliert und lokal gespeichert sind.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/data/model/Models.kt`
- `app/src/main/kotlin/de/traewelling/app/data/local/StatusEntity.kt`
- `app/src/main/kotlin/de/traewelling/app/service/GpsJourneyTimeEstimator.kt`
- `app/src/main/kotlin/de/traewelling/app/service/JourneyTimeResolver.kt`

## Modelle (Retrofit / Gson)

Die Modelle verwenden Gson. `@SerializedName` legt abweichende JSON-Feldnamen fest; gleich benannte Felder werden direkt zugeordnet.

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
| `CheckinInfo`          | Check-in-Details mit origin, destination, operator, numerischem trip, tripUuid, Plan-/Echtzeitdaten und manuellen Zeitkorrekturen |
| `StopOperator`         | Betreiber: id als String (Legacy-Zahl oder UUID), bevorzugte uuid, name, identifiers |

### Station-Modelle

| Modell                  | Beschreibung                                                                                                                                                                                                      |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `StationIdentifier`     | Kennung mit type, identifier sowie optional name und origin |
| `TrainStation`          | Bahnhof: interne id, uuid, name, latitude, longitude, identifiers; ibnr und rilIdentifier werden aus identifiers berechnet |
| `StopStation`           | Konkreter Halt: id, uuid, verschachtelte station, arrivalPlanned/arrivalReal, departurePlanned/departureReal, Gleis- und Ausfallinformationen |
| `StationSearchResponse` | Liste von TrainStation                                                                                                                                                                                            |
| `DepartureResponse`     | Liste von DepartureTrip                                                                                                                                                                                           |

### Trip-Modelle

| Modell              | Beschreibung                                                                        |
| ------------------- | ----------------------------------------------------------------------------------- |
| `DepartureTrip`     | Abfahrt: tripId, line, direction, station, plannedWhen, realWhen (JSON when), platform, cancelled; berechnete delayMinutes |
| `HafasLine`         | Linieninfo: name, fahrtNr, product, mode; kein veraltetes line.operator |
| `TripResponse`      | Wrapper für TripDetails                                                             |
| `TripDetails`       | Trip: numerische id, uuid, Provider-tripId, lineName, category, operator, stopovers |
| `StopoversResponse` | Map von tripId zu StopStation-Liste                                                 |

### Check-in-Modelle

| Modell                | Beschreibung                                                                                                                 |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `TravelReason`        | Enum für den Reisegrund beim Check-in: PRIVATE=0, BUSINESS=1, COMMUTE=2                                                      |
| `CheckInRequest`      | Request für Check-in: tripId, lineName, startStationId, destinationStationId, departure, arrival, body, business, visibility |
| `CheckInResponse`     | Antwort mit Status und Points                                                                                                |
| `CheckInResult`       | Enthält Status und CheckInPoints                                                                                             |
| `CheckInPoints`       | Gesamtpunkte und calculation mit base, reason, distance, factor |
| `CheckInConflictResponse` / `CheckInConflictData` | HTTP-409-Envelope mit data.conflicts als Status-Liste |
| `CheckInConflictException` | Konfliktliste und verständliche Meldung mit Linie, Ziel und Status-ID |
| `UpdateStatusRequest` | PUT-Request für Status-Updates; Zielwechsel mit destinationId und destinationArrivalPlanned gemeinsam |

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

### Stationsdaten und Haltidentität

`StopStation.stationId` liest ausschließlich `station.id`. Stopover-`id` wird nie für Stationsanfragen oder Check-ins verwendet: Träwelling widmet dieses Feld nach dem 30.11.2026 zur Halt-ID um.

`stationName` liest `station.name`; nur für die Anzeige alter gecachter Statusdaten bleibt ein privater Rückfall auf das frühere JSON-Feld `name`. `stationIdentifier(type)` liest ausschließlich `station.identifiers`. `TrainStation.ibnr` nutzt `de_db_ibnr`, `TrainStation.rilIdentifier` nutzt `de_db_ril100`.

`matchesStopover(other)` vergleicht bevorzugt die Stopover-UUID. Falls eine UUID fehlt, müssen interne Station-ID und eine geplante Ankunft oder Abfahrt übereinstimmen. ISO-Zeitstempel werden als Zeitpunkte verglichen. Wiederholte Besuche derselben Station auf einer Rundfahrt bleiben dadurch unterscheidbar.

### Effektive Zeiten

`effectiveArrival` ist `arrivalReal ?: arrivalPlanned`, `effectiveDeparture` ist `departureReal ?: departurePlanned`. Diese Modellhelfer wählen vorhandene Felder, prüfen aber deren Parsebarkeit nicht. Die Legacy-Felder `arrival` und `departure` werden nicht gelesen. Der Tracking-Service berücksichtigt manuelle Einstieg-/Zielzeiten in seiner internen Route. Das Fahrtdetail behält API-Halte und manuelle Check-in-Zeiten getrennt; die Live-Anzeige verwendet `JourneyTimeResolver` statt GPS-Prognosen in Echtzeitfelder zu schreiben.

`DepartureTrip.delayMinutes` berechnet die Differenz zwischen `when` und `plannedWhen` in Minuten. Bei fehlender oder ungültiger Zeit ist der Wert `null`; das Legacy-Feld `delay` wird nicht verwendet.

### Lokale Tracking- und Zeitmodelle

| Modell/Feld | Bedeutung und Lebensdauer |
| --- | --- |
| `TrackingStop.plannedDepartureMillis` | Geplante Abfahrt des konkreten Besuchs, getrennt von `effectiveDepartureMillis`; optional für alte Cache-Routen. |
| `TrackingLiveState.gpsTimes` | Optionales prozesslokales Ergebnis der GPS-Zeitauswertung; nur passende eigene aktive Fahrt, nicht serialisiert. |
| `GpsStopTime` | Besuchsschlüssel, Station-ID, Plan-Ankunft/-Abfahrt und optionale lokale Ankunft/-Abfahrt; Flags unterscheiden beobachtet und geschätzt. |
| `GpsJourneyTimes` | Liste besuchsbezogener GPS-Zeiten, Fixzeitpunkt und Gültigkeitsende; spätestens nach 30 Sekunden unbrauchbar. |
| `JourneyTime` | Aufgelöster Anzeigezeitpunkt mit `JourneyTimeSource`, Planzeit, Quellenlabel und optionaler positiver/negativer Abweichung in Minuten. |

`JourneyTimeResolver` prüft je Ereignis gültige GPS-Zeit, manuelle Zeit, parsebare API-Echtzeit und Planzeit in dieser Reihenfolge. GPS-Matching bevorzugt UUID, sonst Station-ID und vorhandene Planzeiten mit eindeutigem Treffer. Es werden keine neuen Retrofit-, Room- oder Status-PUT-Felder eingeführt. Die Prognosen und Standortbeobachtungen bleiben RAM-Zustand; Details stehen unter [GPS-Zeiten](../module/gps-zeiten.md).

### List<StopStation>.deduplicate()

```kotlin
fun List<StopStation>.deduplicate(): List<StopStation>
```

Dedupliziert aufeinanderfolgende Halte derselben Station mit gleichen geplanten Zeiten. Unterschiedliche Stationen oder unterschiedliche vorhandene Stopover-UUIDs bleiben auch bei identischen Zeiten erhalten. Bei doppelten Einträgen wird eine vorhandene Gleisinformation beziehungsweise der kürzere Stationsname bevorzugt.

### StopoversResponse.allStopovers()

```kotlin
fun StopoversResponse.allStopovers(): List<StopStation>
```

Flacht die Map von tripId zu StopStation-Liste in eine flache Liste.

## Manuelle Zeitedits

Die API unterstützt manuelle Korrekturen von Abfahrts-/Ankunftszeiten:

- `manualDeparture`: Manuell korrigierte Abfahrtszeit
- `manualArrival`: Manuell korrigierte Ankunftszeit

Diese werden im CheckInInfo-Modell gespeichert und beim Auflösen der Anzeige berücksichtigt. Eine frische eindeutige GPS-Zeit hat in der eigenen aktiven Begleitung Vorrang; außerhalb dieser Beobachtung bleiben manuelle Zeiten vor API-/Planzeit. Das Bearbeitungsformular wird ohne GPS-Werte initialisiert.

Bei einem Zielwechsel über `UpdateStatusRequest` wird zusätzlich zur neuen internen Station-ID deren geplante Ankunft als `destinationArrivalPlanned` gesendet. Der Upstream-Request verlangt beide Felder gemeinsam. Für reine Text-, Sichtbarkeits- oder Zeitänderungen werden sie weggelassen.

## Reisegrund

Der Reisegrund wird beim Check-in im Feld `business` übertragen. Das Android-Modell verwendet dafür `TravelReason` mit den API-Werten `0` (Privat), `1` (Geschäftlich) und `2` (Arbeitsweg). Standard ist `TravelReason.PRIVATE`.

## Verwandte Seiten

- [Datenbank](./datenbank.md)
- [Schemas](./schemas.md)
- [Check-in](../module/checkin.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
