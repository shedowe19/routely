# UI: Screens

## Zweck

Übersicht aller Compose-Screens der App.

## Alle Screens

| Screen             | Datei                              | Zweck                                     |
| ------------------ | ---------------------------------- | ----------------------------------------- |
| SetupScreen        | `ui/screens/SetupScreen.kt`        | Login/Token-Eingabe                       |
| FeedScreen         | `ui/screens/FeedScreen.kt`         | Dashboard/Global-Feed                     |
| CheckInScreen      | `ui/screens/CheckInScreen.kt`      | Check-in Flow                             |
| LiveMapScreen      | `ui/screens/LiveMapScreen.kt`      | Native Transitous-Karte mit geschätzten Fahrzeugpositionen |
| NotificationScreen | `ui/screens/NotificationScreen.kt` | Benachrichtigungen                        |
| ProfileScreen      | `ui/screens/ProfileScreen.kt`      | Eigenes Profil + letzte Fahrten           |
| UserProfileScreen  | `ui/screens/UserProfileScreen.kt`  | Fremdes Profil                            |
| UserSearchScreen   | `ui/screens/UserSearchScreen.kt`   | Benutzer-Suche                            |
| StatusDetailScreen | `ui/screens/StatusDetailScreen.kt` | Status-Detail mit Timeline und Reisegrund |
| SettingsScreen     | `ui/screens/SettingsScreen.kt`     | Theme- und TTS-Einstellungen              |

## Navigation

Die App nutzt einen HorizontalPager mit 5 Tabs:

- Feed (Home)
- Check-in
- Live-Karte
- Notifications (mit Badge bei ungelesenen)
- Profil

Die `NavigationBar` nutzt eine Surface-Optik mit tonal elevation. Ausgewählte Tabs werden über Primary-Farbe und einen dezenten Indicator hervorgehoben.

Die Live-Karte bindet MapLibre nativ in Compose ein. Sie lädt Daten nur für den sichtbaren Kartenausschnitt, pausiert die regelmäßigen Abfragen beim Verlassen des Tabs und zeigt Quellenangaben für Transitous und die Kartenbasis an. Die optionale Standortzentrierung benötigt eine erteilte Standortberechtigung.

Zusätzliche Screens werden als Stack navigiert:

- `userProfile/{username}`
- `userSearch`
- `statusDetail/{statusId}`

## Zustandsdarstellung

Feed, Check-in, StatusDetail, Profile, UserProfile, UserSearch und Notifications nutzen `StateMessage` für Lade-, Fehler- und Empty-States. Dadurch sind die visuellen Zustände über die wichtigsten Screens konsistent.

## Verwandte Seiten

- [Komponenten](./komponenten.md)
- [Transitous Live-Karte](../features/transitous-live-karte.md)
