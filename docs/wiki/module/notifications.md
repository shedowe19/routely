# Modul: Notifications

## Zweck

Zeigt die Liste der Benachrichtigungen (Likes, Follows, etc.) mit automatischer Aktualisierung.

## Kontext

Notifications werden im Tab "Meldungen" angezeigt und in der BottomNavigation mit einem Badge versehen, wenn ungelesene vorhanden sind.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/NotificationScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/NotificationViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/NotificationController.kt`

## Verhalten

### Lade-Prozess

1. `loadNotifications(refresh)` lädt eine Seite
2. `loadMore()` lädt weitere Seiten (Pagination via `links.next`)
3. `refresh()` lädt die erste Seite mit Pull-to-Refresh

Refresh beendet den alten Ladeauftrag und erhöht die Generation; eine ältere Seite darf die neue Liste nicht überschreiben. Pagination wartet auf laufenden Refresh und dedupliziert nach Meldungs-ID. Einträge ohne brauchbare ID werden nicht als ausführbare Meldung übernommen. Sessionwechsel beendet das gesamte Feature-ViewModel über den [Auth-Store](./auth.md).

`NotificationViewModel` bleibt der Android-Lifecycle-Adapter mit unverändertem Anwendungskonstruktor und öffentlicher Aktions-API. `NotificationController` hält die accountbezogenen Zustände und erhält einen CoroutineScope sowie ein API-Gateway. Dadurch lassen sich echte verzögerte Listen-, Lese- und Zählerantworten ohne Android-Instanz überprüfen.

### Unread-Count Polling

- Im `init` wird ein Coroutine gestartet, der alle 60 Sekunden `refreshUnreadCount()` aufruft
- Der Badge in der NavigationBar zeigt die Anzahl

Ein Mutex serialisiert Zähleranfragen. Zusätzlich tragen sie eine eigene Requestgeneration und den Stand der Leseänderungsrevision. Beginnt inzwischen ein neuer Zählerauftrag oder eine Leseaktion, darf die alte Antwort den Badge nicht überschreiben. Das gilt auch für parallele normale Anfragen aus Polling und Listenladen ohne zwischenzeitliches Lesen. HTTP-Fehler erzeugen keine erfolgreiche Nullantwort.

### Mark-Read Logik

- `markAsRead(notificationId)`: Optimistic Update + API-Aufruf
- `markAllAsRead()`: Setzt alle auf gelesen
- Tippen auf eine Meldung ruft nur für ungelesene Einträge `markAsRead()` auf. Es gibt dabei keine Zielnavigation zum Profil oder zur Fahrt.

Unbekannte oder bereits gelesene IDs verringern den Badge nicht. Pro Meldung ist höchstens ein Leseauftrag offen; `markAllAsRead` und einzelne Leseaufträge überschneiden sich nicht. Neue Listenantworten erhalten noch offene optimistische Lesemarkierungen. Nach erfolgreichem Leseauftrag bleibt die konkrete ID zusätzlich bestätigt überlagert, auch nachdem `pendingReads` entfernt wurde. Erst ein danach gestarteter GET mit serverseitigem `readAt` beendet diese zusätzliche Überlagerung. Ein früher begonnener GET kann eine erfolgreich gelesene Meldung damit nicht erneut als ungelesen anzeigen.

Ein erfolgreiches `markAllAsRead` setzt eine gemeinsame Revisionsgrenze. Noch ausstehende ältere Listen werden auch für zuvor nicht geladene IDs als gelesen übernommen. Neu auftauchende IDs aus erst danach begonnenen GETs bleiben dagegen ungelesen. Bei API-Fehler werden die weiterhin passende vorläufige Markierung und ihr eigener Zähleranteil zurückgenommen; konkurrierende Markierungen bleiben erhalten. Bei fehlgeschlagenem `markAllAsRead` wird der frühere Zähler ebenfalls wiederhergestellt, auch wenn die anschließende Zähleranfrage fehlschlägt.

`NotificationControllerTest` prüft mit virtueller Coroutine-Zeit und gezielt zurückgehaltenen API-Antworten verspätete GETs nach erfolgreichem Lesen, neue Meldungen nach `markAllAsRead`, Rollback neben einem anderen laufenden Leseauftrag, Zählerserialisierung und abgebrochene Refreshs. `NotificationReadStateTest` ergänzt die lokalen Mapping-/Rollbackregeln. Es werden keine Zeit-Sleeps verwendet; siehe [Tests](../entwicklung/tests.md).

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

- TODO: D6 des [Nachreviews](../entwicklung/main-review-2026-10-06.md) beheben: neue Meldungen nach serverseitigem MarkAll-Commit können während der verzögerten PUT-Antwort geladen und anschließend unbeabsichtigt dauerhaft gelesen bestätigt werden. Ein weiterer frischer GET reproduziert den Widerspruch zwischen Liste und serverseitigem Ungelesenzähler.

- TODO: Meldungen mit auflösbaren Zielinformationen zum zugehörigen Profil oder Status navigieren lassen.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Offene Fragen](../offene-fragen.md)
- [Main-Review](../entwicklung/main-review-2026-10-06.md)
- [Tests](../entwicklung/tests.md)
