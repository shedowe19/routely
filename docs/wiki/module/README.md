# Module: Übersicht

## Zweck

Einstiegspunkt für die Beschreibung der Features und Module der App.

## Wichtige Module

- **Auth**: Login via OAuth2, Token Management (`AuthViewModel`, `OAuthHelper`).
- **Auth-PKCE**: Detaillierte OAuth2 PKCE Flow Dokumentation.
- **Feed**: Globale und persönliche Status-Liste (`FeedViewModel`, `FeedScreen`).
- **Check-in**: Suchen von Bahnhöfen, Abfahrten, Trips und durchführen des Check-ins (`CheckInViewModel`, `CheckInScreen`).
- **StatusDetail**: Detailansicht mit Timeline, Live-Tracking und Bearbeitung (`StatusDetailViewModel`, `StatusDetailScreen`).
- **UserProfile**: Profil eines anderen Nutzers mit Follow/Unfollow (`UserProfileViewModel`, `UserProfileScreen`).
- **UserSearch**: Benutzer-Suche (`UserSearchViewModel`, `UserSearchScreen`).
- **Notifications**: Benachrichtigungsliste mit Unread-Badge (`NotificationViewModel`, `NotificationScreen`).
- **Profile**: Eigenes Profil, Statistiken, TTS-Einstellungen (`ProfileViewModel`, `ProfileScreen`).
- **TripTracking**: Foreground Service für Live-Reiseverfolgung mit TTS (`TripTrackingService`).
- **Widget**: Homescreen-Widget für aktive Fahrt (`TripWidgetProvider`).

## UI-Module

- **Screens**: Alle Compose-Screens ([Screens](./ui/screens.md))
- **Komponenten**: Wiederverwendbare UI-Bausteine ([Komponenten](./ui/komponenten.md))
- **Theme**: Farben und Typografie ([Theme](./ui/theme.md))

## Verwandte Seiten

- [Architektur Module](../architektur/module.md)
