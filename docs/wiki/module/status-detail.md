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
- `app/src/main/kotlin/de/traewelling/app/service/JourneyTimeResolver.kt`

## Verhalten

### Lade-Prozess

1. `loadStatusDetail(statusId)` lädt Status-Details und behält `manualDeparture` und `manualArrival` von CheckinInfo für die getrennte Anzeigenauflösung.
2. Lädt Stopovers via `repo.getStopovers(tripId)`.
3. Veröffentlicht Status, eindeutig zugeordnete Ein-/Ausstiegshalte, Timeline und Aktualisierungszeitpunkt gemeinsam. Ohne Trip wird die Timeline geleert.
4. Prüft via `checkIfOwnStatus()` ob eigener Status (für Bearbeiten/Löschen-Buttons).

Die Timeline verwendet `StopStation.stationName` und `stationId` aus dem verschachtelten Stationsobjekt. Einstieg und Ziel werden über `matchesStopover` statt über die alte Stopover-`id` zugeordnet. API-Halte bleiben unverändert im UI-Zustand; manuelle Zeiten und GPS-Werte werden nur für die Anzeige aufgelöst.

### Auto-Refresh

Alle 30 Sekunden wird `refreshSilently()` aufgerufen für Live-Delay-Daten. Während die frische Haltantwort aussteht, bleibt der vorherige vollständige Snapshot sichtbar. Der neue Status wird mit seinen zugeordneten Stopover-Grenzen und der neuen Timeline in einem UIState-Update übernommen; ein vorübergehender Rohstatus ohne passend hydratisierte Grenzen wird nicht angezeigt. Dadurch wechseln Headerzeiten nicht allein wegen nacheinander eintreffender API-Antworten zwischen GPS und API. Der Speichern-Erfolg ordnet ebenfalls noch kompatible vorhandene Grenzen vor der Veröffentlichung zu.

Schlägt die Halteanfrage fehl, werden dennoch die neuen Status-/Text-/manuellen Zeitfelder übernommen. Vorhandene Stopovers bleiben nur bei derselben Status-ID und Trip-ID, kompatibler vorhandener Trip-UUID sowie eindeutig passenden gelieferten Grenzen mit unveränderten gelieferten Planzeiten verwendbar. Andernfalls wird die alte Timeline geleert und die eingehenden API-Grenzen bleiben bestehen. Beim initialen Laden wird der Haltefehler angezeigt; der stille Refresh bleibt still. Dies ist keine Anzeigeverzögerung für abgelaufene GPS-Werte.

Antworten auf Status-, Halte- und Nutzeranfragen werden nur übernommen, wenn weiterhin dieselbe Status-ID angezeigt wird. Späte Antworten einer zuvor geöffneten Fahrt überschreiben dadurch nicht die neue Ansicht.

### GPS-Zeiten und API-Rückfall

`JourneyTimeResolver` entscheidet für Header und Halte dieselbe Priorität: frische GPS-Zeit des konkreten Ereignisses, manuelle Check-in-Zeit, parsebare API-Echtzeit, Planzeit. GPS-Werte werden nur aus dem passenden eigenen aktiven `TrackingLiveState` übernommen. Ankunft und Abfahrt erhalten getrennte Quellenhinweise; beobachtete Ankunft und Prognose sind unterscheidbar. Fehlende GPS-Abfahrt verhindert keine gültige API-Abfahrt desselben Halts. Ein bereits belegter passender GPS-Wert kann bei Bremsen und geordnetem Haltwechsel bis zu seinem ursprünglichen Gültigkeitsende erhalten bleiben; die UI führt dafür keine eigene Quellenverzögerung ein. Unbrauchbare oder abgelaufene Werte fallen weiterhin auf die nächsten Quellen zurück.

`StatusDetailContent` liest für jede neue Zusammensetzung die aktuelle Systemzeit und verwendet diesen gemeinsamen Zeitpunkt für Header, Haltzeiten und Timeline-Fortschritt. Ein zwischen den sekündlichen UI-Ticks eintreffender GPS-Datensatz wird dadurch nicht gegen die ältere Tickzeit geprüft und fälschlich als Zukunftswert verworfen. Der Ein-Sekunden-Ticker bleibt aktiv, damit GPS-Zeiten auch ohne neue Service-Publikation ablaufen. Die strikte Ablehnung tatsächlich zukünftiger Zeitstempel und die ursprüngliche GPS-Gültigkeitsdauer bleiben unverändert; die UI verlängert oder puffert keine Prognose.

Die frühere `propagateDelays()`-Vererbung einschließlich synthetischer Puffer wurde entfernt. Ein Rückfall zeigt dadurch tatsächliche vorhandene Providerwerte und bewahrt Verfrühungen, statt eine ältere positive Verzögerung auf weitere Halte zu übertragen. GPS-Prognosen verändern weder die API-Echtzeitfelder noch die gespeicherten Check-in-Zeiten. Das Bearbeitungsformular verwendet ausdrücklich den Resolver ohne GPS-Daten; eine Schätzung wird nicht beim Speichern zur manuellen Istzeit. Die Prognosebedingungen stehen unter [GPS-Zeiten](./gps-zeiten.md).

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

Ohne passenden aktiven Service, etwa bei fremden oder früheren Fahrten, ermittelt der Helper genau einen zeitbasierten Besuch aus Ankunft bis Abfahrt; bei überlappenden Aufenthalten gilt der letzte passende Besuch, sonst der nächste zukünftige. `JourneyTimeResolver.manualTimelineStops` erstellt dafür nur eine lokale Projektion mit parsebaren manuellen Einstieg-/Zielzeiten an eindeutig passenden Besuchen. Diese Projektion enthält keine GPS-Zeiten und wird weder im UIState gespeichert noch dem Bearbeitungsformular übergeben. Gestrichene Halte werden ausgelassen. Das frühere Zeitfenster von ±1 Minute je Zeile wird nicht mehr verwendet, da es benachbarte Halte gleichzeitig als aktuell markieren konnte.

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
- [GPS-Zeiten](./gps-zeiten.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Tests](../entwicklung/tests.md)
- [API Überblick](../api/ueberblick.md)
- [Datenmodell](../daten/datenmodell.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
