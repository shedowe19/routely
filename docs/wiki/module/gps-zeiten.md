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

## Offene Fragen

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
