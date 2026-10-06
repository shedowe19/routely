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

Refresh beendet den alten Ladeauftrag und erhöht die Generation; eine ältere Seite darf die neue Liste nicht überschreiben. Pagination wartet auf laufenden Refresh und dedupliziert nach Meldungs-ID. Einträge ohne brauchbare ID werden nicht als ausführbare Meldung übernommen. Sessionwechsel beendet das gesamte Feature-ViewModel über den [Auth-Store](./auth.md).

### Unread-Count Polling

- Im `init` wird ein Coroutine gestartet, der alle 60 Sekunden `refreshUnreadCount()` aufruft
- Der Badge in der NavigationBar zeigt die Anzahl

### Mark-Read Logik

- `markAsRead(notificationId)`: Optimistic Update + API-Aufruf
- `markAllAsRead()`: Setzt alle auf gelesen
- Tippen auf eine Meldung ruft nur für ungelesene Einträge `markAsRead()` auf. Es gibt dabei keine Zielnavigation zum Profil oder zur Fahrt.

Unbekannte oder bereits gelesene IDs verringern den Badge nicht. Pro Meldung ist höchstens ein Leseauftrag offen; `markAllAsRead` und einzelne Leseaufträge überschneiden sich nicht. Neue Listenantworten erhalten noch offene optimistische Lesemarkierungen. Bei API-Fehler werden die weiterhin passende vorläufige Markierung und ihr eigener Zähleranteil zurückgenommen; konkurrierende Markierungen bleiben erhalten. Bei fehlgeschlagenem `markAllAsRead` wird der frühere Zähler ebenfalls wiederhergestellt, auch wenn die anschließende Zähleranfrage fehlschlägt. Eine Zählerrevision verwirft Pollantworten, die vor einer inzwischen gestarteten Leseaktion begonnen haben.

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

- TODO: [Main-Review](../entwicklung/main-review-2026-10-06.md), D2: Nach Abschluss eines Read-POST kann ein schon vorher begonnener GET wieder ungelesene Daten anzeigen. Die bestehende Überlagerung schützt nur noch offene `pendingReads`; parallele normale Count-Anfragen brauchen zusätzlich eine eigene Antwortgeneration.
- TODO: Meldungen mit auflösbaren Zielinformationen zum zugehörigen Profil oder Status navigieren lassen.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Offene Fragen](../offene-fragen.md)
