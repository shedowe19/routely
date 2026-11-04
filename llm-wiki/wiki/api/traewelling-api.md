# Träwelling-API

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite dokumentiert die tatsächlich genutzten HTTP-Verträge zum
Träwelling-Server. Sie ist keine Kopie der offiziellen API-Dokumentation,
sondern beschreibt, was dieser Client aufruft und welche Annahmen er trifft.

## Basis und Authentifizierung

- Basis-URL aus `server_url`, normalisiert durch `ApiServerUrl.normalize`:
  HTTPS Pflicht, keine Zugangsdaten, keine Query, kein Fragment, optionaler
  Unterpfad bleibt erhalten.
- Authentifizierung per `Authorization: Bearer <access_token>`.
- Login: Der Nutzer fügt einen API-Token ein. `AuthRepository.loginWithToken`
  validiert ihn über `GET api/v1/auth/user` und speichert erst danach die
  Session.
- Logout: lokale Session zuerst löschen, danach best-effort
  `POST api/v1/auth/logout`.

## Genutzte Endpunkte

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `api/v1/auth/user` | Token validieren, aktuellen Nutzer laden |
| POST | `api/v1/auth/logout` | Session serverseitig beenden |
| GET | `api/v1/dashboard?page` | persönlicher Feed (Seite 1 gecacht) |
| GET | `api/v1/statuses?page` | globaler Feed (Seite 1 gecacht) |
| GET | `api/v1/status/{id}` | einzelner Status |
| PUT | `api/v1/status/{id}` | Status bearbeiten |
| DELETE | `api/v1/status/{id}` | Status löschen |
| POST | `api/v1/status/{id}/like` | Like setzen |
| DELETE | `api/v1/status/{id}/like` | Like entfernen |
| GET | `api/v1/trains/station/autocomplete/{query}` | Stationssuche |
| GET | `api/v1/stations?min_lat&max_lat&min_lon&max_lon` | Stationen im Umkreis |
| GET | `api/v1/station/{id}/departures?when` | Abfahrten |
| GET | `api/v1/trains/trip?hafasTripId&lineName` | Fahrt und Haltestellen |
| POST | `api/v1/trains/checkin` | Check-in |
| GET | `api/v1/statistics` | Profilstatistik |
| GET | `api/v1/user/{username}` | Profil |
| GET | `api/v1/user/{username}/statuses?page` | Status eines Nutzers |
| GET | `api/v1/user/search/{query}` | Nutzersuche |
| GET | `api/v1/stopovers/{tripId}` | Haltestellen einer Fahrt |
| POST | `api/v1/user/{id}/follow` | Folgen |
| DELETE | `api/v1/user/{id}/follow` | Entfolgen |
| GET | `api/v1/notifications?page` | Benachrichtigungen |
| GET | `api/v1/notifications/unread/count` | ungelesene Anzahl |
| PUT | `api/v1/notifications/read/{id}` | eine Meldung lesen |
| PUT | `api/v1/notifications/read/all` | alle Meldungen lesen |
| GET | `api/v1/polyline/{statusId}` | native Streckengeometrie |

Quelle: `data/api/TraewellingApiService.kt`, Aufrufstellen in
`data/repository/TraewellingRepository.kt`.

## OAuth-Endpunkte (vorhanden, aber ungenutzt)

`OAuthApiService` in derselben Datei definiert:

- `POST oauth/token` mit `grant_type=authorization_code`, `client_id`,
  `client_secret`, `redirect_uri`, `code`, `code_verifier`.
- `POST oauth/token` mit `grant_type=refresh_token`, `client_id`,
  `client_secret`, `refresh_token`.

`AuthRepository.exchangeCodeForToken` und `refreshAccessToken` existieren, werden
aber von keinem Produktivpfad aufgerufen. Der Token-Login speichert weder
`refresh_token` noch `client_id`. Siehe
[Offene Fragen](../questions/offene-fragen.md).

## Datenverträge

- Gson mappt camelCase; `@SerializedName` nur bei abweichenden Namen
  (`lock_visibility`, `destinationId`, `manualArrival`, …).
- `Status.checkin` ist das Zugobjekt, nicht `train`.
- `TrainStation.ibnr` kommt aus dem Identifier-Typ `de_db_ibnr`,
  `rilIdentifier` aus `de_db_ril100`.
- `StopStation.uuid` identifiziert den Halt, `station.id` die Station. Ein
  Halt gilt nur bei gleicher UUID oder gleicher Station plus identischen
  Planzeiten als derselbe Besuch.
- `StopoversResponse.data` ist eine Map `tripId → Haltestellen`.
- `StatusVisibility`: 0 PUBLIC, 1 UNLISTED, 2 FOLLOWERS, 3 PRIVATE,
  4 AUTHENTICATED, 5 TRUSTED.
- `TravelReason`: 0 PRIVATE, 1 BUSINESS, 2 COMMUTE.

Quelle: `data/model/Models.kt`, `data/model/StatusVisibility.kt`.

## Fehler- und Schreibsemantik

- Ein Check-in mit HTTP 409 wird als Konflikt mit den vorhandenen Status
  gemeldet (`CheckInConflictException`).
- Erfolgreicher POST ohne brauchbare ID ⇒ `CheckInAcceptedException`
  (nicht wiederholbar).
- Erfolgreicher PUT ohne Body oder mit fremder ID ⇒
  `StatusWriteAcceptedException` (nicht wiederholbar).
- Lesefehler bei 401/403 oder kaputtem JSON lösen keinen Offline-Fallback aus.
- Fehlerklassen sind nicht einheitlich: einige Methoden werfen
  `HttpException`, andere `IllegalStateException` mit Code im Text.

## Risiken

- Die API ist nicht versioniert im Client gekapselt; neue Felder werden still
  ignoriert, fehlende Pflichtfelder fallen erst im Modell oder Test auf.
- `getNearbyStations` ist im Retrofit-Interface deklariert
  (`api/v1/trains/station/nearby`), wird aber nicht verwendet. Der Repository-
  Pfad nutzt stattdessen `api/v1/stations` mit einer festen Bounding-Box.
- Für Status-Detail werden Status und Stopovers separat gelesen; nur die
  Revisionsprüfung verhindert gemischte Stände.

## Verwandte Seiten

- [Live-Datenquellen](live-datenquellen.md)
- [Datenmodell](../data/datenmodell.md)
- [Datenzugriff](../modules/datenzugriff.md)
- [Index](../index.md)
