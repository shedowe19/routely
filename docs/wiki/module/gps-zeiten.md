# Modul: GPS-gestützte Reisezeiten

## Zweck

Aktualisiert die angezeigten Zeiten der eigenen aktiven Fahrt anhand eines zuverlässig zugeordneten GPS-Verlaufs. Bei fehlender geeigneter Beobachtung nutzt die Anzeige wieder die manuellen Check-in-Zeiten, API-Echtzeit oder den Fahrplan. Providerfelder und gespeicherte Check-in-Zeiten werden nicht durch Schätzwerte ersetzt.

## Kontext

`TripTrackingService` erzeugt nach der geordneten Halterkennung ein prozesslokales `GpsJourneyTimes`. `TrackingLiveState.gpsTimes` versorgt Fahrtdetail, Fahrtbenachrichtigung und Widget. `JourneyTimeResolver` entscheidet für jede Ankunft und Abfahrt einzeln, welche Zeit angezeigt wird. GPS-Zeiten sind nur für den passenden eigenen aktiven Status verfügbar; fremde und vergangene Fahrten erhalten sie nicht.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/GpsJourneyTimeEstimator.kt`
- `app/src/main/kotlin/de/traewelling/app/service/JourneyTimeResolver.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingLiveState.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TripProgressModel.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/StatusDetailViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/widget/TripWidgetProvider.kt`
- [Werkzeug zum Abruf öffentlicher SEV-Punkte](../../../tools/extract_bahnhof_sev.py)
- [SEV-Beispielabruf vom 06.10.2026](../../../tools/sev-stops-example.json)

## Quellenentscheidung

| Priorität | Voraussetzung | Anzeigequelle |
| --- | --- | --- |
| 1 | Frische GPS-Zeit für genau diesen Haltbesuch und dieses Ereignis | `GPS beobachtet` oder `GPS-Schätzung` |
| 2 | Parsebare manuelle Ankunft am Ziel beziehungsweise Abfahrt am Einstieg | `Manuell` |
| 3 | Parsebares Echtzeitfeld des API-Halts | `API-Echtzeit` |
| 4 | Parsebare Planzeit | `Fahrplan` |

Fehlt ein einzelnes GPS-Ereignis, bleibt für dieses Ereignis der Rückfall verfügbar. Fehlende oder ungültige Echtzeit ist kein Grund, eine ungültige Zeit anzuzeigen. Ohne jede geeignete Zeit bleibt die Anzeige leer. Verspätung und Verfrühung werden als Differenz zur Planzeit berechnet; GPS-Verfügbarkeit ist kein Anlass, negative Abweichungen auf null zu begrenzen.

Ein GPS-Datensatz gilt höchstens 30 Sekunden ab seinem Fixzeitpunkt. Eine UUID identifiziert bevorzugt den konkreten Besuch; auch bei gleicher UUID müssen die vorhandenen Planmarker weiterhin passen. Ohne UUID benötigt die Zuordnung Station-ID und übereinstimmende vorhandene Planzeiten; mehrere passende Besuche bleiben mehrdeutig und verwenden den Rückfall. Gestrichene Halte erhalten keine GPS-Zeit.

Die Gültigkeitsprüfung benötigt einen aktuellen Vergleichszeitpunkt: In [StatusDetail](./status-detail.md) wird die Systemzeit bei jeder Zusammensetzung gelesen, auch wenn neue GPS-Daten zwischen zwei UI-Ticks eintreffen. Die ältere Tickzeit darf einen bereits eingetroffenen Fix nicht scheinbar in die Zukunft versetzen. Ein weiterhin sekündlicher Tick prüft den Ablauf bei ausbleibenden Updates. Tatsächlich zukünftige oder abgelaufene GPS-Datensätze bleiben ungültig; weder Schätzergrenzen noch Gültigkeitsdauer werden für die Anzeige gelockert.

## Konservative GPS-Auswertung

Der Schätzer setzt einen bereits räumlich etablierten Besuchscursor der `StationTrackingEngine` voraus. Frische Position allein genügt nicht. Fixes müssen gültige Koordinaten und eine Genauigkeit von höchstens 75 Metern haben; ihr Zeitstempel darf weder in der Zukunft liegen noch älter als 30 Sekunden sein. Doppelte Zeitstempel und reine Uhr-/API-Ticks zählen nicht als zusätzliche Bewegung oder Aufenthaltsdauer.

Eine beobachtete Ankunft setzt bestätigten Ankunftsstatus und `Entfernung + Genauigkeit <= 120 m` voraus. Zwei langsame Fixes mit höchstens 3 m/s oder mindestens acht Sekunden stabiler Aufenthalt bestätigen die Beobachtung. Gespeichert wird der Zeitpunkt des ersten unterstützten inneren Fixes. Dieser Ankunftswert bleibt während der gültigen GPS-Beobachtungsfolge stabil, auch wenn das Fahrzeug weiter wartet. Eine frühe beobachtete Ankunft prognostiziert keine unbestätigte Frühabfahrt: Die aktuelle Folgeabfahrt wartet mindestens bis zum Plan. Sobald die geplante Abfahrt überschritten wird, erhöht das Warten den Zeitversatz für kommende Halte.

Am Einstieg liefert stationäres Warten grundsätzlich noch keinen GPS-Versatz für die Weiterfahrt, auch wenn eine Planankunft vorhanden ist. Die Telefonposition am Bahnsteig beweist keine Zugverspätung; vorhandene API-Abfahrt bleibt maßgeblich. Erst unterstützte gerichtete Weiterbewegung kann dort eine Prognose etablieren. Wird das Tracking erst nach einer verfrühten Abfahrt gestartet, darf die `StationTrackingEngine` den geordneten Folgehalt bereits vor der Plan-/API-Abfahrt räumlich etablieren. Eine ausreichend lange gerichtete Fixfolge kann anschließend auch eine frühere GPS-Ankunft prognostizieren; die zukünftige API-Abfahrt blockiert diesen Ablauf nicht.

Zwischen Halten wird der Fortschritt auf einen konservativen geraden Korridor zwischen dem letzten nicht gestrichenen Halt und dem aktuellen Besuch projiziert. Eine neue Prognose benötigt mindestens drei gerichtete Fixes über acht Sekunden und mindestens `max(50 m, 3 × Genauigkeit)` unterstützte Bewegung. Das Beobachtungsfenster wird nach verstrichener Zeit begrenzt, nicht auf acht Location-Callbacks: Mindestens eine Sekunde zwischen gespeicherten Stichproben und höchstens 32 Stichproben decken bis zu 30 Sekunden ab. Auch häufige Updates können damit die erforderlichen acht Sekunden belegen. Der aktuelle Fix geht zusätzlich in die Bewegungsauswertung ein.

Der Korridor erlaubt höchstens `max(100 m, 2 × Genauigkeit)` Querabweichung. Unterstützte Segmente sind 100 Meter bis 50 Kilometer lang, ihre geplante Fahrzeit liegt zwischen 15 Sekunden und 90 Minuten; neue Prognosen verwenden 5 bis 98 Prozent des Segments.

Der Versatz ergibt sich aus dem Fixzeitpunkt gegenüber dem räumlich interpolierten Zeitpunkt im geplanten Fahrintervall. Kommende Planzeiten werden um diesen Versatz verschoben. Ein Abstand geteilt durch momentane Geschwindigkeit wird nicht als Ankunftsprognose verwendet. Beobachtete Abfahrt benötigt vorherigen beobachteten Aufenthalt und geordnete Abfahrtsbewegung; sie kann auf langen Segmenten schon vor der Fünf-Prozent-Prognosegrenze erfasst werden. Sie bezeichnet den unterstützten Bewegungsfix nach dem Halt, nicht den exakten Zeitpunkt des Türschließens. Unplausible Sprünge, Rückwärtsbewegung und Versatzbeträge über sechs Stunden erzeugen keine GPS-Zeit.

## Rückfall und Lebensdauer

Eine bereits unterstützte Prognose benötigt nicht bei jedem Bremsfix oder Haltwechsel erneut die gesamte Mindestbewegung. Fehlt kurzzeitig ein neuer berechenbarer Versatz, kann eine frische, zur geordneten Haltfolge und zum Korridor passende Position das bisherige Ergebnis bis zu dessen ursprünglichem Gültigkeitsende erhalten. Das gilt auch bei Ankunft vor abgeschlossener langsamer Ankunftsbeobachtung und beim geordneten Übergang in den folgenden Abschnitt. Liegt ein bereits etablierter aktueller Besuch im bestätigten inneren Ankunftsbereich (`Entfernung + Genauigkeit <= 120 m`), darf ein Fix leicht hinter dessen Stationskoordinate das alte Ergebnis bis zum Ablauf erhalten. Die Querabweichung, Richtung und Sprungprüfung bleiben erforderlich; der zusätzliche Endbereich liefert ohne weiteren Beobachtungsbeleg keinen neuen Versatz. Der alte Unterstützungszeitpunkt und die höchstens 30 Sekunden Gültigkeit werden dabei nicht verlängert; eine neue Prognose braucht weiterhin die vollständigen Beobachtungskriterien.

Alter, Ungenauigkeit, fehlende Koordinaten, unklare Besuchsidentität, ein unplausibler Sprung oder eine Position außerhalb des unterstützten Korridors verhindern die Prognose und erhalten kein altes Ergebnis. Erkennbares Zurückfahren außerhalb der Genauigkeitstoleranz verwirft die Prognose ebenfalls. Kurvige Strecken, parallele Wege und spärliche Haltfolgen können deshalb trotz empfangenem GPS auf API-/Planzeit zurückfallen. Die Quellenwahl für die Zeit ist unabhängig von der Herkunft des Besuchscursors: Ein räumlich etablierter Cursor bleibt bei einem vorübergehenden Signalverlust erhalten, während die Uhrzeit wieder `API-Echtzeit` oder `Fahrplan` zeigen kann.

Die GPS-Beobachtungen, Fixfolge und Prognosen bleiben ausschließlich im RAM. Ungültiger Standortzustand verwirft sie; ein Service-Neustart übernimmt keine frühere GPS-Zeit aus dem Cache. Änderungen an Besuchsfolge, Planzeiten, Koordinaten, Streichungen oder manuellen Check-in-Zeiten setzen die betreffende Prognosebasis zurück; veränderte API-Echtzeit allein verwirft den räumlich gestützten Planversatz nicht. Bereits verbrauchte Fixzeitstempel dürfen nach Invalidierung keinen alten Versatz reaktivieren.

Die Erweiterung sendet weder Standortverläufe noch Prognosen an Träwelling und löst keinen automatischen Status-PUT aus. Das Bearbeitungsformular nutzt ausdrücklich den Resolver ohne GPS-Daten. Der API-Änderungsmonitor vergleicht weiterhin Providerwerte; lokale Prognoseschwankungen erzeugen keine Echtzeit-Änderungshinweise.

## Abhängigkeiten

`StationTrackingEngine`, `TrackingStop.plannedArrivalMillis` und `plannedDepartureMillis`, die bestehende Standortfreigabe sowie parsebare ISO-Zeitfelder der API. Es wird keine zusätzliche API, Preference oder Datenbank eingeführt.

## SEV: öffentliche Ersatzhaltestellen und fehlende App-Zuordnung

Die Codeprüfung vom 06.10.2026 bestätigt: `TripTrackingService.toTrackingStops()` übernimmt weiterhin `stop.station.latitude/longitude` aus der Träwelling-API. Die Android-App hat bislang keine Zuordnung physischer Ersatzhalte. Das neue Abrufwerkzeug exportiert öffentliche Quellen unabhängig von der App; es verändert weder API-Daten noch Tracking-Koordinaten. `category = bus`, Linienname und Betreiber sind allgemeine Verkehrsmitteldaten, kein eindeutiger Beleg für einen bestimmten SEV-Halt. Die nachgereichte Aufnahme einer Busfahrt RE1 Essen Hbf → Mülheim (Ruhr) Hbf → Duisburg Hbf zeigt Namen und Fahrplanzeiten, jedoch keine tatsächlich gelieferten Koordinaten oder Location-Callbacks.

### Öffentlicher Abruf auf bahnhof.de

Die öffentlichen Kartenseiten [Essen Hbf](https://www.bahnhof.de/essen-hbf/karte), [Mülheim (Ruhr) Hbf](https://www.bahnhof.de/muelheim-ruhr-hbf/karte) und [Duisburg Hbf](https://www.bahnhof.de/duisburg-hbf/karte) enthalten in ihren HTML-Daten GeoJSON-Features vom Typ `RAIL_REPLACEMENT_TRANSPORT`. Ein Feature mit `geometry.type = Point` enthält Koordinaten in der Reihenfolge **Längengrad, Breitengrad**, eine `sev.*`-Kennung und gegebenenfalls Richtungsbezeichnungen. Wegbeschreibungen und zeitweilige Einschränkungen stehen zusätzlich in den Kartendaten. Dafür ist kein RIS::Stations-Konto nötig. Die HTML-Struktur ist eine öffentliche Website-Ausgabe, kein zugesicherter API-Vertrag.

Das Werkzeug benötigt Python 3.10 oder neuer, nutzt dessen Standardbibliothek und benötigt keine zusätzliche Android-Abhängigkeit:

```bash
python3 tools/extract_bahnhof_sev.py essen-hbf muelheim-ruhr-hbf duisburg-hbf --output /tmp/sev-stops.json
```

Der [Beispielabruf](../../../tools/sev-stops-example.json) dokumentiert am 06.10.2026 drei Stationen mit fünf Ersatzhaltepunkten. Er ist eine Momentaufnahme, kein dauerhaft gültiges Haltestellenverzeichnis. Der Abgleich mit den zugehörigen öffentlichen Lageplänen bestätigt folgende veröffentlichte Punkte:

| Station | SEV-Kennung | Breitengrad | Längengrad | Zuordnung in der Quelle |
| --- | --- | --- | --- | --- |
| Essen Hbf | `sev.101840` | 51.45018831 | 7.0101172 | Ein Ersatzhalt an der Kruppstraße; keine getrennte Richtungsbezeichnung |
| Mülheim (Ruhr) Hbf | `sev.136938` | 51.43222557 | 6.88553272 | Duisburg; Oberhausen zusätzlich bis 09.10.2026 |
| Mülheim (Ruhr) Hbf | `sev.136949` | 51.43175246 | 6.88538831 | Essen |
| Duisburg Hbf | `sev.135989` | 51.42804102 | 6.77808449 | Essen / Oberhausen |
| Duisburg Hbf | `sev.135978` | 51.42987326 | 6.77724749 | Düsseldorf |

Der Werkzeugaufruf wurde mit den drei öffentlichen Kartenseiten erfolgreich ausgeführt. Ein unabhängiger Vergleich mit gesondert geladenen HTML-Daten bestätigt alle fünf Punkte. Zusätzliche manuelle Prüfungen verwerfen fehlende beziehungsweise abweichende Stationsdaten, anders typisierte Punkte und ungültige Koordinaten. Das ist ein Extraktionsnachweis, kein Android-, GPS- oder Geräteprüflauf.

In Essen liegt der veröffentlichte SEV-Punkt rund 349 Meter vom ebenfalls veröffentlichten Kartenmittelpunkt des Bahnhofs entfernt. Diese Entfernung bezieht sich auf die beiden Website-Punkte, nicht auf eine im Screenshot belegte API-Koordinate. Ein Kartenmittelpunkt ist kein Ersatzhalt und darf bei fehlendem SEV-Feature nicht als solcher exportiert werden.

### Richtung, Gültigkeit und PDF-Abgleich

Das Feld `properties.version` beschreibt den Bearbeitungsstand eines Features. Es ist weder der Beginn noch das Ende einer Maßnahme. Zeiträume müssen aus den zugehörigen Hinweisen und Richtungsbezeichnungen gelesen werden. Die Karten von Mülheim und Duisburg nennen temporäre Ersatzhalte vom 04.09. bis 30.10.2026; die Oberhausen-Ergänzung in Mülheim ist separat bis 09.10.2026 begrenzt. Die Richtungshinweise belegen nicht automatisch, an welchem Punkt ein in Duisburg endender Bus ankommt.

Die öffentlichen Lagepläne liefern ergänzende Orts- und Wegangaben:

- [DB-SEV-Lageplan Essen Hbf, Stand 22.04.2026](https://www.bahnhof.de/downloads/replacement-service-maps/1690.pdf): Der südliche Ausgang Freiheit führt zur Kruppstraße und zum Ersatzhalt bei DSV. Der QR-Code führt zur Bahnhofseite; er ist kein Koordinatennachweis. Auch der einzige PDF-Link verweist auf die Bahnhofseite. Die Dateinummer `1690` darf nicht mit einer Träwelling-Stations-ID oder IBNR gleichgesetzt werden.
- [DB-SEV-Lageplan Mülheim (Ruhr) Hbf, Stand 09.09.2026](https://www.bahnhof.de/downloads/replacement-service-maps/4219.pdf): Die Verlegung unterscheidet die Punkte in der Parallelstraße nach Fahrtrichtung, mit Duisburg oberhalb des Tourainer Rings und Essen vor der Brücke. Die zusätzliche Oberhausen-Frist steht ebenfalls im Plan und in den aktuellen öffentlichen Kartendaten.
- [DB-SEV-Lageplan Duisburg Hbf, Stand 04.09.2026](https://www.bahnhof.de/downloads/replacement-service-maps/1374.pdf): Für Düsseldorf ist der Halt am Osteingang vorgesehen; für Essen/Oberhausen der temporäre Punkt an der Neudorfer Straße bei Hausnummer 62.

[DB RIS::Stations](https://developers.deutschebahn.com/db-api-marketplace/apis/product/ris-stations) bleibt eine alternative strukturierte Quelle mit `GET /replacement-transport/stops/by-bounding-box`. Dieser Dienst benötigt einen genehmigten Zugang und einen abonnierten Nutzungsplan; eine authentifizierte Datenbankabfrage wurde hier nicht durchgeführt. Dieser Zugang ist keine Voraussetzung für das neue öffentliche Abrufwerkzeug. Dauerhafte Nutzungsbedingungen und die Wartbarkeit des Website-Abrufs bleiben vor einem regelmäßigen App-Datenbezug zu prüfen.

Eine spätere Zuordnung muss Stationskennung, konkreten Fahrtbesuch, Datum, Richtung und gegebenenfalls mehrere Kandidaten berücksichtigen. Nur eindeutig belegte physische Punkte dürfen die interne Tracking-Projektion ergänzen; API-Stations-ID, Stopover-UUID und Zeiten bleiben erhalten. Falsche Bezugspunkte können Ankunft, Aufenthalt, Abfahrt und Ansage beeinträchtigen. Zusätzlich können Straßenumwege den bestehenden geraden Prognosekorridor verlassen; Ersatzhaltkoordinaten allein gewährleisten deshalb keine Bus-ETA. Eine Bus-Routenprojektion ist ein eigenständiger Ausbau und kein Anlass, die GPS-Gültigkeitsgrenzen pauschal zu lockern.

## Offene Fragen

- TODO: Die exportierten öffentlichen SEV-Punkte eindeutig einer erkannten Ersatzverkehrsfahrt sowie Station, Besuch, Datum und Richtung zuordnen, bevor die Android-App sie für GPS verwendet. Für den gemeldeten RE1 die tatsächlich von der API gelieferten Stopover-Koordinaten und die Ankunftshaltestelle in Duisburg verifizieren. Fehlende oder mehrdeutige Ersatzhalte dürfen keine pauschale Koordinatenkorrektur auslösen.
- TODO: Website-Struktur, Quellenänderungen, Ablauf temporärer Verlegungen und Bedingungen eines regelmäßigen Abrufs prüfen. Der Beispielabruf vom 06.10.2026 ist kein automatischer Aktualisierungsdienst; Feature-Versionen sind keine Gültigkeitsintervalle.
- TODO: Den Nutzerbericht vom 06.10.2026 zur flackernden Quellenanzeige auf der S28 mit dem stabilisierten Prognosezustand und der aktuellen UI-Vergleichszeit erneut prüfen. Die nachgereichte Bildschirmaufnahme bei eingeschaltetem Display zeigt wechselnde GPS-/API-Quellen für denselben Besuch und Folgehalte, enthält aber keinen Standort- oder Audioverlauf. Insbesondere Bremsen, Ankunft und kurze Haltwechsel dürfen einen noch gültigen passenden Wert nicht unnötig verwerfen; echter Signalverlust muss weiterhin auf API/Plan zurückfallen. Prognosegüte bei Verfrühung, Verspätung, längerem Aufenthalt, Tunnel, Kurven und eng benachbarten Halten bleibt offen.
- TODO: Einheitliche Quellen-/Zeitdarstellung in Fahrtdetail, Widget und Samsung-Sperrbildschirm bei Display-aus-Betrieb und wiederkehrendem Signal prüfen. Reine Kotlin-Tests belegen keine reale ETA-Güte.

## Verwandte Seiten

- [TripTracking](./trip-tracking.md)
- [StatusDetail](./status-detail.md)
- [Reisefortschritt](./trip-progress.md)
- [Widget](./widget.md)
- [Datenmodell](../daten/datenmodell.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Tests](../entwicklung/tests.md)
- [Offene Fragen](../offene-fragen.md)
