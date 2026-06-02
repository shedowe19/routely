# API: Überblick

## Zweck

Übersicht über die Netzwerkschnittstellen. Diese Seite beschreibt grob die Routen, Endpunkte und Services für die Routely-App.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/data/api/TraewellingApiService.kt`
- `app/src/main/kotlin/de/traewelling/app/data/api/RetrofitClient.kt`

## TraewellingApiService

Zentrale Schnittstelle, definiert in `TraewellingApiService.kt`. Die App kommuniziert hauptsächlich mit den Träwelling RESTful APIs.

### Wichtigste Endpunkt-Gruppen:

**1. Check-in & Feed (Kernfunktionen)**

- **Check-In**: `POST /api/v1/trains/checkin` – Einchecken in Verkehrsmittel (benötigt Start, Ziel, Fahrt-ID; optional/gesetzt: `business` als Reisegrund).
- **Persönlicher Feed**: `GET /api/v1/dashboard` – Check-ins von Nutzern, denen man folgt.
- **Globaler Feed**: `GET /api/v1/statuses` – Öffentliche Check-ins aller Nutzer.
- **Status (Singular)**: `GET/PUT/DELETE /api/v1/status/{id}` sowie `POST/DELETE /api/v1/status/{id}/like` – Einzelnen Check-in abrufen, ändern, löschen, liken/entliken.

**2. Bahnhöfe & Abfahrten (Reiseplanung)**

- **Station Search**: `GET /api/v1/trains/station/autocomplete/{query}` – Suche von Haltestellen per Text.
- **Nearby Stations**: `GET /api/v1/stations` (mit Bounding-Box über `min_lat`, `max_lat`, `min_lon`, `max_lon`) oder `GET /api/v1/trains/station/nearby` – Haltestellen in der Nähe. _Hinweis: `nearby` liefert immer nur einen Bahnhof, `stations` ist für Umkreissuche._
- **Departures**: `GET /api/v1/station/{id}/departures` – Abfahrtsmonitor. Benötigt die numerische ID, nicht den Namen!

**3. Fahrtdetails & Haltestellen (Trip-Infos)**

- **Trip Detail**: `GET /api/v1/trains/trip` – Liefert den gesamten Fahrtverlauf, benötigt `hafasTripId` und `lineName`.
- **Stopovers**: `GET /api/v1/stopovers/{tripId}` – Detaillierte Route eines getätigten Check-ins.

**4. Benutzer & Authentifizierung**

- **Eigenes Profil**: `GET /api/v1/auth/user` – Eigene Daten abrufen.
- **Fremdes Profil**: `GET /api/v1/user/{username}` – Profil eines anderen Nutzers.
- **Nutzerhistorie**: `GET /api/v1/user/{username}/statuses` – Vergangene Check-ins eines Nutzers.
- **Follow / Unfollow**: `POST/DELETE /api/v1/user/{id}/follow`.

**5. Notifications**

- **Benachrichtigungsliste**: `GET /api/v1/notifications`
- **Ungelesene Zähler**: `GET /api/v1/notifications/unread/count`
- **Gelesen markieren**: `PUT /api/v1/notifications/read/{id}` oder `/all`.

## OAuth

Token Exchange und Refreshing laufen über den `OAuthApiService` in `TraewellingApiService.kt` (`POST /oauth/token`).

## Logging und Tokens

`RetrofitClient` setzt den Bearer-Token als `Authorization`-Header. Netzwerk-Logging ist im Release-Build deaktiviert und im Debug-Build auf `BASIC` begrenzt; `Authorization` wird redaktiert.

## Verwandte Seiten

- [Interne Schnittstellen](./interne-schnittstellen.md)
- [Externe Schnittstellen](./externe-schnittstellen.md)
- [Check-in](../module/checkin.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
