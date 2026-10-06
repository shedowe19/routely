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
7. Der Compose-Screen (View) beobachtet den `StateFlow` (`collectAsState()`) und recomposed sich mit den neuen Daten.

## Sitzungs- und Anforderungsgrenzen

Der erreichbare Login validiert einen manuellen Token vor dem atomaren Speichern von Server, Token und Nutzer. `AuthSession.revision` trennt Zugangsgenerationen auch bei später wieder gleichen Zugangsdaten. Vergleichende DataStore-Schreiboperationen verhindern, dass alte Auth-/Check-in-Antworten eine neue Sitzung löschen, verändern oder als aktive Fahrt belegen. Logout entfernt die lokale Sitzung vor dem optionalen API-Aufruf.

`MainActivity` erhält Feature-ViewModels innerhalb derselben Sitzung über Rotation und löscht ihren Store beim Sitzungswechsel. Suche, Auswahl, Feed, Meldungen und Profile haben zusätzlich Anforderungsgenerationen gegen verspätete Antworten innerhalb derselben Sitzung. Repository-Abbrüche bleiben `CancellationException` und werden nicht als Cache-/UI-Erfolg zurückgegeben. Der Room-Feedcache ist nach Server-/Token-Digest und Feedart partitioniert; nur temporäre Seite-1-Fehler erlauben den passenden Offline-Rückfall. Verträge: [Auth](../module/auth.md), [Feed](../module/feed.md) und [Schemas](../daten/schemas.md).

## Aktive Fahrt und Stationsalarm

Nach einem erfolgreichen Check-in speichert `CheckInViewModel` die aktive Status-ID. Die sichtbare `MainActivity` prüft Standortfreigabe und GPS-Einstellung und startet den Foreground-Service. Ein ViewModel startet keinen GPS-Service aus dem Hintergrund.

Im Service laufen API-Aktualisierung (60 Sekunden), Standort-Callbacks und der Fahrplan-Tick getrennt. Die letzte erfolgreiche eingegrenzte Haltfolge versorgt `StationTrackingEngine`; deren Update liefert aktuellen Halt, Quelle, optionalen Ansagetrigger und GPS-Zielkriterium. Ein gemeinsamer Mutex serialisiert Engine-Änderung und Ergebnisübernahme gegenüber Tick, Routen- und Einstellungsänderungen. Netzwerkanfragen bleiben außerhalb der Sperre; Standort-Batches werden chronologisch vollständig verarbeitet.

Nach bestätigtem Foreground-Start und Abgleich der aktiven Fahrt hält eine separate generationsgebundene CPU-WakeLock-Lease die Verarbeitung bei Display aus verfügbar. Sie hat einen 120-Sekunden-Timeout, wird alle 60 Sekunden erneuert und bei Fahrtwechsel oder Service-Ende ausdrücklich freigegeben. Android-Doze ohne Nutzer-Ausnahme kann diese Haltung und Netzwerkzugriff trotzdem beschränken. Die [Akkuoptimierungseinstellung](../module/settings.md) liest deshalb den tatsächlichen Systemstatus; sie ersetzt keine Standort- oder TTS-Freigabe. Lebenszyklus und Grenzen stehen unter [TripTracking](../module/trip-tracking.md).

Bei der Ergebnisübernahme publiziert der Service vor der ersten Suspension `TrackingLiveState` mit Besuchsschlüssel, Cursor, passendem Halt, Ankunft, Abschluss und Quelle. Notification, Widget, TTS und Persistenz verwenden denselben Engine-Fortschritt. `StatusDetailViewModel` verbindet diesen prozesslokalen `StateFlow` mit aktiver Status-ID und eigener angezeigter Fahrt. `StopTimelineProgress` ordnet den Besuch der vollständigen Haltefolge zu; dessen Auswahl und Fortschrittsindex steuern sämtliche Markierungen und Verbindungslinien. Ein nicht zuordenbarer Service-Besuch führt zum Wartezustand, nicht zu einem neuen Zeitcursor.

Nach mehreren Haltbesuchen ohne GPS kann die Engine einen eindeutig nahen späteren Besuch anhand einer getrennten frischen Fixfolge wiederverankern. Während dieses Kandidatenbelegs bleiben Cursor und gemeinsame Anzeige vorläufig auf Fahrplanquelle, ohne neue alte Stationsansage; die Zeitauswertung verwirft den bisherigen GPS-Zustand. `isReacquiringLocation()` schützt außerdem eine zurückgestellte Ansage bei TTS-Initialisierung. Bestätigung publiziert denselben neuen Besuch an alle Verbraucher, ohne Zeiten oder Ansagen für übersprungene Halte zu erzeugen. Zielabschluss bleibt eine eigene physische Bestätigung. Der vorübergehende Kandidatenbeleg bleibt RAM-Zustand, kein neues Cache-/Live-DTO-Feld. Grenzen: [TripTracking](../module/trip-tracking.md).

`GpsJourneyTimeEstimator` wertet denselben etablierten Besuch, frische unterstützte GPS-Fixes und die geplanten Fahrintervalle getrennt für lokale Zeiten aus. Das Ergebnis `TrackingLiveState.gpsTimes` enthält besuchsbezogene Beobachtungen und Prognosen mit Gültigkeitsende, keine Positionen. `JourneyTimeResolver` vereinheitlicht die Quellenentscheidung für Header, Halte, Widget und Sperrbildschirm: GPS, manuelle Zeit, parsebare API-Echtzeit, Plan. Geeignete frische Positionen können eine schon belegte Prognose bei Bremsen oder geordnetem Haltwechsel bis zu deren ursprünglichem Gültigkeitsende erhalten. Unbrauchbares GPS verwirft die Zeitprognose, während der bisherige räumliche Cursor erhalten bleiben kann. Anzeigeauflösung ersetzt keine Providerfelder oder Bearbeitungswerte und erzeugt keinen automatischen Schreibrequest. Details: [GPS-Zeiten](../module/gps-zeiten.md).

Route und Besuchsfortschritt werden für die passende aktive Status-ID in DataStore gespeichert. Der Live-Flow einschließlich GPS-Zeiten ist kein zusätzlicher persistierter Zustand und wird bei Fahrtwechsel beziehungsweise Service-Ende geleert. API-Ausfälle blockieren die GPS-Auswertung einer bereits verfügbaren Route nicht. Gerätepositionen und GPS-Prognosen bleiben im Speicher; Positionen werden weder im Live-DTO veröffentlicht noch an Träwelling gesendet. Details: [TripTracking](../module/trip-tracking.md).

## Nativer Träwelling-Streckenverlauf

Für normale Bahn-/Tramkategorien bildet `TransitRouteSelection` aus der vollständigen eingegrenzten Route einschließlich gestrichener Besuche einen Request mit Status-/Tripidentität, geordneten Besuchsschlüsseln, Stationsdaten und Planmarkern. Eine eigene Coroutine lädt `polyline/{statusId}` vom konfigurierten Träwelling-Server mit dem passenden Bearer-Token. Gerätepositionen werden nicht übertragen; API-Polling und GPS warten nicht auf das Ergebnis.

`TransitRouteParser` bindet die Form eindeutig und monoton an diese Besuchsfolge und übernimmt nur Abschnitte mit zusätzlichem Formbeleg. Eine Mischung aus Backend-Strecken und Stationssehnen wird nicht ungeprüft als Gleisroute eingesetzt. Session, Fahrtgeneration und komplette Requestbasis müssen bei Übernahme unverändert sein. Die Form bleibt höchstens 15 Minuten im RAM; nach 14 Minuten ist ein Refresh möglich. Weder Room noch das Version-1-Fahrtcache-JSON erhält eine neue Geometrie oder GPS-Historie.

`TrackingRouteGeometry` projiziert lokal auf den passenden Linienzug. `StationTrackingEngine` nutzt ihn für gerichtete Abfahrts-/Lücken-/Nahhalt-Hilfen, der Zeitschätzer für Abschnittsfortschritt. Physische Ankunft und Abschluss bleiben gesonderte GPS-Belege. Ein Netzabschluss zählt keinen alten Fix erneut. `TrackingLiveState.gpsGeometrySource` benennt die von der Zeitauswertung verwendete Form, sonst eine frische aktive Railprojektion der Engine, nicht schon den Download. Formquelle und verfügbare Zeitprognose bleiben getrennt. Fehlende oder ungeeignete native Form erhält die bisherigen konservativen Geraden-/Zeitquellenregeln. Quelle und Grenzen: [GPS-Zeiten](../module/gps-zeiten.md), [Externe Schnittstellen](../api/externe-schnittstellen.md) und [ADR](../entscheidungen/2026-10-06-native-streckenverlaeufe.md).

## Öffentliche SEV-Quelle und lokale Projektion

Bei Busfahrten mit RE-/RB-Linienkennung startet nach dem vollständigen API-Snapshot eine separate [SEV-Anreicherung](../module/sev-haltestellen.md). `SevJourneyEnricher` lädt öffentliche Bahnhofskarten für eindeutige Slugs der eigenen Route, mit höchstens drei parallelen Stationsabrufen und einer begrenzten Abrufrunde. Ein eigener anonymer OkHttp-Client sendet keine Träwelling-Zugangsdaten oder Gerätepositionen. Der Parser liest eingebettete JSON-Daten und GeoJSON-SEV-Punkte; JavaScript wird nicht ausgeführt.

`SevStopResolver` prüft Stationsnähe, eindeutige Besuchsidentität, Quellenalter sowie Maßnahmen- und Richtungsdaten. Richtungskontext kommt aus den folgenden Halten der vollständigen API-Fahrt, nicht nur aus dem persönlichen Reiseausschnitt. Unsichere Ergebnisse liefern Weg-/Quellenhinweise mit Grund statt erfundener Koordinaten.

Der Service übernimmt eine abgeschlossene Abrufrunde nur für dieselbe Generation, Status-ID, Check-in-Grenzen und vollständige Route. Netzwerk liegt außerhalb des Tracking-Mutex. In `toTrackingStops()` ersetzen ausschließlich aktuelle eindeutige SEV-Punkte die lokale GPS-Koordinate; API-Objekte bleiben unverändert. API-Updates, Fahrplan-Ticks und frische GPS-Fixes prüfen die Zuordnung erneut. Veränderte physische Punkte invalidieren die GPS-Zeitbasis und eine darauf bezogene bisherige Ankunftsbestätigung.

Danach kann ein getrennter Straßen-Job höchstens den aktuellen Ankunftsabschnitt und dessen Folgeabschnitt, am Einstieg die ersten zwei passenden Abschnitte über `RoadRouteRepository` laden. Beide Endpunkte müssen eindeutig aufgelöste SEV-Punkte sein; fehlende Ersatzhaltbelege werden nicht überbrückt. Der anonyme FOSSGIS-OSRM-Request enthält nur die öffentlichen Haltkoordinaten. `RoadRouteParser` begrenzt und validiert die angebotenen GeoJSON-Linienzüge; Generation, Besuchspaar, aktuelles Fenster und physische Endpunkte müssen bei Übernahme weiterhin passen. Standortfixes werden nicht an den Router gesendet und Netzwerk hält keine Tracking-Sperre.

`GpsSegmentGeometry` bindet die RAM-Geometrie an die beiden konkreten Besuchsschlüssel. Nur der Zeitschätzer projiziert frisches GPS lokal auf passende Wege; Stationserkennung, Ansagen und Zielabschluss behalten ihre bisherige Engine. Mehrdeutige oder unpassende Straßenmodelle und fehlende Geometrie liefern für SEV keine Abschnittsprognose aus einer ersatzweise geraden Verbindung. Ein Modellwechsel setzt Prognose und Bewegung zurück, erhält jedoch bestätigte Ereignisse derselben Haltbasis. Die OSRM-Fahrtdauer ist kein Eingang für die Buszeit; der Versatz bleibt an geplanten Fahrintervallen orientiert.

`TrackingLiveState.sevStops` versorgt die Detailansicht. Ihr zusätzlicher anonymer Abruf läuft ebenfalls nach der API-Veröffentlichung und darf keine andere Fahrt überschreiben; für die eigene aktive Fahrt hat die Service-Zuordnung Vorrang. Der bestehende Fahrtcache speichert optional vollständige API-Halte und öffentliche SEV-Karten. Beim Wiederanlauf gelten dieselben Quellen-/Datumsprüfungen; GPS-Gerätepositionen bleiben unpersistiert. Der beschränkte Repository-Prozesscache ist davon unabhängig. Die Ergänzung erzeugt keinen Träwelling-Schreibrequest.

Straßen-Geometrien bleiben zusätzlich nur im RAM und werden nicht in diesen Fahrtcache aufgenommen. Der begrenzte Straßen-Repository-Cache gilt für erfolgreiche Ergebnisse 24 Stunden und Fehlschläge 15 Minuten. Im laufenden Prozess sind gültige bereits geladene Wege offline verwendbar; nach Neustart muss die benötigte Geometrie erneut verfügbar werden. Das Pkw-Profil beschreibt keine offiziell bestätigte SEV-Busroute. Die [Detailansicht](../module/status-detail.md) kennzeichnet OSRM/OpenStreetMap und verlinkt die Kartenkorrektur.

## Fahrterkennung und Änderungshinweise

Die Opt-in-[Fahrterkennung](../module/ride-recognition.md) ist vom aktiven Fahrttracking getrennt. Ein sichtbarer Location-Foreground-Service überträgt über `TraewellingRepository.getNearbyStations` die aus dem aktuellen Standort berechneten Bounding-Box-Grenzen an `GET /api/v1/stations` des konfigurierten Träwelling-Servers und lädt Abfahrten/Tripdetails. `RideRecognitionEngine` gleicht diese RAM-Kandidaten lokal mit frischer Bewegung ab. `CheckInViewModel` übernimmt ausschließlich noch gültige, vom Nutzer ausgewählte Vorschläge in den bestehenden Ziel-/Bestätigungsablauf. Kein GPS-Ergebnis löst selbst einen schreibenden Check-in aus.

Der [Änderungsmonitor](../module/trip-changes.md) vergleicht nur frische erfolgreiche Status-/Stopover-Antworten des aktiven Services. Er prüft vorhandene Providerfelder vor manuellen Zeitüberschreibungen. Meldungen gehen in einen eigenen Android-Kanal und optional in die gemeinsame TTS-Queue; die erste frische Antwort bleibt still. Deduplizierungswerte werden mit dem vorhandenen Fahrtcache gespeichert.

`TripProgressModel` berechnet aus eingegrenzter Haltfolge und gemeinsamem Cursor die Haltezahl. `TripProgressNotificationBuilder` wählt Framework-ProgressStyle oder kompatible Standardanzeige. Interne Notification-Intents mit `open_status_id` oder `open_recognition` werden über `NavigationRequest` sowohl beim Start als auch in einer laufenden Activity verarbeitet.

## Verwandte Seiten

- [Architektur Überblick](./ueberblick.md)
- [TripTracking](../module/trip-tracking.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
- [StatusDetail](../module/status-detail.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Fahrtänderungen](../module/trip-changes.md)
- [Reisefortschritt](../module/trip-progress.md)
