# Tracking-Service

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

`TripTrackingService` begleitet genau eine aktive Träwelling-Fahrt: Er pollt
den Status, verarbeitet GPS, berechnet Fortschritt und Zeiten, aktualisiert
Notification, Widget und Sprachansagen und speichert den Reisezustand.

## Einstieg und Lebenszyklus

- Start nur aus einer sichtbaren Activity über
  `ContextCompat.startForegroundService` (`MainActivity.kt:433-447`).
- Aktionen: `action_stop`, `DISMISS_LIVE_UPDATE`; Extras: `extra_status_id`,
  `extra_enable_gps`, `extra_auth_session_revision`.
- Ein Sticky-Restart ohne Extras wird akzeptiert, aktiviert aber kein GPS
  (`TripTrackingService.kt:504`).
- `onDestroy` beendet den Dienst; `onTimeout` (Android 15+) stoppt synchron und
  lässt `active_status_id` für einen sichtbaren Neustart stehen.
- Ist `gps_tracking_enabled` aus, läuft der Dienst als `dataSync`-FGS ohne
  Standort. Geht die Berechtigung verloren, wird er ebenfalls herabgestuft.

## Pipeline

1. **Start:** `startTracking` prüft Status-ID, Sessionrevision und laufende
   Generation. Ein veralteter Start kann keinen neueren verdrängen.
2. **Restore:** `restoreCachedTrip` liest `trip_tracking_state`, wenn Status und
   Session noch passen.
3. **Refresh:** `refreshTrip` holt Statusdetail und Stopovers, reichert sie mit
   DBF und SEV an und übergibt sie an die Engine.
4. **GPS:** `observeLocation` registriert fused location (3 s nah, 12 s fern).
   Nur ein Callback ist aktiv; alte Registrierungen werden verworfen.
5. **Engine:** `StationTrackingEngine.onLocation` führt den Besuchs-Cursor.
6. **Zeiten:** `GpsJourneyTimeEstimator` und `JourneyTimeResolver` liefern die
   Anzeigezeit.
7. **Ausgabe:** Notification (10-s-Takt, jeder akzeptierte Fix, jede
   Geometrieladung), Widget-Broadcast, optional TTS.
8. **Persistenz:** Fortschritt und Routenmetadaten werden nur geschrieben, wenn
   Status und Sessionrevision noch stimmen.

## Zustandsmodell

- `TrackingProgress(nextIndex, nextStopKey, arrivedAtCurrent, announcedKeys,
  completed, gpsEstablished)`.
- Veröffentlichung ist an vier Bedingungen gebunden:
  `generation == expectedGeneration`, `currentStatusId == statusId`,
  `!stopping`, `currentAuthSession == observedAuthSession`.
- Alle Zustandsänderungen laufen über einen Mutex auf
  `Dispatchers.Main.immediate`. Netzwerkabrufe für Geometrie laufen außerhalb;
  nur die Übernahme ist gesperrt.

## GPS-Qualität und Fallbacks

- Fixes werden nach `elapsedRealtimeNanos` geordnet; Duplikate und Replays
  zählen nicht.
- Akzeptanz: Alter ≤ 30 s, Genauigkeit ≤ 100 m (Engine) bzw. ≤ 75 m
  (Schätzer), nicht mehr als 5 s in der Zukunft, gültige Koordinaten.
- Ein Wanduhrsprung > 1 s setzt Uhr-Adapter, letzte Position und Schätzer
  zurück.
- Bewegungen über 100 m/s plus Genauigkeit gelten als unplausibel.
- Nach > 30 s ohne Fix bleibt der Cursor stehen; die Engine springt nicht
  planmäßig weiter.
- Wiederanschluss braucht einen eindeutigen späteren Halt innerhalb 150 m,
  drei frische Fixes über mindestens 6 s, kohärente Bewegung und kein
  überschrittenes Ziel.
- Zielabschluss braucht physische Ankunft (120/300 m), zwei Fixes mit niedriger
  Geschwindigkeit oder ≥ 10 s Aufenthalt und die richtige Annäherung. Im
  Fahrplanmodus wird das Ziel nie automatisch abgeschlossen.

## Notification, TTS und Widget

- Kanäle: `TripTrackingChannel` (LOW) und `TripChangesChannel` (DEFAULT);
  IDs 1001 (laufend) und 1002 (Änderungen, Tag `trip_changes:<statusId>`).
- `setOnlyAlertOnce(true)`; Fortschritt nutzt ab API 36
  `Notification.ProgressStyle`.
- Sprachansagen laufen über `SpeechDeliveryQueue`: eine Auslieferung pro
  Status, Generation, Halt und Art. Die Abschlussansage wird höchstens 15 s
  abgewartet.
- Das Widget ist passiv: `updatePeriodMillis=0`, nur Broadcasts
  `de.traewelling.app.ACTION_UPDATE_WIDGET` aktualisieren es. Wird die App
  force-gestoppt, bleibt es stehen.
- Beim Storage-Fehler zeigt das Widget „Begleitung pausiert – App öffnen“.

## Wake Lock und Akku

- Ein nicht referenzierter `PARTIAL_WAKE_LOCK` mit Tag `Routely:TripTracking`,
  120 s Timeout, Verlängerung alle 60 s.
- Nur die besitzende Generation darf verlängern; nach `stopping` wird nicht
  mehr erneuert.
- Die Fahrterkennung nutzt **keinen** Wake Lock.

## Beenden

`stopTracking` löscht lokal `active_status_id` und `trip_tracking_state`. Es
gibt in diesem Modul keinen Server-Check-out; das Beenden des Träwelling-
Check-ins ist nicht Teil des Tracking-Service.

## Tests

28 JVM-Testdateien unter `app/src/test/kotlin/de/traewelling/app/service/`.
Abgedeckt: Engine-Cursor, Tunnel-/GPS-Verlust, Prognosen, Änderungsmonitor,
Wake-Lock-Lease, Sessionbindung, Storage-Pause, Sprache, Fahrterkennung.
Nicht abgedeckt: Service-Lebenszyklus, Notification-Inhalt, Widget,
Berechtigungs-Herabstufung auf echten Geräten.

## Risiken

- Die Klasse ist sehr groß und mischt Orchestrierung, Persistenz und
  UI-Ausgabe. Änderungen brauchen die vorhandenen Regressionstests.
- `targetSdk 34`: Das Verhalten von `onTimeout` auf Android 15+ ist nicht auf
  Geräten verifiziert.
- Nach Berechtigungsverlust bleibt `gpsEnabled` intern wahr, der Cursor friert
  ein, bis GPS zurückkommt oder der Nutzer GPS abschaltet.

## Verwandte Seiten

- [GPS-Zeiten](gps-zeiten.md)
- [Fahrterkennung](fahrterkennung.md)
- [Datenfluss](../architecture/datenfluss.md)
- [Index](../index.md)
