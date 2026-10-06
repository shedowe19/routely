# Module: Übersicht

## Zweck

Einstiegspunkt für die Beschreibung der Features und Module der App.

## Wichtige Module

- **Auth**: Validierter manueller Token-Login und Session-Verwaltung ([Auth](./auth.md)).
- **Auth-PKCE**: Vorhandene OAuth2-/PKCE-Helfer; noch kein angebundener Browser-Login ([OAuth/PKCE](./auth-pkce.md)).
- **Feed**: Globale und persönliche Status-Liste (`FeedViewModel`, `FeedScreen`).
- **Check-in**: Suchen von Bahnhöfen, Abfahrten, Trips und durchführen des Check-ins (`CheckInViewModel`, `CheckInScreen`).
- **StatusDetail**: Detailansicht mit Timeline, Live-Tracking und Bearbeitung (`StatusDetailViewModel`, `StatusDetailScreen`).
- **UserProfile**: Profil eines anderen Nutzers mit Follow/Unfollow (`UserProfileViewModel`, `UserProfileScreen`).
- **UserSearch**: Benutzer-Suche (`UserSearchViewModel`, `UserSearchScreen`).
- **Notifications**: Benachrichtigungsliste mit Unread-Badge (`NotificationViewModel`, `NotificationScreen`).
- **Profile**: Eigenes Profil, Statistiken und Einstieg in die separate Einstellungsansicht (`ProfileViewModel`, `ProfileScreen`).
- **Settings**: Theme, GPS, Reisebegleitung, Android-Akku-Ausnahme und TTS-Sprache/-Stimme ([Settings](./settings.md)).
- **TripTracking**: Foreground-Service für GPS-Stationsalarme mit TTS ([TripTracking](./trip-tracking.md)).
- **GPS-Zeiten**: Lokale Zeitbeobachtung/-prognose, geeignete native Bahn-/Tramlinienzüge und getrennte SEV-Straßenprojektion mit gemeinsamem API-/Plan-Rückfall ([GPS-Zeiten](./gps-zeiten.md)).
- **SEV-Ersatzhaltestellen**: Öffentliche bahnhof.de-Punkte und Wegbeschreibungen, richtungs- und datumsabhängige lokale Zuordnung mit Rückfall auf API-Koordinaten ([SEV](./sev-haltestellen.md)).
- **Fahrterkennung**: Opt-in-GPS-Service für bestätigungspflichtige Fahrtvorschläge ([Fahrterkennung](./ride-recognition.md)).
- **Fahrtänderungen**: Vergleich frischer API-Snapshots und gezielte Hinweise ([Fahrtänderungen](./trip-changes.md)).
- **Reisefortschritt**: Gemeinsames Haltemodell und Android-Fortschrittsbenachrichtigung ([Live Updates](./trip-progress.md)).
- **Widget**: Homescreen-Widget für aktive Fahrt (`TripWidgetProvider`).

## UI-Module

- **Screens**: Alle Compose-Screens ([Screens](../ui/screens.md))
- **Komponenten**: Wiederverwendbare UI-Bausteine ([Komponenten](../ui/komponenten.md))
- **Theme**: Farben und Typografie ([Theme](../ui/theme.md))

## Verwandte Seiten

- [Architektur Module](../architektur/module.md)
