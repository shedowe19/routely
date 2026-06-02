# Modul: Auth

## Zweck

Dieses Modul verwaltet die Benutzerauthentifizierung und Session-Verwaltung innerhalb der App.

## Kontext

Die Authentifizierung ist der initiale Einstiegspunkt für den Nutzer, um personalisierte Funktionen wie Check-Ins und Feeds zu nutzen.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/viewmodel/AuthViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/data/repository/AuthRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/api/OAuthApiService.kt`
- `app/src/main/kotlin/de/traewelling/app/util/OAuthHelper.kt`

## Verhalten

Die App unterstützt mehrere Authentifizierungswege:

1. **OAuth Flow mit PKCE**: Über `OAuthHelper` werden kryptografisch sichere `code_verifier` und `code_challenge` generiert. Der resultierende Authorization Code wird in `AuthRepository.exchangeCodeForToken` zusammen mit dem `code_verifier` gegen Zugangs- und Refresh-Tokens eingetauscht.
2. **Manueller Token-Login (`AuthViewModel.loginWithToken`)**: Bei Legacy-Routen oder direkter Eingabe kann ein API-Token manuell hinterlegt werden. Dieser Vorgang überspringt OAuth und PKCE komplett. Das Token wird direkt via `PreferencesManager` gespeichert und mit einem Aufruf an `api.getAuthUser()` validiert.
3. **Refresh Token Flow**: Wird über `AuthRepository.refreshAccessToken()` gehandhabt (z.B. ausgelöst durch `viewModel.refresh()` in UI-Ansichten wie `UserProfileScreen`). Das gespeicherte Refresh-Token wird an `/oauth/token` gesendet, um ein neues Token-Paar zu erhalten. Schlägt dies fehl, wird die Session gelöscht (`prefs.clearSession()`).

Das `AuthViewModel` propagiert den aktuellen Authentifizierungsstatus (eingeloggt / nicht eingeloggt) an die UI.

## Abhängigkeiten

- **Retrofit**: Für die API-Kommunikation (`OAuthApiService`).
- **DataStore**: Für die lokale Persistenz der Authentifizierungs-Token (`PreferencesManager`).

## Offene Fragen

Keine offenen Fragen aktuell.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Module Übersicht](./README.md)
