# Modul: StatusDetail

## Zweck

Detaillierte Ansicht eines Check-ins mit Haltestellenverlauf, Live-Tracking und Bearbeitungsfunktion.

## Kontext

Zeigt einen einzelnen Status mit vollem Timeline-Verlauf der Haltestellen. Ermöglicht auch eigenständige Bearbeitung und Löschung.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/StatusDetailViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/StatusEditRequest.kt`
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

Lade- und Ansichtsgenerationen trennen zusätzlich mehrere Refreshs derselben Status-ID. Während Speichern oder Löschen sind Auto-Refresh und alte Ladeaufträge ausgesetzt; gegenseitige beziehungsweise doppelte Mutationen werden abgelehnt. Reset und Fahrtwechsel beenden auch den laufenden Mutationsauftrag. Ein gescheiterter Schreibauftrag kann den Auto-Refresh der weiterhin passenden Ansicht wieder starten.

### GPS-Zeiten und API-Rückfall

`JourneyTimeResolver` entscheidet für Header und Halte dieselbe Priorität: frische GPS-Zeit des konkreten Ereignisses, manuelle Check-in-Zeit, parsebare API-Echtzeit, Planzeit. GPS-Werte werden nur aus dem passenden eigenen aktiven `TrackingLiveState` übernommen. Ankunft und Abfahrt erhalten getrennte Quellenhinweise; beobachtete Ankunft und Prognose sind unterscheidbar. Fehlende GPS-Abfahrt verhindert keine gültige API-Abfahrt desselben Halts. Ein bereits belegter passender GPS-Wert kann bei Bremsen und geordnetem Haltwechsel bis zu seinem ursprünglichen Gültigkeitsende erhalten bleiben; die UI führt dafür keine eigene Quellenverzögerung ein. Unbrauchbare oder abgelaufene Werte fallen weiterhin auf die nächsten Quellen zurück.

Eine frisch geladene API-Grenze darf keinen GPS-Wert eines früheren Planpaares übernehmen. Auch ohne Stopover-UUID müssen geplante Ankunft und Abfahrt einschließlich fehlender Marker exakt zum GPS-Besuch passen. Entfernt oder ergänzt der Refresh einen Marker, folgt die Anzeige unmittelbar der neuen API-/manuellen Basis, ohne auf das getrennte Service-Polling zu warten.

`StatusDetailContent` liest für jede neue Zusammensetzung die aktuelle Systemzeit und verwendet diesen gemeinsamen Zeitpunkt für Header, Haltzeiten und Timeline-Fortschritt. Ein zwischen den sekündlichen UI-Ticks eintreffender GPS-Datensatz wird dadurch nicht gegen die ältere Tickzeit geprüft und fälschlich als Zukunftswert verworfen. Der Ein-Sekunden-Ticker bleibt aktiv, damit GPS-Zeiten auch ohne neue Service-Publikation ablaufen. Die strikte Ablehnung tatsächlich zukünftiger Zeitstempel und die ursprüngliche GPS-Gültigkeitsdauer bleiben unverändert; die UI verlängert oder puffert keine Prognose.

GPS-Haltfortschritt und GPS-Zeitdaten sind unabhängig. Der Timeline-Kopf darf daher `Fortschritt per GPS` anzeigen, während einzelne Zeiten weiterhin `Fahrplan` oder `API-Echtzeit` nutzen. Im passenden eigenen Trackingzustand erklärt `gpsTimeUnavailableReason` zusätzlich den aktuellen Prognoserückfall: fehlendes frisches GPS, unzureichende Genauigkeit, unbestätigter Halt, fehlende Streckendaten, Warten am Einstieg, fehlende sichere Abschnittszuordnung, fehlender Bewegungsbeleg oder unplausible Bewegung. Für die SEV-Straßenprojektion kommen ein fehlender geeigneter Straßenweg und mehrdeutiger Fortschritt auf möglichen Wegen hinzu. Läuft ein vorhandener GPS-Datensatz zwischen Service-Updates ab, zeigt der UI-Tick den Hinweis auf fehlendes frisches GPS. Der Hinweis liefert eine aktuelle Zustandsdiagnose, keine nachträgliche Rekonstruktion eines früheren Screenshots.

`OUTSIDE_CORRIDOR` erklärt neutral eine noch unsichere Zuordnung zum aktuellen Streckenabschnitt. Die Anzeige behauptet damit weder eine tatsächliche Tram-Abweichung vom Fahrweg noch eine garantiert amtliche Schienen-Geometrie. Bei GPS-Fortschritt nennt der Kopf eine tatsächlich verwendete Linienquelle: `Träwelling-Streckenverlauf` oder `SEV-Straßenmodell`. Der Service verwendet die aktuelle Zeitauswertung, sonst eine frische aktive Railprojektion der Stationsengine; der Quellenhinweis kann daher auch ohne neue GPS-ETA sichtbar sein. Ein nur vorgeladener Linienzug erzeugt ihn nicht. Ohne sicher gemeldete Linienquelle entfällt der Hinweis: Ein leerer Quellenwert kann sowohl die Geradenbasis als auch eine noch nicht zuordenbare Position bedeuten. Native Bahn-/Tramabschnitte, konservativer Geradenrückfall und haltbasierte Wiederverankerung sind unter [GPS-Zeiten](./gps-zeiten.md) getrennt beschrieben.

Bestätigte Ankunfts-/Abfahrtsereignisse können als `GPS beobachtet` sichtbar sein, ohne dass eine `GPS-Schätzung` für kommende Halte vorliegt. Sie ersetzen nur ihr jeweiliges Ereignis; künftige Zeiten behalten mangels Prognose den API-/Plan-Rückfall. Ein GPS-Signal oder die SEV-Haltkoordinate allein erzeugt keine neue ETA. Die Bedingungen für Beobachtung und Prognose bleiben unter [GPS-Zeiten](./gps-zeiten.md) getrennt dokumentiert.

Die frühere `propagateDelays()`-Vererbung einschließlich synthetischer Puffer wurde entfernt. Ein Rückfall zeigt dadurch tatsächliche vorhandene Providerwerte und bewahrt Verfrühungen, statt eine ältere positive Verzögerung auf weitere Halte zu übertragen. GPS-Prognosen verändern weder die API-Echtzeitfelder noch die gespeicherten Check-in-Zeiten. Das Bearbeitungsformular verwendet ausdrücklich den Resolver ohne GPS-Daten; eine Schätzung wird nicht beim Speichern zur manuellen Istzeit. Die Prognosebedingungen stehen unter [GPS-Zeiten](./gps-zeiten.md).

### SEV-Ersatzhaltestellen

Busfahrten mit RE-/RB-Linienkennung erhalten automatisch die [SEV-Ergänzung](./sev-haltestellen.md). Erst wird der vollständige API-Snapshot sichtbar veröffentlicht; danach lädt eine eigene Coroutine öffentliche bahnhof.de-Karten. Der API-Refresh wartet darauf nicht. Bei unverändertem Fahrt-/Routensnapshot wird ein noch laufender Abruf weiterverwendet, damit der 30-Sekunden-Refresh langsamere Ergebnisse nicht ständig abbricht.

Übernahme erfordert weiterhin dieselbe Status-ID, SEV-Generation, Trip-ID/UUID, Verkehrsmittel-/Linienkennung, Check-in-Grenzen und vollständige Haltfolge einschließlich Besuchsschlüssel, Planzeiten, Stationsdaten und Ausfallstatus. Reine API-Echtzeitänderungen machen eine räumliche Zuordnung nicht veraltet. Fahrtwechsel, Reset, Bearbeitung und ViewModel-Ende beenden die alte Anreicherung; späte Ergebnisse überschreiben keine neue Fahrt.

Für die passende eigene aktive Fahrt haben die besuchsbezogenen `TrackingLiveState.sevStops` Vorrang vor dem separaten Detailabruf. Der Service ist damit für die tatsächliche Tracking-Koordinate maßgeblich; die Detailansicht kann Wegangaben bereits vor einem laufenden Service anzeigen. Es werden keine Koordinaten in API-Halte oder gespeicherte Check-in-Zeiten geschrieben.

Der Fahrtkopf zeigt `Schienenersatzverkehr` und den Zustand der Ergänzung. Zugeordnete Haltzeilen zeigen `SEV-Haltestelle`, vorhandene Richtung, Wegbeschreibung und `Lageplan auf bahnhof.de`. Die Wegbeschreibung wird zunächst auf drei Zeilen begrenzt und lässt sich ausklappen. Bei Mehrdeutigkeit oder ungültiger Quelle erscheint der Auflösungsgrund in der Zeile; ohne passende Karte erläutert der Kopf den Rückfall auf vorhandene Stationsdaten. Bahn-Gleisbadges werden bei diesen SEV-Buskandidaten unterdrückt, damit ein Bahnsteig nicht als Busabfahrtsort erscheint. Gestrichene Halte erhalten keine SEV-Weganzeige.

Für diese Ersatzbusfahrten nennt die Ansicht zusätzlich OSRM und OpenStreetMap als Quellen der optionalen Straßenprojektion und bietet einen Link zur Kartenkorrektur. Ein Pkw-Routenmodell ist kein offizieller SEV-Busfahrweg. Die Zuordnung der Ersatzhalte und die Verfügbarkeit einer geeigneten GPS-Zeitprognose bleiben getrennt: Ein Quellenlink oder geladenes Straßenmodell erzeugt allein keine `GPS-Schätzung`. Fehlende beziehungsweise unpassende Wege verwenden den bestehenden Zeitquellenrückfall und dessen Diagnose.

### Bearbeitung (nur eigene Statusen)

- `startEditing()`: Setzt Bearbeitungszustand
- `saveStatusEdit()`: Sendet PUT `/api/v1/status/{id}` mit UpdateStatusRequest
- Bei einem Zielwechsel speichert `editDestinationStop` den ausgewählten Halt. Der Request enthält dann dessen `stationId` als `destinationId` und `arrivalPlanned` als `destinationArrivalPlanned`, da Upstream beide Felder gemeinsam verlangt. Reine Text- oder Zeitkorrekturen übertragen kein Zielpaar.

`buildStatusEditRequest` vergleicht die Formularzeiten mit den beim Öffnen aufgelösten Anfangswerten. Unveränderte Ankunft/Abfahrt werden im Request ausgelassen, damit eine reine Textänderung die damalige Providerzeit nicht versehentlich als manuelle Istzeit festschreibt. Eine bewusst geleerte Zeit wird als leerer String übertragen. Auch ein anderer Besuch derselben Station gilt als Zielwechsel; eine reine Echtzeitänderung desselben Besuchs nicht. Ein neues Ziel muss nach dem eindeutig zugeordneten Einstieg liegen und darf nicht gestrichen sein. Der GPS-freie Formularresolver bleibt erhalten.

Bei einem neuen Ziel wird ein vorhandener, vom Nutzer unveränderter manueller Ankunfts-Override des alten Ziels ausdrücklich mit `manualArrival = ""` gelöscht. Upstream wandelt diesen leeren Wert in `null` um; bloßes Weglassen würde den alten Override behalten. Eine eigens im Formular geänderte Zielzeit bleibt dagegen erhalten. Bei Rückwahl des ursprünglichen Besuchs wird dessen ursprünglicher manueller Wert wieder angezeigt. Speichern-/Löschfehler bleiben auch bei vorhandenem Status beziehungsweise offenem Bearbeitungsdialog sichtbar; laufendes Speichern sperrt dessen Bestätigung und Schließen.

### Löschung

- `deleteStatus()`: Sendet DELETE `/api/v1/status/{id}`

Nur eigene Status dürfen geändert oder gelöscht werden. Die lokale aktive Fahrt wird nach erfolgreichem Löschen zusätzlich gegen den vor dem Auftrag aufgenommenen Auth-Snapshot geprüft; eine gleich nummerierte Fahrt eines neu angemeldeten Kontos wird nicht entfernt.

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

Die Übernahme prüft außerdem `TrackingLiveState.sessionRevision` gegen die aktuelle Authrevision. Eine gleiche numerische Status-ID auf einem neu angemeldeten Konto genügt nicht für alte GPS-Zeiten, SEV-Hinweise oder Cursor.

| Quelle und Zustand | Markierung |
| --- | --- |
| GPS, innerer Ankunftsbereich bestätigt | `AKTUELL` |
| GPS, nächster Besuch noch nicht erreicht | `ALS NÄCHSTES` |
| Fahrplanquelle, beibehaltener Ankunftszustand | `LAUT FAHRPLAN` |
| Fahrplanquelle, nächster Besuch | `NÄCHSTER HALT · CA.` |
| Service-Besuch nicht zuordenbar oder gestrichen | Keine Haltmarkierung; `Position wird ermittelt` |

GPS-Fortschritt hat Vorrang vor der Uhrzeit. Der Kopf zeigt `Fortschritt per GPS` oder `Fahrplan-Schätzung · ungefähre Position`. Bei fehlender Zuordnung wird kein neuer Uhrzeitcursor erfunden. Nach Abschluss endet die Fortschrittslinie am eingecheckten Ziel; spätere Halte werden nicht als besucht markiert.

Bei der [Wiederverankerung nach GPS-Ausfall](./trip-tracking.md) bleibt der alte Cursor während des frischen Kandidatenbelegs geschützt, seine Quelle ist vorläufig Fahrplan. Erst die Engine-Bestätigung übernimmt den späteren konkreten Besuch in Timeline, Linie und Zeitauflösung. Die UI zählt weder Tunnelhalte anhand der Uhr als GPS-besucht noch erfindet sie deren Istzeiten. Ein Signal am späteren Halt allein genügt nicht; Mehrdeutigkeit und unzureichende Fixfolge lassen die sichere Zuordnung offen.

Ohne passenden aktiven Service, etwa bei fremden oder früheren Fahrten, ermittelt der Helper genau einen zeitbasierten Besuch aus Ankunft bis Abfahrt; bei überlappenden Aufenthalten gilt der letzte passende Besuch, sonst der nächste zukünftige. `JourneyTimeResolver.manualTimelineStops` erstellt dafür nur eine lokale Projektion mit parsebaren manuellen Einstieg-/Zielzeiten an eindeutig passenden Besuchen. Diese Projektion enthält keine GPS-Zeiten und wird weder im UIState gespeichert noch dem Bearbeitungsformular übergeben. Gestrichene Halte werden ausgelassen. Das frühere Zeitfenster von ±1 Minute je Zeile wird nicht mehr verwendet, da es benachbarte Halte gleichzeitig als aktuell markieren konnte.

Die Linie wird mit `drawBehind` über die vollständige Zeilenhöhe gezeichnet. Die Punktposition folgt der gemessenen Kopfzeilenhöhe, auch bei großer Schrift und mehrzeiligen Zeiten; feste Prozentsegmente werden nicht mehr eingesetzt.

## UI-Zustand (StatusDetailUiState)

| Feld          | Typ               | Beschreibung                    |
| ------------- | ----------------- | ------------------------------- |
| `status`      | Status?           | Geladener Status                |
| `stopovers`   | List<StopStation> | Haltestellen-Verlauf            |
| `isOwnStatus` | Boolean           | Ist eigener Status              |
| `trackingState` | TrackingLiveState? | Passender eigener aktiver Service-Fortschritt |
| `sevStops` | Map<String, SevStopInfo> | Besuchsbezogene SEV-Positionen beziehungsweise Quellenhinweise des Detailabrufs |
| `isLoadingSevStops` | Boolean | Öffentliche Karten werden im Hintergrund gesucht |
| `isEditing`   | Boolean           | Bearbeitungsmodus               |
| `isDeleting`  | Boolean           | Löschvorgang                    |
| `lastUpdated` | Long              | Timestamp letzte Aktualisierung |

## Offene Fragen

- TODO: EditStatusDialog Layout dokumentieren

## Verwandte Seiten

- [Check-in](./checkin.md)
- [TripTracking](./trip-tracking.md)
- [GPS-Zeiten](./gps-zeiten.md)
- [SEV-Ersatzhaltestellen](./sev-haltestellen.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Tests](../entwicklung/tests.md)
- [API Überblick](../api/ueberblick.md)
- [Datenmodell](../daten/datenmodell.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
