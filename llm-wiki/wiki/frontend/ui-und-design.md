# UI und Design

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite beschreibt den Aufbau der Oberfläche, die Navigation und die
visuellen Grundlagen. Sie ersetzt keinen Screenshot, sondern erklärt, wo man
welche Änderung macht.

## Navigationsmodell

- Eine Activity (`MainActivity`), ein `NavHost`.
- Routen: `main`, `userProfile/{username}`, `userSearch`,
  `statusDetail/{statusId}`, `settings`.
- Die vier Hauptbereiche sind Seiten eines `HorizontalPager`:
  Feed, Check-in, Meldungen, Profil.
- Die Bottom-Bar zeigt ein Ungelesen-Badge, gedeckelt bei „99+“.
- Ein Wechsel zu Check-in aus einer Notification läuft über
  `NavigationRequest.showCheckIn` und `scrollToPage(1)`, nicht über eine Route.

## Screens

| Screen | Aufgabe | Besonderheit |
|---|---|---|
| `SetupScreen` | Server-URL und Token | validiert HTTPS, zeigt Fehler |
| `FeedScreen` | Dashboard/globale Timeline | Likes, Pagination, Nutzersuche |
| `CheckInScreen` | Station → Fahrt → Ziel → Status | mehrstufig, Navigation gesperrt |
| `NotificationScreen` | Träwelling-Meldungen | Link-Auflösung, Lesen |
| `ProfileScreen` | eigenes Profil | Statistik, eigene Status |
| `UserProfileScreen` | fremdes Profil | Folgen, Pagination |
| `UserSearchScreen` | Kontensuche | führt ins Profil |
| `StatusDetailScreen` | Haltestellenverlauf, Bearbeiten | 30-s-Refresh, Live-Zustand |
| `SettingsScreen` | Theme, Tracking, TTS | Activity-scoped ViewModel |

## Komponenten

`StatusCard`, `StateMessage`, `TraewellingTopAppBar`, `StatPill`,
`StatusVisibilitySelector`, `JourneyDateTimeField`,
`DepartureSearchTimeSelector`, `StorageRecoveryDialog`.

Der Top-App-Bar nutzt einen horizontalen Verlauf aus der Primary-Farbe. Es
gibt keine Dynamic-Color-Anbindung.

## Theme und Farben

- Drei Schemata: `LIGHT`, `DARK`, `AMOLED`.
- Primary ist Deep Indigo, Secondary Teal, Tertiary Amber.
- Semantische Farben: Grün für pünktlich/verfrüht, Amber für Verspätung, Rot
  für Ausfall/Fehler.
- `TransportColors.forCategory` färbt Verkehrsmittel: Fernverkehr weinrot,
  Regionalverkehr DB-Blau, S-Bahn grün, U-Bahn blau, Tram rot, Bus violett,
  Fähre blau.
- `TraewellingRed` ist definiert, wird aber nicht verwendet.
- Typografie ist Material-3-Standard; keine eigenen Shapes.

## Formatierung

- Zeitstempel: ISO → Gerätezeitzone, lokalisiertes `SHORT`-Format.
- Reisezeit-Editor: `dd.MM.yyyy · HH:mm` im 24-h-Modus, sonst `h:mm a`.
- Abfahrtsboard: immer `dd.MM.yyyy · HH:mm XXX` (24 h), unabhängig von der
  Geräteeinstellung.
- Haltestellenzeiten: `HH:mm` in Gerätezeitzone, mit durchgestrichener Planzeit
  und `+n min`/`-n min`.
- DST: nicht existierende Frühlingszeit wird abgelehnt; bei doppelter
  Herbstzeit bleibt die bestehende Occurrence erhalten, sonst wird der frühere
  Offset gewählt.

## Zustände und Fehler

- `StateMessage` trägt Laden, Fehler und leere Liste.
- Der Storage-Recovery-Dialog blockiert die gesamte Oberfläche.
- Fast alle Texte sind deutsch im Kotlin-Code; `strings.xml` enthält nur fünf
  Einträge.

## Tests

Nur JVM-Tests für Präsentationslogik: `DepartureTimePresentationTest`,
`StopTimelineProgressTest`, `JourneyDateTimePresentationTest`,
`NotificationTargetResolverTest`. Keine Compose-UI- oder
Instrumentation-Tests.

## Risiken

- `themes.xml` ist hell; im Dark Mode kann beim Start ein heller Flash
  auftreten.
- Keine Lokalisierungsvorbereitung.
- Die Screens sind groß und enthalten teils Formatierungslogik, die besser in
  `util/` läge.

## Verwandte Seiten

- [Architekturüberblick](../architecture/ueberblick.md)
- [Einstellungen](../modules/einstellungen.md)
- [Storage-Recovery](../modules/storage-recovery.md)
- [Index](../index.md)
