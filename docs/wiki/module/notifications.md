# Modul: Notifications

## Zweck

Zeigt die Liste der Benachrichtigungen (Likes, Follows, etc.) mit automatischer Aktualisierung.

## Kontext

Notifications werden im Tab "Meldungen" angezeigt und in der BottomNavigation mit einem Badge versehen, wenn ungelesene vorhanden sind.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/NotificationScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/NotificationViewModel.kt`

## Verhalten

### Lade-Prozess

1. `loadNotifications(refresh)` lädt eine Seite
2. `loadMore()` lädt weitere Seiten (Pagination via `links.next`)
3. `refresh()` lädt die erste Seite mit Pull-to-Refresh

### Unread-Count Polling

- Im `init` wird ein Coroutine gestartet, der alle 60 Sekunden `refreshUnreadCount()` aufruft
- Der Badge in der NavigationBar zeigt die Anzahl

### Mark-Read Logik

- `markAsRead(notificationId)`: Optimistic Update + API-Aufruf
- `markAllAsRead()`: Setzt alle auf gelesen
- Tippen auf eine Meldung ruft nur für ungelesene Einträge `markAsRead()` auf. Es gibt dabei keine Zielnavigation zum Profil oder zur Fahrt.

### Notification-Typen

- "Liked": Herz-Icon (Error-Farbe)
- "Follow": PersonAdd-Icon (Primary-Farbe)
- "Connection": Zug-Icon (Secondary-Farbe)
- "Mention": AlternateEmail-Icon
- Sonst: Notifications-Icon

### UI-Darstellung

- Lade-, Fehler- und Empty-States via `StateMessage`
- Benachrichtigungen werden als abgerundete Cards dargestellt
- Ungelesene Einträge erhalten einen farbigen linken Akzent, stärkere Elevation und ein `Neu`-Badge

## UI-Zustand (NotificationUiState)

| Feld            | Typ                | Beschreibung                   |
| --------------- | ------------------ | ------------------------------ |
| `notifications` | List<Notification> | Liste aller Benachrichtigungen |
| `unreadCount`   | Int                | Anzahl ungelesener             |
| `hasMore`       | Boolean            | Weitere Seiten verfügbar       |
| `currentPage`   | Int                | Aktuelle Seiten-Nummer         |

## Abhängigkeiten

- **TraewellingRepository**: getNotifications, markNotificationRead, markAllNotificationsRead, getUnreadNotificationCount
- **StateMessage**: Einheitliche UI für Lade-, Fehler- und Empty-States

## Offene Fragen

- TODO: Meldungen mit auflösbaren Zielinformationen zum zugehörigen Profil oder Status navigieren lassen.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Offene Fragen](../offene-fragen.md)
