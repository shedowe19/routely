# Modul: Auth

## Zweck

Dieses Modul verwaltet die Benutzerauthentifizierung und Session-Verwaltung innerhalb der App.

## Kontext

Die Authentifizierung ist der initiale Einstiegspunkt für den Nutzer, um personalisierte Funktionen wie Check-Ins und Feeds zu nutzen.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/viewmodel/AuthViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/data/repository/AuthRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/api/TraewellingApiService.kt` (enthält auch `OAuthApiService`)
- `app/src/main/kotlin/de/traewelling/app/util/OAuthHelper.kt`

## Verhalten

Der erreichbare `SetupScreen` bietet ausschließlich den manuellen Token-Login:

1. `AuthViewModel.loginWithToken()` speichert Server-URL und Token über `PreferencesManager` und prüft das Token mit `api.getAuthUser()`. OAuth und PKCE werden dabei nicht verwendet.
2. Beim App-Start wird ein gespeichertes Token erneut geprüft. Eine erfolglose HTTP-Antwort löscht die Session; bei einem Netzwerkfehler bleibt sie erhalten.
3. Logout ruft nach Möglichkeit die API auf und löscht anschließend die lokale Session.

`OAuthHelper` enthält PKCE-/URL-Hilfsfunktionen. `AuthRepository.exchangeCodeForToken()` und `refreshAccessToken()` können Token-Paare austauschen beziehungsweise erneuern, sind aber nicht an den erreichbaren Anmeldeablauf angebunden. `UserProfileViewModel.refresh()` lädt Profildaten erneut und löst keinen Refresh-Token-Flow aus. Einzelheiten stehen unter [OAuth/PKCE](./auth-pkce.md).

Das `AuthViewModel` propagiert den aktuellen Authentifizierungsstatus (eingeloggt / nicht eingeloggt) an die UI.

### SetupScreen UI

Der initiale `SetupScreen` nutzt einen Gradient-Hero mit Routely-Branding, Feature-Chips und eine Login-Card für Server-URL und manuellen Access-Token. Fehlermeldungen werden innerhalb der Card als getönter Error-Hinweis dargestellt. Echte Tokenwerte dürfen nicht dokumentiert werden.

## Abhängigkeiten

- **Retrofit**: Für die API-Kommunikation (`OAuthApiService`).
- **DataStore**: Für die lokale Persistenz der Authentifizierungs-Token (`PreferencesManager`).

## Offene Fragen

- TODO: OAuth-Anmeldung einschließlich Callback-Verarbeitung und automatischer Token-Erneuerung an einen erreichbaren UI-Ablauf anbinden, falls dieser Login unterstützt werden soll.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Module Übersicht](./README.md)
- [OAuth/PKCE](./auth-pkce.md)
- [Offene Fragen](../offene-fragen.md)
