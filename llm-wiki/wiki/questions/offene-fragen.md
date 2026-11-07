# Offene Fragen

Status: active
Last verified: 2026-11-04
Confidence: medium

## Zweck

Ungeklärte Punkte, die Code, Tests und Commit-Historie nicht beantworten. Sie
sind keine Tasks, solange keine Akzeptanzprüfung formuliert ist.

## Architektur und Produkt

### O-1: Ist Browser-OAuth geplant oder Altlast?

Manifest-Filter `traewelling://oauth-callback`, `OAuthHelper` (PKCE/State),
`exchangeCodeForToken` und `refreshAccessToken` existieren. Kein Screen startet
den Flow, `MainActivity` liest `intent.data` nicht. Entweder implementieren
oder entfernen.
Quellen: `AndroidManifest.xml:44-52`, `util/OAuthHelper.kt`,
`data/repository/AuthRepository.kt:49-134`, `MainActivity.kt:297-307`.

### O-2: Soll ein Token-Refresh möglich sein?

Der Token-Login speichert weder `refresh_token` noch `client_id`; ein 401
beendet die Session bei der nächsten Prüfung. Ist das für persönliche
API-Tokens gewollt?
Quellen: `AuthRepository.kt:22-32`, `AuthSession.kt`.

### O-3: Gehen Navigations-Intents beim Kaltstart verloren?

`AppNavigation` konsumiert eine `NavigationRequest` mit nicht passender
Sessionrevision. Trifft ein Intent vor der Auth-Hydration ein, kann er
verworfen werden. Soll er stattdessen gepuffert werden?
Quelle: `ui/navigation/AppNavigation.kt:53-61`.

### O-4: Ist die Benutzernamen-Kodierung sicher?

Benachrichtigungen kodieren mit `Uri.encode`, Feed/Suche/Statusdetail nicht.
Der Resolver akzeptiert nur `[A-Za-z0-9_]{1,25}`. Garantiert der Server diese
Grammatik?
Quellen: `AppNavigation.kt:150`, `:207`, `:221`;
`ui/navigation/NotificationTargetResolver.kt`.

## Daten und Datenschutz

### O-5: Wann wird der Feed-Cache gelöscht?

Room-Zeilen sind an einen Token-Hash gebunden und werden beim Logout nicht
global entfernt. Private Feed-Inhalte bleiben nach Logout oder Tokenwechsel
liegen. Soll ein Logout die Partitionen löschen?
Quelle: `data/repository/TraewellingRepository.kt:118-125`,
`data/local/StatusDao.kt`.

### O-6: Ist der ungenutzte Nearby-Endpunkt Absicht?

`api/v1/trains/station/nearby` ist deklariert, wird aber nicht aufgerufen; der
Repository-Pfad nutzt `api/v1/stations` mit fester Bounding-Box. Soll der
Endpunkt entfernt oder der Radius konfigurierbar werden?
Quellen: `data/api/TraewellingApiService.kt`,
`data/repository/TraewellingRepository.kt:239-270`.

### O-7: Warum kein Provider-Zeitstempel bei DBF?

`DbfStopDelta.providerUpdatedAt` und `DbfBoard.providerUpdatedAt` sind immer
`null`, weil v3 keinen Zeitstempel liefert. Ist eine UI-Anzeige geplant oder
bleibt `fetchedAt` die einzige Provenienz?
Quelle: `data/dbf/DbfModels.kt`, `data/model/StopRealtimeInfo.kt`.

### O-8: Wozu werden DBF-Meldungen geparst?

`DbfMessage` wird gelesen, aber von Matcher und Delta nicht verwendet. Soll es
eine UI dafür geben?
Quelle: `data/dbf/DbfJsonParser.kt`, `DbfMatcher.kt`.

## Betrieb und Geräte

### O-9: Verhält sich `onTimeout` auf allen OEMs gleich?

`targetSdk 34`, `compileSdk 36`. Der Android-15-Callback stoppt synchron und
lässt `active_status_id` stehen. Ist das auf realen Geräten verifiziert?
Quellen: `service/TripTrackingService.kt:1821-1832`,
`app/build.gradle.kts:12-17`.

### O-10: Wie bleibt das Widget aktuell?

Das Widget pollt nicht und wird nur vom Service aktualisiert. Nach einem
Force-Stop bleibt es stehen. Soll es einen Zeitstempel oder eine
Aktualisierung beim App-Start geben?
Quellen: `widget/TripWidgetProvider.kt`, `res/xml/trip_widget_info.xml`.

### O-11: Was passiert bei Berechtigungsverlust mitten in der Fahrt?

Der Dienst wird auf `dataSync` herabgestuft, aber `gpsEnabled` bleibt intern
wahr und der Cursor friert ein. Soll explizit auf einen Uhr-Cursor
umgeschaltet werden?
Quelle: `service/TripTrackingService.kt:244-266`, `:681-691`, `:1185-1194`.

### O-12: Fehlen Geräte- und UI-Tests?

Alle Tests sind JVM-Unit-Tests. Service-Lebenszyklus, Notification, Widget,
Compose und Berechtigungen sind ungetestet. Welche Geräte-/Emulator-Matrix ist
verbindlich?
Quelle: `app/src/test/`, [Teststrategie](../testing/teststrategie.md).

## Repository und Dokumentation

### O-13: Wohin gehört die Dokumentation?

`README.md` und der Wiki-Sync verweisen auf `docs/wiki/`, das im Arbeitsbaum
gelöscht ist. Siehe
[Dokumentationspfade](../contradictions/2026-11-04-dokumentationspfade.md).

### O-14: Welche Lizenz gilt?

Es gibt keine `LICENSE`-Datei. Ohne Lizenz ist eine Weiterverbreitung
rechtlich unklar.

## Verwandte Seiten

- [Aktueller Projektstand](../current-project-state.md)
- [Tasks](../tasks.md)
- [Index](../index.md)
