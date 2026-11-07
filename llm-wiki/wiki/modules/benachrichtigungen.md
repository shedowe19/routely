# Benachrichtigungen

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Der Bereich „Meldungen“ zeigt die Benachrichtigungen des Träwelling-Kontos und
verlinkt sie in Status- oder Profilansichten. Er ist nicht dasselbe wie die
lokalen Fahrt-Notifications des Tracking-Service.

## Screen und Zustand

- `NotificationScreen` erhält `serverUrl` sowie Callbacks für Status und
  Nutzer.
- `NotificationUiState` enthält Liste, Lade-/Refresh-Zustand, Fehler,
  Pagination und `unreadCount`.
- Der Ungelesen-Zähler wird alle 60 s im ViewModel abgefragt. Der Poll ist
  nicht lifecycle-aware.
- „Alle als gelesen“ sendet zuerst einen PUT und lädt danach den
  Server-Snapshot neu.

## Lese-Semantik

- Eine Meldung wird optimistisch als gelesen markiert.
- Schlägt der PUT fehl, wird nur die eigene Zeile und der eigene Zähler
  zurückgerollt; andere ausstehende Lesevorgänge bleiben erhalten.
- Der Zähler wird nie negativ.
- Unbekannte oder bereits gelesene IDs reduzieren den Zähler nicht.
- Ein älterer Listen-GET darf einen bestätigten Lesevorgang nicht
  zurücksetzen; Count-Anfragen sind serialisiert und revisionsgeprüft.
- „Alle als gelesen“ beansprucht nur IDs, die vor dem PUT bekannt waren. Neue
  Meldungen, die währenddessen eintreffen, bleiben ungelesen.

## Navigation

`NotificationTargetResolver` löst Links aus Meldungen auf:

- nur gleiche Origin wie die konfigurierte Instanz (Schema, Host, Port),
- kein `http`, kein protokollrelativer Link, keine Zugangsdaten,
- Pfadpräfixe selbst gehosteter Instanzen bleiben erhalten,
- Status-ID oder `@username` werden extrahiert,
- unbekannte Ziele, Pfadtraversal und Steuerzeichen werden verworfen.

Die Auflösung ist rein In-App. Es gibt keinen registrierten OS-Deep-Link für
Status oder Profile.

## Abgrenzung zu lokalen Notifications

| Art | Quelle | Kanal/ID |
|---|---|---|
| Träwelling-Meldungen | API `/notifications` | nur In-App-Liste |
| Laufende Fahrt | `TripTrackingService` | `TripTrackingChannel`, ID 1001 |
| Fahrtänderungen | `TripTrackingService` | `TripChangesChannel`, ID 1002 |
| Fahrterkennung | `RideRecognitionService` | `ride_recognition`, ID 710 |

Die lokalen Kanäle und ihre Aktionen sind in
[Tracking-Service](tracking-service.md) beschrieben.

## Tests

`NotificationControllerTest`, `NotificationReadStateTest`,
`NotificationTargetResolverTest`. Abgedeckt: Rollback, Serialisierung,
Snapshot-Neuladen, Origin-Prüfung, Injection-Fälle. Nicht abgedeckt: echtes
Notification-Rendering und OS-Zustellung.

## Risiken

- Der 60-s-Poll läuft, solange der Prozess lebt, und kann Akku kosten.
- Ein während des Kaltstarts eintreffender Navigations-Intent kann verloren
  gehen, wenn seine Sessionrevision noch nicht geladen ist.
- Benutzernamen werden an einigen Navigationszielen URL-kodiert, an anderen
  nicht. Der Resolver akzeptiert nur `[A-Za-z0-9_]{1,25}`; andere Pfade
  verlassen sich auf die Servergrammatik.

## Verwandte Seiten

- [Feed und Statusdetails](feed-und-status.md)
- [UI und Design](../frontend/ui-und-design.md)
- [Offene Fragen](../questions/offene-fragen.md)
- [Index](../index.md)
