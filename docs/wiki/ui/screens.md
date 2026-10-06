# UI: Screens

## Zweck

Übersicht aller Compose-Screens der App.

## Alle Screens

| Screen             | Datei                              | Zweck                                     |
| ------------------ | ---------------------------------- | ----------------------------------------- |
| SetupScreen        | `ui/screens/SetupScreen.kt`        | Login/Token-Eingabe                       |
| FeedScreen         | `ui/screens/FeedScreen.kt`         | Dashboard/Global-Feed                     |
| CheckInScreen      | `ui/screens/CheckInScreen.kt`      | Check-in Flow                             |
| NotificationScreen | `ui/screens/NotificationScreen.kt` | Benachrichtigungen                        |
| ProfileScreen      | `ui/screens/ProfileScreen.kt`      | Eigenes Profil + letzte Fahrten           |
| UserProfileScreen  | `ui/screens/UserProfileScreen.kt`  | Fremdes Profil                            |
| UserSearchScreen   | `ui/screens/UserSearchScreen.kt`   | Benutzer-Suche                            |
| StatusDetailScreen | `ui/screens/StatusDetailScreen.kt` | Status-Detail mit Timeline und Reisegrund |
| SettingsScreen     | `ui/screens/SettingsScreen.kt`     | Theme, GPS, Fahrterkennung, Änderungen, Fortschritt, TTS und Android-Akku-Ausnahmestatus |

## Navigation

Die App nutzt einen HorizontalPager mit 4 Tabs:

- Feed (Home)
- Check-in
- Notifications (mit Badge bei ungelesenen)
- Profil

Die `NavigationBar` nutzt eine Surface-Optik mit tonal elevation. Ausgewählte Tabs werden über Primary-Farbe und einen dezenten Indicator hervorgehoben.

Zusätzliche Screens werden als Stack navigiert:

- `userProfile/{username}`
- `userSearch`
- `statusDetail/{statusId}`
- `settings` (Einstieg über das eigene Profil)

## Benachrichtigungsnavigation

`MainActivity` übersetzt `open_status_id` in einen `NavigationRequest` zum passenden `statusDetail/{statusId}`. `open_recognition` öffnet den Check-in-Tab. Die Requests haben einen Verbrauchs-/Sequenzschlüssel und werden bei kaltem Start sowie `onNewIntent` verarbeitet; die Fahrtbenachrichtigung und Änderungshinweise führen damit zur jeweiligen Fahrt. Der Opt-in-Bereich der Fahrterkennung steht im Stationsschritt des Check-ins und bietet aktuelle Vorschläge, Pausen-/Fehlerhinweise und Beenden.

## Zustandsdarstellung

Feed, Check-in, StatusDetail, Profile, UserProfile, UserSearch und Notifications nutzen `StateMessage` für Lade-, Fehler- und Empty-States. Dadurch sind die visuellen Zustände über die wichtigsten Screens konsistent.

Die Status-Detail-Timeline nutzt für die eigene aktive Fahrt einen gemeinsamen Besuchscursor des Tracking-Service. GPS-Ankunft, Annäherung und ungefähre Fahrplanposition erhalten unterschiedliche Markierungen; die durchgehende Verbindungslinie verwendet denselben Fortschritt wie die Haltepunkte. Einzelheiten stehen unter [StatusDetail](../module/status-detail.md).

Header und Haltzeiten verwenden den gemeinsamen `JourneyTimeResolver` einschließlich frischer lokaler [GPS-Zeiten](../module/gps-zeiten.md). Jede Zeit kennzeichnet beobachtet, geschätzt, manuell, API-Echtzeit oder Fahrplan. GPS-Verlust wechselt die Zeitquelle, ohne für eine etablierte eigene Fahrt einen neuen Uhrzeitcursor zu erfinden. Fremde oder frühere Fahrten erhalten keine aktuellen GPS-Prognosen; das Bearbeitungsformular bleibt ebenfalls ohne GPS-Werte.

Die Einstellungs-Card `Begleitung bei ausgeschaltetem Display` liest den Akkuoptimierungsstatus direkt aus Android und aktualisiert ihn bei Rückkehr in die sichtbare Activity. Eine Ausnahme wird ausschließlich über einen ausdrücklichen Nutzertipp auf die Systemfreigabe angefragt. Sie ist kein gespeicherter App-Schalter; Start-/Fallbackverhalten steht unter [Settings](../module/settings.md).

## Verwandte Seiten

- [Komponenten](./komponenten.md)
- [Settings](../module/settings.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Reisefortschritt](../module/trip-progress.md)
- [StatusDetail](../module/status-detail.md)
