# Modul: StatusDetail

## Zweck

Detaillierte Ansicht eines Check-ins mit Haltestellenverlauf, Live-Tracking und Bearbeitungsfunktion.

## Kontext

Zeigt einen einzelnen Status mit vollem Timeline-Verlauf der Haltestellen. Ermöglicht auch eigenständige Bearbeitung und Löschung.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/StatusDetailViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/StopTimelineProgress.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingLiveState.kt`

## Verhalten

### Lade-Prozess

1. `loadStatusDetail(statusId)` lädt Status-Details
2. Enriches mit `manualDeparture` und `manualArrival` von CheckinInfo
3. Lädt Stopovers via `repo.getStopovers(tripId)`
4. Prüft via `checkIfOwnStatus()` ob eigener Status (für Bearbeiten/Löschen-Buttons)

Die Timeline verwendet `StopStation.stationName` und `stationId` aus dem verschachtelten Stationsobjekt. Einstieg und Ziel werden über `matchesStopover` statt über die alte Stopover-`id` zugeordnet. Ankunft und Abfahrt nutzen `effectiveArrival` und `effectiveDeparture`; manuelle Zeitkorrekturen werden dafür in `arrivalReal` und `departureReal` gespeichert.

### Auto-Refresh

Alle 30 Sekunden wird `refreshSilently()` aufgerufen für Live-Delay-Daten. Der aktualisierte Status wird im UIState gespeichert.

Antworten auf Status-, Halte- und Nutzeranfragen werden nur übernommen, wenn weiterhin dieselbe Status-ID angezeigt wird. Späte Antworten einer zuvor geöffneten Fahrt überschreiben dadurch nicht die neue Ansicht.

### Intelligente Verspätungsvererbung und Verspätungsabbau (Delay Recovery)

Vor der Speicherung des Haltestellenverlaufs im `UIState` (sowohl beim Initialladen als auch beim Auto-Refresh) durchläuft die Haltestellen-Liste die Methode `propagateDelays()`. Diese Funktion gleicht Plan- und Echtzeitdaten ab und berechnet die aktuelle Verspätung in Minuten. Wenn die API oder manuell geänderte Check-in-Daten eine geringere Verspätung als den erwarteten vererbten Wert melden, wird aus Gründen der logischen Konsistenz der höhere, vererbte Wert verwendet, um unmögliche Ankunftszeiten (wie eine Ankunft vor der vorherigen Abfahrt) zu verhindern. Um Pufferzeiten zu simulieren, baut die App die vererbte Verspätung bei nachfolgenden Stationen anhand einer realistischen Eisenbahn-Pufferformel anteilig ab:

- **Fahrzeitpuffer**: ca. 5% Puffer auf die reine Fahrzeit zwischen zwei Stationen.
- **Haltezeitpuffer**: Überschüssige Standzeiten/Haltezeiten (alles über 1 Minute Mindesthaltezeit) werden ebenfalls mit einem Skalierungsfaktor von 5% in den Verspätungsabbau einbezogen.
  Dadurch nimmt die geschätzte Ankunftszeit am Ziel realistisch ab, anstatt den exakt selben Verspätungswert blind bis zur Endstation mitzuschleppen.

### Bearbeitung (nur eigene Statusen)

- `startEditing()`: Setzt Bearbeitungszustand
- `saveStatusEdit()`: Sendet PUT `/api/v1/status/{id}` mit UpdateStatusRequest
- Bei einem Zielwechsel speichert `editDestinationStop` den ausgewählten Halt. Der Request enthält dann dessen `stationId` als `destinationId` und `arrivalPlanned` als `destinationArrivalPlanned`, da Upstream beide Felder gemeinsam verlangt. Reine Text- oder Zeitkorrekturen übertragen kein Zielpaar.

### Löschung

- `deleteStatus()`: Sendet DELETE `/api/v1/status/{id}`

### Timeline-Darstellung (StatusDetailScreen)

Die Timeline zeigt:

- Status-Header mit Nutzerinformationen; ein Klick auf den Header öffnet das Nutzerprofil, sofern ein Nutzername vorhanden ist
- Fahrtinformationen mit Linie, Kategorie, Betreiber, Start/Ziel, Zeiten und Reisegrund (`Status.business`)
- Getönter Timeline-Kopf mit Halteanzahl und Hinweis auf den Live-Fortschritt
- Durchgehende Verbindungslinie über die volle Höhe jeder Timeline-Zeile; Linie und Haltepunkte verwenden denselben Fortschrittsindex
- Dezente Container für aktuellen Halt, Einstieg, Ziel und Halte innerhalb der eigenen Reise
- Weiche Status-Übergänge zwischen Ladezuständen, Error und Timeline via `AnimatedContent`
- Gestaffelte Fade-in/Slide-in Animationen der Timeline-Einträge via `AnimatedVisibility`
- "LIVE" Badge mit Puls-Animation wenn Status heute ist
- Höchstens ein aktueller beziehungsweise nächster Besuch gemäß der unten beschriebenen Fortschrittsquelle
- Verspätungs-Badges (grün/rot)
- "HALT ENTFÄLLT" für gestrichene Halte
- "STARTHALTESTELLE", "ENDSTATION" Badges
- "DEIN EINSTIEG", "DEIN ZIEL" (goldene Premium-Badges)

### Gemeinsamer Besuchsfortschritt

Für den eigenen, weiterhin aktiven und gerade geöffneten Status übernimmt das ViewModel den In-Memory-`StateFlow` `TripTrackingService.trackingLiveState`. `StopTimelineProgress` ordnet dessen Besuch über `matchesStopover` beziehungsweise UUID der vollständigen Timeline zu. Der Index der eingegrenzten Service-Route wird nicht als Index der vollständigen Haltefolge verwendet; Rundfahrten bleiben besuchsbezogen.

| Quelle und Zustand | Markierung |
| --- | --- |
| GPS, innerer Ankunftsbereich bestätigt | `AKTUELL` |
| GPS, nächster Besuch noch nicht erreicht | `ALS NÄCHSTES` |
| Fahrplanquelle, beibehaltener Ankunftszustand | `LAUT FAHRPLAN` |
| Fahrplanquelle, nächster Besuch | `NÄCHSTER HALT · CA.` |
| Service-Besuch nicht zuordenbar oder gestrichen | Keine Haltmarkierung; `Position wird ermittelt` |

GPS-Fortschritt hat Vorrang vor der Uhrzeit. Der Kopf zeigt `Fortschritt per GPS` oder `Fahrplan-Schätzung · ungefähre Position`. Bei fehlender Zuordnung wird kein neuer Uhrzeitcursor erfunden. Nach Abschluss endet die Fortschrittslinie am eingecheckten Ziel; spätere Halte werden nicht als besucht markiert.

Ohne passenden aktiven Service, etwa bei fremden oder früheren Fahrten, ermittelt der Helper genau einen zeitbasierten Besuch aus Ankunft bis Abfahrt; bei überlappenden Aufenthalten gilt der letzte passende Besuch, sonst der nächste zukünftige. Gestrichene Halte werden ausgelassen. Das frühere Zeitfenster von ±1 Minute je Zeile wird nicht mehr verwendet, da es benachbarte Halte gleichzeitig als aktuell markieren konnte.

Die Linie wird mit `drawBehind` über die vollständige Zeilenhöhe gezeichnet. Die Punktposition folgt der gemessenen Kopfzeilenhöhe, auch bei großer Schrift und mehrzeiligen Zeiten; feste Prozentsegmente werden nicht mehr eingesetzt.

## UI-Zustand (StatusDetailUiState)

| Feld          | Typ               | Beschreibung                    |
| ------------- | ----------------- | ------------------------------- |
| `status`      | Status?           | Geladener Status                |
| `stopovers`   | List<StopStation> | Haltestellen-Verlauf            |
| `isOwnStatus` | Boolean           | Ist eigener Status              |
| `trackingState` | TrackingLiveState? | Passender eigener aktiver Service-Fortschritt |
| `isEditing`   | Boolean           | Bearbeitungsmodus               |
| `isDeleting`  | Boolean           | Löschvorgang                    |
| `lastUpdated` | Long              | Timestamp letzte Aktualisierung |

## Offene Fragen

- TODO: EditStatusDialog Layout dokumentieren

## Verwandte Seiten

- [Check-in](./checkin.md)
- [TripTracking](./trip-tracking.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Tests](../entwicklung/tests.md)
- [API Überblick](../api/ueberblick.md)
- [Datenmodell](../daten/datenmodell.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
