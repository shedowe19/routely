# Modul: Reisefortschritt in Android-Benachrichtigungen

## Zweck

Die laufende Fahrtbenachrichtigung zeigt nächsten Halt, verbleibende Halte, Zielzeit und Datenquelle. Auf unterstützten Android-Versionen verwendet sie die systemeigene Fortschrittsdarstellung und kann als hervorgehobenes Live Update angezeigt werden.

## Kontext

`TripTrackingService` erstellt das Fortschrittsmodell aus der eingecheckten Haltfolge und demselben `TrackingLiveState`, den die Fahrtdetail-Timeline verwendet. Der Balken zählt Halte; die Zielzeit nutzt getrennt davon den gemeinsamen Zeitresolver einschließlich lokaler GPS-Prognosen. Die Anzeige ist keine separate Navigation.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/TripProgressModel.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TripProgressNotificationBuilder.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/service/JourneyTimeResolver.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/navigation/NavigationRequest.kt`
- `app/src/main/kotlin/de/traewelling/app/MainActivity.kt`
- `app/src/main/AndroidManifest.xml`

## Fortschrittsmodell

Gezählt werden konkrete geordnete Haltbesuche bis zum eingecheckten Ziel. Der Einstieg und gestrichene Halte zählen nicht als verbleibende Halte. Ein Besuchsschlüssel beziehungsweise die konkrete Stopover-Zuordnung ist maßgeblich; ein nackter Index darf wiederholte Stationen nicht verwechseln.

Fortschritt basiert auf Halten, nicht auf Entfernung oder Fahrzeit. Unbekannte Zuordnung bleibt unbestimmt. GPS- und Fahrplanquelle werden gekennzeichnet; eine rein zeitbasierte Zielposition setzt den Balken nicht auf vollständig erreicht. Erst das bestehende bestätigte GPS-Zielkriterium erlaubt den Abschluss. Ein gestrichener Zielhalt wird ausdrücklich benannt.

Sobald der konkrete nicht gestrichene Zielbesuch als angekommen markiert ist, sind null Halte übrig: Die Anzeige lautet `Am Ziel · Ankunft wird geprüft`. Haltezählung und Fahrtabschluss bleiben getrennt. Solange `TrackingLiveState.completed` nicht bestätigt ist, bleibt der Balken eine Fortschrittseinheit unter seinem Maximum; bei drei gezählten Halten sind das 299 von 300. Bei bloßer Annäherung bleibt der Zielhalt noch übrig. Gestrichene oder nicht eindeutig zugeordnete Besuche bestätigen keine Zielankunft.

Die Zielzeit stammt über `JourneyTimeResolver` aus frischer zugeordneter GPS-Beobachtung/-Schätzung, sonst manueller Zielzeit, parsebarer API-Echtzeit oder Fahrplan. Die jeweilige Zeitquelle steht direkt bei der Zielankunft. Eine belegte GPS-Zeit kann bei passenden frischen Folgefixes trotz Bremsen oder geordnetem Haltwechsel bis zu ihrem ursprünglichen Gültigkeitsende erhalten bleiben. Bei ungeeignetem oder abgelaufenem GPS fällt die Uhrzeit zurück, ohne den Besuchscursor neu zu erfinden. „Noch n Halte“ und der Fortschrittsbalken sind kein Nachweis der Prognosegüte; Grenzen der konservativen Interpolation stehen unter [GPS-Zeiten](./gps-zeiten.md).

## Android-Darstellung

| Voraussetzung | Darstellung |
| --- | --- |
| Android API 36+ mit aktiviertem Fortschritt | Framework-`Notification.ProgressStyle` mit Segmenten und Haltpunkten; unbestimmt, solange kein Besuch zugeordnet ist. |
| Älteres Android oder deaktivierter Live-Fortschritt | Normale `NotificationCompat`-/`BigTextStyle`-Fahrtbenachrichtigung. Bei eingeschaltetem Fortschritt bleibt der konventionelle Balken mit Haltezahl verfügbar. |
| API 36.1+-System mit geeigneter laufender Fahrt | Anfrage zur Hervorhebung über das offiziell dokumentierte Extra `android.requestPromotedOngoing`. Die Systementscheidung bleibt unabhängig von der Anfrage. |

Das Manifest enthält `POST_PROMOTED_NOTIFICATIONS`; compileSdk 36 ermöglicht die typisierte `ProgressStyle`-API. Die Promotion-Anfrage verwendet den dokumentierten Bundle-Wert der API 36.1, damit kein Minor-SDK-/Kotlin-Wechsel erforderlich ist. Es gibt keinen Aufruf einer erst ab API 36.1 verfügbaren Methode auf älteren Geräten.

Benachrichtigungsberechtigung, Nutzerentscheidungen, Kanal, Android-Version und Herstelleroberfläche entscheiden über Anzeige und Hervorhebung. Eine Samsung-spezifische Sperrbildschirmoberfläche oder ein hervorgehobener Statuschip wird nicht garantiert.

Das Wegwischen eines Live Updates unterdrückt die erneute Hervorhebungsanfrage für die laufende Fahrt; die Fahrtbegleitung selbst bleibt bestehen. Dieser Zustand wird im Tracking-Cache gespeichert und nach einem Service-Neustart wiederhergestellt. Eine neue Fahrt setzt ihn zurück. `TripTrackingService.onStartCommand` erfasst bei jedem Service-Intent, einschließlich Wegwisch-Intent, zuerst die aktuelle `startId`. Ein späteres `finishService` stoppt dadurch den neuesten Start und lässt nach API-Abschluss keinen Service wegen einer veralteten Start-ID weiterlaufen.

Die Aktionen `Fahrt öffnen` beziehungsweise Tippen öffnen das passende Fahrtdetail mit `open_status_id`. `Beenden` beendet die Begleitung. Navigation behandelt kalte und bereits laufende Activity-Starts.

`Beenden` und der Wegwisch-Intent tragen zusätzlich die Authrevision sowie eine revisionsbezogene Intent-Identität. Der Service verarbeitet sie nur für seine noch aktuelle Sitzung und Fahrt. Eine alte Benachrichtigungsaktion darf daher keine gleich nummerierte aktive Fahrt eines anderen Kontos beenden oder deren Hervorhebung deaktivieren. Die lokale ID-/Cache-Löschung ist ebenfalls sitzungsgebunden; Details: [TripTracking](./trip-tracking.md).

## Einstellungen und Datenschutz

`live_progress_enabled` und `lock_screen_details_enabled` sind standardmäßig `true`. Werden Sperrbildschirmdetails ausgeschaltet, wird die öffentliche Anzeige durch einen allgemeinen Routely-Hinweis ersetzt. Die Hervorhebung wird dann nicht angefordert, damit Route und Halte nicht über eine andere Sperrbildschirmfläche offengelegt werden. Android-Sperrbildschirmeinstellungen wirken zusätzlich.

Der Button `Android-Anzeigeeinstellungen` öffnet ab API 36 `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` (`android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS`) für das App-Paket. Auf älteren Geräten wird die normale App-Benachrichtigungseinstellung verwendet; ohne passende System-Activity öffnet sich die App-Detailseite. Diese Freigabeoberfläche ist von der Live-Update-Anfrage der laufenden Notification getrennt.

## Abhängigkeiten

`TripTrackingService`, `TrackingLiveState`, Android-Notifications und `PreferencesManager`. Es wird keine zusätzliche GPS-Historie oder Fortschrittsdatenbank angelegt.

## Offene Fragen

- TODO: Systemdarstellung, Wegwischen, Privatsphäre und kalte/warme Navigation auf Android API 35, 36 und 36.1 sowie Samsung-Geräten prüfen.
- TODO: Tatsächliche OEM-Hervorhebung und Layout bei großer Schrift auf einem Gerät prüfen; ein erfolgreicher Build belegt diese Oberfläche nicht.

## Offizielle Quellen

- [Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)
- [Notification.ProgressStyle](https://developer.android.com/reference/android/app/Notification.ProgressStyle)
- [Notification-Referenz und Promotion-Extra](https://developer.android.com/reference/android/app/Notification)

## Flutter-Umsetzung

`flutter/lib/tracking/trip_progress.dart` übernimmt eindeutige besuchsgebundene Fortschrittsberechnung. Native Sperrbildschirm-Anzeigen ergänzen Android-Live-Progress und iOS-Live-Activity; signierte Apple-Geräteprüfungen bleiben separat. Weitere Details stehen in der [Flutter-Architektur](../architektur/flutter-migration.md). Die bisherigen Kotlin-Verträge bleiben die Verhaltensreferenz.

## Verwandte Seiten

- [TripTracking](./trip-tracking.md)
- [GPS-Zeiten](./gps-zeiten.md)
- [StatusDetail](./status-detail.md)
- [Settings](./settings.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Screens und Navigation](../ui/screens.md)
- [Build](../entwicklung/build.md)
- [Tests](../entwicklung/tests.md)
