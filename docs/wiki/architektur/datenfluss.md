# Architektur: Datenfluss

## Zweck

Erklärung, wie Daten durch die App fließen.

## Datenfluss

1. Ein Compose-Screen triggert eine Aktion im `ViewModel` (z.B. User drückt "Check-in").
2. Das `ViewModel` aktualisiert seinen `StateFlow` (z.B. `isLoading = true`) und ruft das entsprechende `Repository` auf.
3. Das `Repository` entscheidet, ob Daten aus der lokalen Datenbank (`Room`) oder über das Netzwerk (`Retrofit`) bezogen werden.
4. Bei Netzwerkanfragen führt das `TraewellingApiService` den HTTP-Request aus und liefert Response-Modelle (Gson serialisiert).
5. Das `Repository` reicht die Daten an das `ViewModel` zurück, ggf. nach einer Zwischenspeicherung in der Datenbank (z.B. `StatusDao`).
6. Das `ViewModel` aktualisiert den `StateFlow` mit den neuen Daten.
7. Der Compose-Screen (View) beobachtet den `StateFlow` (`collectAsStateWithLifecycle()`) und recomposed sich mit den neuen Daten.

## Aktive Fahrt und Stationsalarm

Nach einem erfolgreichen Check-in speichert `CheckInViewModel` die aktive Status-ID. Die sichtbare `MainActivity` prüft Standortfreigabe und GPS-Einstellung und startet den Foreground-Service. Ein ViewModel startet keinen GPS-Service aus dem Hintergrund.

Im Service laufen API-Aktualisierung (60 Sekunden), Standort-Callbacks und der Fahrplan-Tick getrennt. Die letzte erfolgreiche eingegrenzte Haltfolge versorgt `StationTrackingEngine`; deren Update liefert aktuellen Halt, Quelle, optionalen Ansagetrigger und GPS-Zielkriterium. Ein gemeinsamer Mutex serialisiert Engine-Änderung und Ergebnisübernahme gegenüber Tick, Routen- und Einstellungsänderungen. Netzwerkanfragen bleiben außerhalb der Sperre; Standort-Batches werden chronologisch vollständig verarbeitet.

Bei der Ergebnisübernahme publiziert der Service vor der ersten Suspension `TrackingLiveState` mit Besuchsschlüssel, Cursor, passendem Rohhalt, Ankunft, Abschluss und Quelle. Notification, Widget, TTS und Persistenz verwenden denselben Engine-Fortschritt. `StatusDetailViewModel` verbindet diesen prozesslokalen `StateFlow` mit aktiver Status-ID und eigener angezeigter Fahrt. `StopTimelineProgress` ordnet den Besuch der vollständigen Haltefolge zu; dessen Auswahl und Fortschrittsindex steuern sämtliche Markierungen und Verbindungslinien. Ein nicht zuordenbarer Service-Besuch führt zum Wartezustand, nicht zu einem neuen Zeitcursor.

Route und Besuchsfortschritt werden für die passende aktive Status-ID in DataStore gespeichert. Der Live-Flow ist kein zusätzlicher persistierter Zustand und wird bei Fahrtwechsel beziehungsweise Service-Ende geleert. API-Ausfälle blockieren die GPS-Auswertung einer bereits verfügbaren Route nicht. Gerätepositionen bleiben im Speicher und werden weder im Live-DTO veröffentlicht noch an Träwelling gesendet. Details: [TripTracking](../module/trip-tracking.md).

## Verwandte Seiten

- [Architektur Überblick](./ueberblick.md)
- [TripTracking](../module/trip-tracking.md)
- [StatusDetail](../module/status-detail.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
