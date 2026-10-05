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

Bei der Ergebnisübernahme publiziert der Service vor der ersten Suspension `TrackingLiveState` mit Besuchsschlüssel, Cursor, passendem Halt, Ankunft, Abschluss und Quelle. Notification, Widget, TTS und Persistenz verwenden denselben Engine-Fortschritt. `StatusDetailViewModel` verbindet diesen prozesslokalen `StateFlow` mit aktiver Status-ID und eigener angezeigter Fahrt. `StopTimelineProgress` ordnet den Besuch der vollständigen Haltefolge zu; dessen Auswahl und Fortschrittsindex steuern sämtliche Markierungen und Verbindungslinien. Ein nicht zuordenbarer Service-Besuch führt zum Wartezustand, nicht zu einem neuen Zeitcursor.

`GpsJourneyTimeEstimator` wertet denselben etablierten Besuch, frische unterstützte GPS-Fixes und die geplanten Fahrintervalle getrennt für lokale Zeiten aus. Das Ergebnis `TrackingLiveState.gpsTimes` enthält besuchsbezogene Beobachtungen und Prognosen mit Gültigkeitsende, keine Positionen. `JourneyTimeResolver` vereinheitlicht die Quellenentscheidung für Header, Halte, Widget und Sperrbildschirm: GPS, manuelle Zeit, parsebare API-Echtzeit, Plan. GPS-Verlust verwirft die Zeitprognose, während der bisherige räumliche Cursor erhalten bleiben kann. Anzeigeauflösung ersetzt keine Providerfelder oder Bearbeitungswerte und erzeugt keinen automatischen Schreibrequest. Details: [GPS-Zeiten](../module/gps-zeiten.md).

Route und Besuchsfortschritt werden für die passende aktive Status-ID in DataStore gespeichert. Der Live-Flow einschließlich GPS-Zeiten ist kein zusätzlicher persistierter Zustand und wird bei Fahrtwechsel beziehungsweise Service-Ende geleert. API-Ausfälle blockieren die GPS-Auswertung einer bereits verfügbaren Route nicht. Gerätepositionen und GPS-Prognosen bleiben im Speicher; Positionen werden weder im Live-DTO veröffentlicht noch an Träwelling gesendet. Details: [TripTracking](../module/trip-tracking.md).

## Fahrterkennung und Änderungshinweise

Die Opt-in-[Fahrterkennung](../module/ride-recognition.md) ist vom aktiven Fahrttracking getrennt. Ein sichtbarer Location-Foreground-Service überträgt über `TraewellingRepository.getNearbyStations` die aus dem aktuellen Standort berechneten Bounding-Box-Grenzen an `GET /api/v1/stations` des konfigurierten Träwelling-Servers und lädt Abfahrten/Tripdetails. `RideRecognitionEngine` gleicht diese RAM-Kandidaten lokal mit frischer Bewegung ab. `CheckInViewModel` übernimmt ausschließlich noch gültige, vom Nutzer ausgewählte Vorschläge in den bestehenden Ziel-/Bestätigungsablauf. Kein GPS-Ergebnis löst selbst einen schreibenden Check-in aus.

Der [Änderungsmonitor](../module/trip-changes.md) vergleicht nur frische erfolgreiche Status-/Stopover-Antworten des aktiven Services. Er prüft vorhandene Providerfelder vor manuellen Zeitüberschreibungen. Meldungen gehen in einen eigenen Android-Kanal und optional in die gemeinsame TTS-Queue; die erste frische Antwort bleibt still. Deduplizierungswerte werden mit dem vorhandenen Fahrtcache gespeichert.

`TripProgressModel` berechnet aus eingegrenzter Haltfolge und gemeinsamem Cursor die Haltezahl. `TripProgressNotificationBuilder` wählt Framework-ProgressStyle oder kompatible Standardanzeige. Interne Notification-Intents mit `open_status_id` oder `open_recognition` werden über `NavigationRequest` sowohl beim Start als auch in einer laufenden Activity verarbeitet.

## Verwandte Seiten

- [Architektur Überblick](./ueberblick.md)
- [TripTracking](../module/trip-tracking.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [StatusDetail](../module/status-detail.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Fahrtänderungen](../module/trip-changes.md)
- [Reisefortschritt](../module/trip-progress.md)
