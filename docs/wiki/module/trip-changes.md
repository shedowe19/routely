# Modul: Änderungen auf der aktiven Fahrt

## Zweck

`TripChangeMonitor` vergleicht aufeinanderfolgende erfolgreiche API-Aktualisierungen der eigenen aktiven Fahrt. Er erzeugt verständliche Hinweise zu geänderten Gleisen, entfallenen oder wieder vorgesehenen Halten und größeren Verspätungsänderungen.

## Kontext

Der Monitor läuft im vorhandenen `TripTrackingService` mit dessen API-Polling, nicht bei jedem GPS-Fix oder Fahrplan-Tick. Er verfolgt die eingecheckte Etappe; Anschlussrisiken, Alternativrouten und vollständige Reisen mit Umstiegen werden nicht berechnet.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/TripChangeMonitor.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/service/SpeechDeliveryQueue.kt`
- `app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt`

## Vergleich und Meldungen

Der erste frische Snapshot bildet still die Vergleichsbasis. Ein restaurierter Cache wird niemals zum historischen Änderungsnachweis. Leere, uneindeutige oder nicht neuere Snapshots erzeugen keine Ereignisse. Nicht identifizierbare Halte behalten einen Indexplatzhalter, werden aber ohne Providerwerte nicht überwacht; der Cursor bleibt so zur ursprünglichen Route ausgerichtet. Besuchsschlüssel trennen wiederholte Stationsbesuche; bereits zurückliegende Halte werden nicht gemeldet.

| Änderung | Melderegel |
| --- | --- |
| Ausfall/Wiederherstellung | Ein bekannter `cancelled`-Wert ändert sich bei einem noch bevorstehenden Halt. Einstieg, nächster Halt und Ausstieg werden ausdrücklich bezeichnet. |
| Gleiswechsel | Alter und neuer Wert sind vorhanden und verschieden; betrifft den nächsten Halt oder den Ausstieg. Ankünfte verwenden Ankunftsgleise, der Einstieg sein Abfahrtsgleis. |
| Verspätung | Abweichung gegenüber dem zuletzt gesetzten Referenzwert um mindestens fünf Minuten in beide Richtungen. Bevorzugt wird die Zielankunft, ansonsten die nächste brauchbare Echtzeit. |

Kleine Verspätungsänderungen können sich zur Schwelle summieren. Fehlende Echtzeit beendet die Vergleichskontinuität. Geänderte Planzeiten oder manuelle Check-in-Zeitänderungen setzen die Referenz zurück, statt einen Providerhinweis vorzutäuschen. Meldungen nennen den Halt und die tatsächliche Änderung, zum Beispiel „Gleis 4 statt Gleis 2“.

`lastEvents` dedupliziert je Besuch und Feld den zuletzt gemeldeten Wert. Diese Metadaten sind für dieselbe Status-ID Teil des bestehenden Trackingcache; die vollständige Vergleichsbasis und Verspätungsreferenzen bleiben im RAM. Nach Neustart entsteht wieder eine stille frische Basis. Deduplizierungswerte werden an diese Basis und nach fehlenden Feldern an die neu hergestellte Kontinuität angeglichen: Eine später erneut geänderte Ankunft oder ein wieder gewechseltes Gleis darf nach bekannter Rückkehr zum früheren Wert erneut gemeldet werden. Fahrtwechsel setzt den Monitor zurück.

## Zustellung und Einstellungen

- `trip_change_alerts_enabled` aktiviert Hinweise und ist standardmäßig `true`.
- Ein separater Android-Kanal `TripChangesChannel` fasst die Änderungen eines Pollings in einer Meldung zusammen; spätere Meldungen derselben Fahrt ersetzen diese. Tippen öffnet deren Fahrtdetail über `open_status_id`.
- Android-Benachrichtigungsfreigabe und Kanaleinstellungen bestimmen die tatsächliche Sichtbarkeit. Ausgeschaltete Sperrbildschirmdetails verwenden eine allgemeine öffentliche Ersatzanzeige. Das Ausschalten von Hinweisen oder Sperrbildschirmdetails entfernt auch eine noch vorhandene Änderungsbenachrichtigung, selbst wenn der Tracking-Service bereits beendet wurde.
- `trip_change_speech_enabled` ist ebenfalls standardmäßig `true`. Zusätzlich müssen die globale TTS-Option aktiv, die Engine bereit und Audiofokus verfügbar sein. Änderungen werden in die gemeinsame Ansagequeue eingereiht.
- `SpeechDeliveryKind.TRIP_CHANGE` trennt Änderungshinweise von Stationsansagen. Ein TTS-Fehler eines Änderungshinweises gibt keinen Stationsansageschlüssel frei. Status-ID und Service-Generation schützen gegen verspätete Callbacks früherer Fahrten.

## Abhängigkeiten

`TripTrackingService`, Träwelling-Status/Stopovers, `PreferencesManager`, Android-Benachrichtigungen und Android-TTS. Die reine Vergleichslogik benötigt weder Android noch Standortdaten.

## Offene Fragen

- TODO: Reale Änderungen und deren Android-/TTS-Zustellung prüfen, insbesondere mehrere Ereignisse im selben Polling und konkurrierende Stationsansagen.
- Datenabhängige Grenze: Ohne frische oder eindeutige API-Felder ist keine verlässliche Änderungsmeldung möglich. Eine bereits beim Trackingstart vorhandene Verspätung wird als Ausgangslage behandelt.

## Verwandte Seiten

- [TripTracking](./trip-tracking.md)
- [Settings](./settings.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Tests](../entwicklung/tests.md)
- [Offene Fragen](../offene-fragen.md)
