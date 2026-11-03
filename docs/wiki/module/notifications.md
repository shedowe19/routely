# Modul: Notifications

## Zweck

Zeigt die Liste der Benachrichtigungen (Likes, Follows, etc.) mit automatischer Aktualisierung.

## Kontext

Notifications werden im Tab "Meldungen" angezeigt und in der BottomNavigation mit einem Badge versehen, wenn ungelesene vorhanden sind.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/NotificationScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/NotificationViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/NotificationController.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/navigation/NotificationTargetResolver.kt`
- `app/src/test/kotlin/de/traewelling/app/ui/navigation/NotificationTargetResolverTest.kt`

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
- Tippen auf eine Meldung ruft nur für ungelesene Einträge `markAsRead()` auf. Auflösbare Fahrt-/Profilziele werden unmittelbar geöffnet, ohne auf den Leseauftrag zu warten. Ein Fehler der Lesemarkierung verhindert die Navigation nicht.

Unbekannte oder bereits gelesene IDs verringern den Badge nicht. Pro Meldung ist höchstens ein Leseauftrag offen; `markAllAsRead` und einzelne Leseaufträge überschneiden sich nicht. Neue Listenantworten erhalten noch offene optimistische Lesemarkierungen. Nach erfolgreichem Leseauftrag bleibt die konkrete ID zusätzlich bestätigt überlagert, auch nachdem `pendingReads` entfernt wurde. Erst ein danach gestarteter GET mit serverseitigem `readAt` beendet diese zusätzliche Überlagerung. Ein früher begonnener GET kann eine erfolgreich gelesene Meldung damit nicht erneut als ungelesen anzeigen.

`markAllAsRead` überlagert und bestätigt ausschließlich die beim Start bekannten IDs. Ein während des offenen PUT geladener neuer Eintrag kann nach dem Servercommit entstanden sein und bleibt entsprechend der Serverantwort ungelesen. Der Startzeitpunkt eines GET belegt nicht den Zeitpunkt seines Serversnapshots. Nach erfolgreichem PUT startet genau eine aktuelle erste Seite und beendet beziehungsweise sperrt ältere Listenaufträge; zuvor nicht geladene Einträge werden aus diesem neuen Serverstand übernommen, ohne globale Gelesen-Markierung.

Bei API-Fehler werden nur die eigenen vorläufigen Markierungen zurückgenommen. Der Badge erhält den bisherigen Zähler und mindestens die jetzt sichtbaren ungelesenen Einträge, einschließlich neuer Meldungen während des PUT, auch wenn der anschließende Count-GET fehlschlägt.

`NotificationControllerTest` prüft mit virtueller Coroutine-Zeit und gezielt zurückgehaltenen API-Antworten verspätete GETs nach erfolgreichem Lesen, neue Meldungen nach `markAllAsRead`, Rollback neben einem anderen laufenden Leseauftrag, Zählerserialisierung und abgebrochene Refreshs. `NotificationReadStateTest` ergänzt die lokalen Mapping-/Rollbackregeln. Es werden keine Zeit-Sleeps verwendet; siehe [Tests](../entwicklung/tests.md).

### Zielnavigation

Seit 03.11.2026 öffnet ein Tippen die zugehörige Fahrt oder das Nutzerprofil, wenn der API-Link ein unterstütztes Ziel des angemeldeten Servers beschreibt. `NotificationTargetResolver` akzeptiert ausschließlich HTTPS-Links derselben Origin einschließlich Port und Installationspfad sowie passende serverrelative Pfade. Die bestätigten Webrouten sind `/status/{id}` mit positiver `Int`-ID und `/@{username}` mit dem Upstream-Nutzernamenvertrag `[A-Za-z0-9_]{1,25}`. Profilnamen werden beim Aufbau der Android-Navigationsroute zusätzlich codiert.

Likes, Erwähnungen und gemeinsame Fahrten können so zur Statusansicht führen, Follows und bestätigte Folgeanfragen zum Profil. Die Auswahl hängt vom validierten `link` ab, nicht von einer Vermutung anhand des Meldungstyps oder aus Nachrichtentext. Fremde Hosts, andere Ports, Zugangsdaten in URLs, ungültige IDs, Steuerzeichen und eingeschleuste Pfadsegmente werden abgelehnt. Es wird keine URL in einem Browser geöffnet.

Meldungen zu noch nicht vorhandenen Appseiten wie Einstellungen oder Events werden weiterhin gelesen markiert, lösen aber keine ersatzweise Navigation aus. Bereits gelesene Einträge mit gültigem Ziel bleiben öffnbar; gelesene Einträge ohne Ziel besitzen keine leere Klickaktion. Ein Pfeil kennzeichnet unterstützte Ziele, die Klickaktion nennt für Bedienungshilfen `Fahrt öffnen` beziehungsweise `Profil öffnen`.

Der Resolver verwendet die aktuelle Serverbasis aus dem Authzustand. Die bestehenden accountbezogenen Feature-ViewModels und die synchronen Navigationscallbacks bleiben erhalten; es wird kein verspäteter API-Callback als Navigationsauslöser verwendet.

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

- TODO: Meldungsnavigation und Rückkehr zum Meldungstab einschließlich großer Schrift und TalkBack auf einem Android-Gerät prüfen. Unbekannte Ziele und fehlgeschlagene Lesemarkierungen bleiben getrennte Prüfpfade.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Offene Fragen](../offene-fragen.md)
- [Main-Review](../entwicklung/main-review-2026-10-06.md)
- [Tests](../entwicklung/tests.md)
