# Modul: GPS-gestützte Reisezeiten

## Zweck

Aktualisiert die angezeigten Zeiten der eigenen aktiven Fahrt anhand eines zuverlässig zugeordneten GPS-Verlaufs. Bei fehlender geeigneter Beobachtung nutzt die Anzeige wieder die manuellen Check-in-Zeiten, API-Echtzeit oder den Fahrplan. Providerfelder und gespeicherte Check-in-Zeiten werden nicht durch Schätzwerte ersetzt.

## Kontext

`TripTrackingService` erzeugt nach der geordneten Halterkennung ein prozesslokales `GpsJourneyTimes`. `TrackingLiveState.gpsTimes` versorgt Fahrtdetail, Fahrtbenachrichtigung und Widget. `JourneyTimeResolver` entscheidet für jede Ankunft und Abfahrt einzeln, welche Zeit angezeigt wird. GPS-Zeiten sind nur für den passenden eigenen aktiven Status verfügbar; fremde und vergangene Fahrten erhalten sie nicht.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/GpsJourneyTimeEstimator.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingLocationObservation.kt`
- `app/src/main/kotlin/de/traewelling/app/data/model/RoadRouteModels.kt`
- `app/src/main/kotlin/de/traewelling/app/data/routing/RoadRouteRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/routing/RoadRouteParser.kt`
- `app/src/main/kotlin/de/traewelling/app/data/model/TransitRouteModels.kt`
- `app/src/main/kotlin/de/traewelling/app/data/routing/TransitRouteRepository.kt`
- `app/src/main/kotlin/de/traewelling/app/data/routing/TransitRouteParser.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TransitRouteTracking.kt`
- `app/src/main/kotlin/de/traewelling/app/service/TrackingRouteGeometry.kt`
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

Ein GPS-Datensatz gilt höchstens 30 Sekunden ab seinem Fixzeitpunkt. Eine UUID identifiziert bevorzugt den konkreten Besuch; auch bei gleicher UUID muss das gesamte nullable Paar aus geplanter Ankunft und Abfahrt unverändert sein. Ohne UUID benötigt die Zuordnung zusätzlich dieselbe vorhandene Station-ID und mindestens einen parsebaren Planmarker; beide Planmarker müssen einschließlich ihrer Anwesenheit exakt passen. Hinzufügen oder Entfernen eines Markers verwirft damit einen früheren GPS-Wert, auch wenn der andere Marker gleich bleibt. Mehrere passende Besuche bleiben mehrdeutig und verwenden den Rückfall. Gestrichene Halte erhalten keine GPS-Zeit.

Die Gültigkeitsprüfung benötigt einen aktuellen Vergleichszeitpunkt: In [StatusDetail](./status-detail.md) wird die Systemzeit bei jeder Zusammensetzung gelesen, auch wenn neue GPS-Daten zwischen zwei UI-Ticks eintreffen. Die ältere Tickzeit darf einen bereits eingetroffenen Fix nicht scheinbar in die Zukunft versetzen. Ein weiterhin sekündlicher Tick prüft den Ablauf bei ausbleibenden Updates. Tatsächlich zukünftige oder abgelaufene GPS-Datensätze bleiben ungültig; weder Schätzergrenzen noch Gültigkeitsdauer werden für die Anzeige gelockert.

## Konservative GPS-Auswertung

Die Android-Beobachtung wird vor Engine und Schätzer durch `TrackingLocationClock` normalisiert. Alter und Reihenfolge stammen aus `elapsedRealtimeNanos`; der Ereigniszeitpunkt wird einmal aus aktueller Systemzeit minus monotonem Alter gebildet. API-/Uhr-Replays desselben Fixes behalten genau diesen ursprünglichen Zeitpunkt. Ein alter Batch, ein zukünftiger monotoner Marker oder ein Fix über 30 Sekunden bestätigt keine neue Bewegung. Ein älterer genauerer Fix darf einen bereits beobachteten neueren Marker nicht überholen.

Springt die Systemzeit gegenüber der monotonen Uhr um mehr als eine Sekunde, setzt der Service `StationTrackingEngine.resetLocationClock()` und den Zeitschätzer zurück. Besuchscursor und bereits gesprochene Schlüssel bleiben erhalten, frühere GPS-Zeiten und transiente Positionsbelege werden verworfen. Der Adapter behält seine monotone Reihenfolge; ein bereits verbrauchter Fix wird nicht mit neuer Systemzeit nochmals als Beobachtung angelegt. Ein strikt neuer Fix muss die bestehende räumliche Zuordnung wieder unterstützen. Diese Prüfung erfolgt auch bei API-/Uhr-Ticks mit vorhandener Position und ersetzt keine manuellen Check-in- oder Providerzeiten.

Vor gewöhnlicher physischer Cursor-Mutation prüft die Stationsengine einen Kurzsprung gegen die vorherige unterstützte Position: Bei einem Fixabstand von einer Millisekunde bis 30 Sekunden gilt höchstens `100 m/s × Zeitabstand + beide Genauigkeiten`. Ein verworfener Sprung verbraucht seinen Zeitmarker, liefert `TIMETABLE` und bestätigt weder Haltefortschritt, Ansage noch Zielabschluss. Er ersetzt die vorherige plausible Position nicht. Die getrennte konservative Zeitauswertung bleibt zusätzlich erforderlich; ein nachträglich verworfener ETA-Wert könnte einen zuvor falsch bestätigten Halt nicht rückgängig machen. Längere Lücken verwenden weiterhin die gesonderte Wiederverankerung.

Der Schätzer setzt einen bereits räumlich etablierten Besuchscursor der `StationTrackingEngine` voraus. Frische Position allein genügt nicht. Fixes müssen gültige Koordinaten und eine Genauigkeit von höchstens 75 Metern haben; ihr Zeitstempel darf weder in der Zukunft liegen noch älter als 30 Sekunden sein. Doppelte Zeitstempel und reine Uhr-/API-Ticks zählen nicht als zusätzliche Bewegung oder Aufenthaltsdauer.

Nach mehreren Halten ohne GPS kann die [Stationsengine den Besuch gesondert wiederverankern](./trip-tracking.md). Während ein späterer Halt noch unabhängige frische Positionsbelege benötigt, liefert sie die Fahrplanquelle; der Schätzer verwirft dadurch die alte lokale Zeitbasis und meldet den noch unbestätigten Besuch. Nach Bestätigung beginnt die Auswertung auf dem neu zugeordneten Besuch. Wiederverankerung erzeugt keine Zeiten für übersprungene Tunnelhalte und garantiert keine sofortige neue GPS-Prognose: Ankunftsbeobachtung, Bewegungsfenster und Abschnittsprojektion müssen weiterhin ihre eigenen Kriterien erfüllen.

Eine beobachtete Ankunft setzt bestätigten Ankunftsstatus und `Entfernung + Genauigkeit <= 120 m` voraus. Zwei langsame Fixes mit höchstens 3 m/s oder mindestens acht Sekunden stabiler Aufenthalt bestätigen die Beobachtung. Gespeichert wird der Zeitpunkt des ersten unterstützten inneren Fixes. Dieser Ankunftswert bleibt während der gültigen GPS-Beobachtungsfolge stabil, auch wenn das Fahrzeug weiter wartet. Eine frühe beobachtete Ankunft prognostiziert keine unbestätigte Frühabfahrt: Die aktuelle Folgeabfahrt wartet mindestens bis zum Plan. Sobald die geplante Abfahrt überschritten wird, erhöht das Warten den Zeitversatz für kommende Halte.

Am Einstieg liefert stationäres Warten grundsätzlich noch keinen GPS-Versatz für die Weiterfahrt, auch wenn eine Planankunft vorhanden ist. Die Telefonposition am Bahnsteig beweist keine Zugverspätung; vorhandene API-Abfahrt bleibt maßgeblich. Erst unterstützte gerichtete Weiterbewegung kann dort eine Prognose etablieren. Wird das Tracking erst nach einer verfrühten Abfahrt gestartet, darf die `StationTrackingEngine` den geordneten Folgehalt bereits vor der Plan-/API-Abfahrt räumlich etablieren. Eine ausreichend lange gerichtete Fixfolge kann anschließend auch eine frühere GPS-Ankunft prognostizieren; die zukünftige API-Abfahrt blockiert diesen Ablauf nicht.

Zwischen Halten wird der Fortschritt auf den Abschnitt zwischen dem letzten nicht gestrichenen Halt und dem aktuellen Besuch projiziert. Für Bahn-/Tramfahrten kann ein eindeutig visitgebundener Träwelling-Linienzug die bisherige gerade Haltverbindung ersetzen. Ohne geeigneten Linienzug bleibt die bisherige konservative Geradenprojektion verfügbar. Bei Bus-RE/RB-Ersatzverkehr benötigt diese Abschnittsprognose dagegen weiterhin eine passende validierte Straßen-Geometrie; fehlende Geometrie wird nicht durch die Bahnhofsluftlinie ersetzt. Eine neue Prognose benötigt mindestens drei gerichtete Fixes über acht Sekunden und mindestens `max(50 m, 3 × Genauigkeit)` unterstützte Bewegung. Das Beobachtungsfenster wird nach verstrichener Zeit begrenzt, nicht auf acht Location-Callbacks: Mindestens eine Sekunde zwischen gespeicherten Stichproben und höchstens 32 Stichproben decken bis zu 30 Sekunden ab. Auch häufige Updates können damit die erforderlichen acht Sekunden belegen. Der aktuelle Fix geht zusätzlich in die Bewegungsauswertung ein.

Der Korridor der jeweils verwendeten Geometrie erlaubt höchstens `max(100 m, 2 × Genauigkeit)` Querabweichung. Geraden-/Straßenabschnitte sind 100 Meter bis 50 Kilometer, native Bahn-/Tramabschnitte bis 500 Kilometer lang. Die geplante Fahrzeit muss weiterhin zwischen 15 Sekunden und 90 Minuten liegen; neue Prognosen verwenden 5 bis 98 Prozent der Abschnittslänge. Ein Linienzug verändert die räumliche Projektion, nicht die GPS-Qualitäts-, Zeit- oder Bewegungsgrenzen.

GPS-Besuchsfortschritt und GPS-Zeitprognose sind getrennte Auswertungen. Die Stationsengine akzeptiert für ihren Cursor eine gemeldete Ungenauigkeit bis 100 Meter; die Zeitauswertung benötigt höchstens 75 Meter und zusätzliche zeitliche/räumliche Belege. Deshalb bedeutet `Fortschritt per GPS` nicht, dass bereits eine lokale Ankunftsprognose vorliegt. Vor der Straßenprojektion betrug der gerade Abschnitt der veröffentlichten SEV-Punkte Duisburg → Mülheim ungefähr 7,47 Kilometer; dessen Fünf-Prozent-Grenze lag rund 373 Meter hinter dem Beginn. Bei einer Straßen-Geometrie richten sich Länge und Fortschritt nach dem gewählten Linienzug. Mindestfixfolge und gerichtete Bewegung bleiben auch dort erforderlich.

Der Nutzerbericht vom 06.10.2026 zur RE1-Busrückfahrt Duisburg → Mülheim → Essen zeigt GPS-Fortschritt mit Fahrplanzeiten. Die spätere Aufnahme zeigt ausdrücklich die Diagnose `OUTSIDE_CORRIDOR`: Der damalige Schätzer akzeptierte die Position nicht für seinen geraden Abschnitt. Sie belegt weder den vollständigen Fixverlauf noch den tatsächlichen Straßenweg oder beobachtete Ankunft/Abfahrt. Die neue optionale Straßenprojektion adressiert diese Geometriegrenze; ein GPS-Signal allein rechtfertigt weiterhin keine Zeitverschiebung.

Der Versatz ergibt sich aus dem Fixzeitpunkt gegenüber dem räumlich interpolierten Zeitpunkt im geplanten Fahrintervall. Kommende Planzeiten werden um diesen Versatz verschoben. Ein Abstand geteilt durch momentane Geschwindigkeit wird nicht als Ankunftsprognose verwendet. Beobachtete Abfahrt benötigt vorherigen beobachteten Aufenthalt und geordnete Abfahrtsbewegung; sie kann auf langen Segmenten schon vor der Fünf-Prozent-Prognosegrenze erfasst werden. Sie bezeichnet den unterstützten Bewegungsfix nach dem Halt, nicht den exakten Zeitpunkt des Türschließens. Unplausible Sprünge, Rückwärtsbewegung und Versatzbeträge über sechs Stunden erzeugen keine GPS-Zeit.

## Beobachtete Ereignisse ohne Zukunftsprognose

Eine bestätigte Ankunft beziehungsweise Abfahrt wird unabhängig von einem berechenbaren Prognoseversatz veröffentlicht. Vor der Korrektur vom 06.10.2026 gab der Schätzer ohne `supportedOffset` nur eine vorhandene Prognose zurück; intern bereits erfasste Ereignisse konnten dadurch unsichtbar bleiben. Diese Veröffentlichungslücke ist im Code belegt, nicht als konkrete Ursache des Nutzer-Screenshots nachgewiesen.

`publishObservedOnly()` liefert ausschließlich tatsächlich beobachtete Ereignisse bis zum aktuellen eindeutig zugeordneten Besuch. Eine beobachtete Ankunft behält die bisherige Voraussetzung einer geplanten Ankunft; eine Abfahrt benötigt den bestehenden bestätigten Aufenthalt und die geordnete Abfahrtsbeobachtung. Fehlender Korridor-/Bewegungsbeleg für eine Zukunftsprognose erzeugt weder einen synthetischen Versatz noch Zeiten für folgende unbesuchte Halte. Diese Ereignisse erhalten `GPS beobachtet`; fehlende Zukunftszeiten verwenden weiterhin API-/Planwerte.

Die Veröffentlichung bleibt an zuverlässige, geordnete Fixes, passende Besuchsidentität und höchstens 30 Sekunden GPS-Gültigkeit gebunden. Ereigniszeitpunkte bleiben die ursprünglichen Beobachtungen; ein unterstützender neuer Fix verändert sie nicht. UI-/API-Ticks und derselbe erneut verwendete Fix verlängern keine Gültigkeit. Beim Ergänzen tatsächlicher Ereignisse in eine noch gültige Prognose bleiben deren ursprünglicher Unterstützungszeitpunkt und Ablauf erhalten. Standortinvalidierung, Routen-/Koordinatenwechsel und Service-Neustart behalten die bestehenden Schutzregeln.

## Erklärung einer fehlenden Prognose

`GpsTimeUnavailableReason` und `GpsJourneyTimeEstimator.unavailableReason()` benennen den aktuellen Grund einer fehlenden Zukunftsprognose. Der Service publiziert ihn in `TrackingLiveState.gpsTimeUnavailableReason`; der [StatusDetail-Timeline-Kopf](./status-detail.md) zeigt dazu einen kurzen Hinweis. Gründe sind fehlendes frisches GPS, unzureichende Genauigkeit, unbestätigter Besuch, nicht unterstützte Streckendaten, Warten am Einstieg, fehlende sichere Projektion auf den verwendeten Abschnitt, fehlender Bewegungsbeleg oder unplausible Bewegung. Die Straßenprojektion ergänzt `ROUTE_GEOMETRY_UNAVAILABLE` für einen fehlenden geeigneten Straßenweg und `AMBIGUOUS_ROUTE` für mehrdeutigen Fortschritt auf möglichen Wegen.

`OUTSIDE_CORRIDOR` behauptet keine tatsächliche Abweichung vom offiziellen Fahrweg. Der UI-Text erläutert stattdessen, dass sich die GPS-Position dem aktuellen Streckenabschnitt noch nicht sicher zuordnen lässt. Die verwendete Basis kann ein geeigneter Träwelling-Linienzug, die bisherige gerade Haltverbindung oder das getrennte SEV-Straßenmodell sein. Nach einem Tunnel kann zusätzlich zunächst die alte Besuchsbasis nicht mehr zur Position passen. Haltbasierte Wiederverankerung und Geometrieverfügbarkeit bleiben unterschiedliche Belege; keine der Quellen garantiert den aktuell befahrenen offiziellen Weg.

Ein Hinweis kann zugleich mit einer gültigen beobachteten Ereigniszeit erscheinen: Er beschreibt die Zukunftsprognose, nicht pauschal jede lokale GPS-Zeit. Bei Ablauf eines Datensatzes zwischen Service-Updates zeigt der UI-Tick den Hinweis auf fehlendes frisches GPS. Die Diagnose verändert keine Zeitquelle und rekonstruiert keinen früheren Fixverlauf. Eine ausdrücklich eingeblendete Diagnose belegt nur den aktuellen Ablehnungszustand. Die frühere Veröffentlichungskorrektur beobachteter Ereignisse ergänzte noch keine Straßenroute; die getrennte Erweiterung darunter verändert die Geometrie, ohne die GPS-Grenzen zu lockern.

`TrackingLiveState.locationError` und `speechError` sind getrennte Laufzeitdiagnosen: Sie erklären beispielsweise eine fehlgeschlagene Standortregistrierung oder eine nicht verfügbare Sprachengine. Sie erzeugen keine neue GPS-Zeit oder Haltzuordnung. Begrenzte Registrierungs-/TTS-Retries und die Lebensdauer dieser Fehler stehen unter [TripTracking](./trip-tracking.md).

## Bahn-/Tramabschnitte mit Träwelling-Streckenverlauf

Die aktive Begleitung lädt für Bahn-, S-Bahn-, U-Bahn- und Tramkategorien `GET /api/v1/polyline/{statusId}` vom eigenen Träwelling-Server. Busmodus und SEV werden ausgeschlossen. Der Abruf betrifft den vollständigen eigenen Check-in von eindeutigem Einstieg bis eindeutigem Ziel; er ist weder eine fremde Live-Karte noch eine neue Reiseplanung. Die Anfrage enthält Status-ID und passende Anmeldung, keine GPS-Geräteposition. Der [native Vertrag](../api/externe-schnittstellen.md) kann gespeicherte Streckenabschnitte mit Stationssehnen mischen; die App bezeichnet ihn als `Träwelling-Streckenverlauf`, nicht als garantiert amtliche aktuelle Gleisführung.

`TransitRouteParser` prüft genau ein zum Status passendes `LineString`-Feature, endliche GeoJSON-Koordinaten, maximal vier MiB Antwort und 40.000 Punkte. Zwei bis 256 geordnete Haltbesuche bilden die lokale Basis. Einstieg und Ziel müssen zur Form passen; jeder Halt muss innerhalb von 250 Metern in eine insgesamt eindeutige monotone Zuordnung passen. Lokal bleiben nur Kandidaten bis 15 Meter über dem besten Haltabstand; nahe Minima innerhalb von 50 Metern Linienlänge zählen als derselbe Anker. Mehrere vollständige Zuordnungen, etwa an Schleifen, werden abgelehnt. Die Gesamtform ist auf 3.000 Kilometer, einzelne Kanten auf zehn Kilometer und verwendete Abschnitte auf 100 Meter bis 500 Kilometer begrenzt.

Eine Abschnittsform braucht mindestens einen inneren Punkt mit 15 Metern Querabstand sowohl zur lokal projizierten direkten Verbindung als auch zum sphärischen Großkreis zwischen den Halten. Eine reine oder nur dichter mit Punkten besetzte Stationssehne genügt nicht; auch geodätisch verdichtete künstliche Verbindungen werden dadurch nicht als Kurve ausgegeben. Gestrichene Besuche bleiben für die Formzuordnung erhalten; verwendete Abschnitte verbinden die aufeinanderfolgenden bedienten Besuche. Dabei muss jede ursprüngliche Teilpaarung, auch vor und nach einem gestrichenen Halt, ihren eigenen Kurvenbeleg besitzen. Ein bloßer Knick an diesem Halt darf daher keinen Schienenweg vortäuschen. Die Prüfung ist ein geometrischer Plausibilitätsbeleg, keine Herkunftszertifizierung des Backends. Deshalb können auch tatsächlich gerade Gleisabschnitte die zusätzliche Linienzugnutzung verlieren und auf die bestehende Geradenprojektion zurückfallen.

`TrackingRouteGeometry` verwendet denselben lokalen Linienzug für Zeitschätzer und gerichtete Fortschrittshilfen der Stationsengine. Kurze Endpunktverbindungen bis 250 Meter bewahren die ursprünglichen physischen Haltkoordinaten. Nicht benachbarte nahe Wegäste müssen in ihrem Längenfortschritt übereinstimmen; der nächste Punkt allein ist kein Fortschrittsbeleg. Physische Ankunftsbereiche, Zielaufenthalt, Wiederverankerung und der normale Ansageradius bleiben gesonderte Halt-/GPS-Regeln. GPS-Qualität, drei gerichtete Fixes, acht Sekunden Bewegung und 30 Sekunden Zeitgültigkeit werden nicht gelockert.

Die gesamte Form wird asynchron außerhalb der Tracking-Sperre geladen. Sessionrevision, Fahrtgeneration, Tripidentität und die vollständige geordnete Besuchs-/Plan-/Koordinatenbasis müssen bei Übernahme weiterhin passen. Echtzeit- oder Gleisänderungen allein verändern diese Formbasis nicht. Gültige Geometrie bleibt höchstens 15 Minuten verfügbar; nach 14 Minuten darf ein weicher Refresh starten. Ein fehlgeschlagener Refresh verlängert den ursprünglichen Ablauf nicht. Repository und Service halten die Form nur im RAM; nach Neustart wird sie erneut geladen. Einzelheiten zu acht Cacheeinträgen, vier laufenden Abrufen, zweiminütigem Fehlversuchcache und den HTTP-Grenzen stehen unter [Externe Schnittstellen](../api/externe-schnittstellen.md).

Ein tatsächlicher Form- oder Quellenwechsel desselben aktiven Besuchspaars setzt Bewegungsbeleg und Zukunftsprognose zurück; bereits bestätigte Istereignisse bei gleicher Haltbasis und verbrauchte Fixzeitstempel bleiben erhalten. Ein geänderter Abrufzeitpunkt derselben Form oder eine zusätzlich geladene Folgeform ist kein neuer Bewegungsbeleg. Keine Form erzeugt während eines Tunnels GPS-Fixes, rückwirkende Istzeiten oder sichere Zielankunft. Fehlt eine passende Zukunftsprognose, folgt weiter GPS-Ereignis, manuelle Zeit, API-Echtzeit und Plan nach dem jeweiligen Ereignis. Die Entscheidung steht in der [ADR zum Streckenverlauf](../entscheidungen/2026-10-06-native-streckenverlaeufe.md).

## Optionale Straßenprojektion für SEV

Für zwei aufeinanderfolgende, nicht gestrichene und eindeutig aus bahnhof.de aufgelöste Ersatzhalte lädt der Service eine Straßen-Geometrie über den öffentlichen FOSSGIS-OSRM-Dienst. Angefragt werden ausschließlich die beiden veröffentlichten Haltkoordinaten, niemals der Gerätefix oder die GPS-Historie. Das Pkw-Profil beschreibt einen möglichen Straßenweg; es ist kein veröffentlichter SEV-Busfahrweg und berücksichtigt nicht zuverlässig die konkrete Baustellenführung oder Sonderrechte des Busses.

`RoadRouteParser` akzeptiert nur erfolgreiche, begrenzte GeoJSON-Linienzüge mit plausiblen Koordinaten, Längen und höchstens 150 Metern Abweichung der gerouteten Endpunkte zu den angefragten Halten. Bis zu drei Routenkandidaten bleiben verfügbar. Der Schätzer prüft den GPS-Verlauf lokal gegen diese Geometrien; eine mehrdeutige Projektion oder eine Position außerhalb aller passenden Wege liefert keine bewegungsgestützte Abschnittsprognose. Eine native API-Polyline ohne belegte Herkunft und eine gerade Verbindung zwischen Bahnhofspunkten werden für SEV nicht als Ersatzweg verwendet.

Kurze Verbindungen von höchstens 150 Metern zwischen den öffentlichen Ersatzhaltpunkten und den vom Router auf Straßen eingerasteten Endpunkten erhalten die physischen Ankunftskoordinaten. Die Projektion darf den Halt nicht zum Straßenknoten versetzen. An Kreuzungen müssen plausible Projektionen auf demselben Linienzug im räumlichen Fortschritt übereinstimmen. Zusätzlich wird ein Sprung entlang des Weges gegen höchstens 100 m/s über die verstrichene Fixzeit plus Genauigkeitstoleranz geprüft; eine Schleife darf durch nahe beieinander liegende Punkte keinen großen Fortschrittssprung erzeugen.

Ein Routenkandidat kann für den aktuellen Abschnitt gebunden werden, nachdem mindestens drei ausschließlich zu diesem Kandidaten passende gerichtete Fixes über mindestens acht Sekunden und `max(50 m, 3 × Genauigkeit)` Bewegung vorliegen. Danach darf der belegte Weg auf einer wieder gemeinsam genutzten Straße weitergelten, statt allein wegen erneut passender Alternativen die Quelle zu wechseln. Diese Bindung entfällt bei mehrdeutiger Projektion innerhalb des gebundenen Wegs, Position außerhalb seines Korridors, Formwechsel desselben Besuchspaars, Abschnittswechsel oder ungültigem GPS. Sie bestätigt keinen offiziellen Busfahrweg und lockert keine Bewegungs-/Zeitgrenze.

Der Abruf läuft getrennt vom API-Polling und von Standort-Callbacks. Das Fenster umfasst höchstens den aktuellen Ankunftsabschnitt und dessen Folgeabschnitt; am Einstieg ohne Ankunftsabschnitt die ersten zwei passenden Abschnitte. Fehlende bestätigte Ersatzhalte werden nicht überbrückt. Übernahme setzt dieselbe Fahrtgeneration, Besuchspaarung und physischen Endpunkte voraus. Geometrien bleiben im RAM: Der Service hält höchstens acht besuchsbezogene Abschnitte, das Repository höchstens 64 Endpunktpaare mit Erfolg höchstens 24 Stunden und Fehlversuch 15 Minuten. Nach einem Service-/Prozessneustart müssen benötigte Wege erneut verfügbar werden; ein bereits laufender Prozess kann gültige geladene Wege bei Netzausfall weiter nutzen.

Die Projektion misst räumlichen Fortschritt entlang eines Linienzuges. Der Zeitversatz bleibt der Vergleich mit dem geplanten Fahrintervall; die OSRM-Fahrtdauer wird nicht zur Bus-ETA. Nur Endpunkte und Linienzüge des aktuell eingehenden Abschnitts bilden die Geometriebasis. Ein anderer Abrufzeitpunkt desselben Wegs oder das Eintreffen einer vorgeladenen Folgegeometrie setzen sie nicht zurück. Eine Formänderung desselben Besuchspaars setzt Bewegung und Zukunftsprognose zurück, erhält aber bereits bestätigte tatsächliche Ereignisse bei unveränderter Besuchs-/Haltbasis. Ein geordneter Abschnittswechsel sammelt Bewegung und Kandidatenbindung neu; eine noch gültige Prognose darf bei weiterhin kompatibler frischer Position bis zum ursprünglichen Ablauf erhalten bleiben. Ein eintreffender Weg wird nicht mit einem alten Fix als neuer Bewegungsbeleg ausgewertet. Halterkennung, Ansageradius und Zielabschluss der `StationTrackingEngine` bleiben unverändert.

Fehlender, abgelaufener, unpassender oder mehrdeutiger Straßenweg verhindert die Abschnittsprognose. Bestätigte tatsächliche Ereignisse bleiben davon getrennt; ein bestätigter Aufenthalt am passenden Zwischenhalt kann weiterhin den bestehenden Planversatz stützen, ohne den Straßenweg zu kennen. Ohne geeignete lokale Zeit verwendet die Anzeige den normalen Zeitquellenrückfall. Die [Detailansicht](./status-detail.md) nennt OSRM/OpenStreetMap und bietet einen Link zur Kartenkorrektur. Schnittstelle, Abrufgrenzen und Anbieterbedingungen stehen unter [Externe Schnittstellen](../api/externe-schnittstellen.md).

## Rückfall und Lebensdauer

Eine bereits unterstützte Prognose benötigt nicht bei jedem Bremsfix oder Haltwechsel erneut die gesamte Mindestbewegung. Fehlt kurzzeitig ein neuer berechenbarer Versatz, kann eine frische, zur geordneten Haltfolge und zum Korridor passende Position das bisherige Ergebnis bis zu dessen ursprünglichem Gültigkeitsende erhalten. Das gilt auch bei Ankunft vor abgeschlossener langsamer Ankunftsbeobachtung und beim geordneten Übergang in den folgenden Abschnitt. Liegt ein bereits etablierter aktueller Besuch im bestätigten inneren Ankunftsbereich (`Entfernung + Genauigkeit <= 120 m`), darf ein Fix leicht hinter dessen Stationskoordinate das alte Ergebnis bis zum Ablauf erhalten. Die Querabweichung, Richtung und Sprungprüfung bleiben erforderlich; der zusätzliche Endbereich liefert ohne weiteren Beobachtungsbeleg keinen neuen Versatz. Der alte Unterstützungszeitpunkt und die höchstens 30 Sekunden Gültigkeit werden dabei nicht verlängert; eine neue Prognose braucht weiterhin die vollständigen Beobachtungskriterien.

Alter, Ungenauigkeit, fehlende Koordinaten, unklare Besuchsidentität, ein unplausibler Sprung oder eine Position außerhalb des unterstützten Korridors verhindern die Prognose und erhalten kein altes Ergebnis. Erkennbares Zurückfahren außerhalb der Genauigkeitstoleranz verwirft die Prognose ebenfalls. Kurvige Strecken, parallele Wege und spärliche Haltfolgen können deshalb trotz empfangenem GPS auf API-/Planzeit zurückfallen. Die Quellenwahl für die Zeit ist unabhängig von der Herkunft des Besuchscursors: Ein räumlich etablierter Cursor bleibt bei einem vorübergehenden Signalverlust erhalten, während die Uhrzeit wieder `API-Echtzeit` oder `Fahrplan` zeigen kann.

Die GPS-Beobachtungen, Fixfolge und Prognosen bleiben ausschließlich im RAM. Ungültiger Standortzustand verwirft sie; ein Service-Neustart übernimmt keine frühere GPS-Zeit aus dem Cache. Änderungen an Besuchsfolge, Planzeiten, Koordinaten, Streichungen oder manuellen Check-in-Zeiten setzen die betreffende Prognosebasis zurück; veränderte API-Echtzeit allein verwirft den räumlich gestützten Planversatz nicht. Eine Formänderung des Straßenwegs desselben Besuchspaars erhält bestätigte Ereignisse derselben Haltbasis, setzt aber Prognose und Bewegung zurück. Bereits verbrauchte Fixzeitstempel dürfen nach Invalidierung keinen alten Versatz reaktivieren.

Die Erweiterung sendet weder Standortverläufe noch Prognosen an Träwelling und löst keinen automatischen Status-PUT aus. Das Bearbeitungsformular nutzt ausdrücklich den Resolver ohne GPS-Daten. Der API-Änderungsmonitor vergleicht weiterhin Providerwerte; lokale Prognoseschwankungen erzeugen keine Echtzeit-Änderungshinweise.

## Abhängigkeiten

`StationTrackingEngine`, `TrackingStop.plannedArrivalMillis` und `plannedDepartureMillis`, die bestehende Standortfreigabe sowie parsebare ISO-Zeitfelder der API. Die reine GPS-Zeitauswertung führt selbst keine Netzwerkanfrage aus. `TransitRouteRepository` lädt native Träwelling-Linienzüge; die getrennte [SEV-Ergänzung](./sev-haltestellen.md) belegt physische Haltpunkte und `RoadRouteRepository` kann dazu öffentliche Straßen-Geometrien laden. Beide verwenden OkHttp/Gson ohne neues Routing-SDK, Preference oder Datenbanktabelle.

## SEV: öffentliche Ersatzhaltestellen und lokale App-Zuordnung

Der anfängliche Quellenabgleich vom 06.10.2026 stellte fest, dass `TripTrackingService.toTrackingStops()` ausschließlich `stop.station.latitude/longitude` aus der Träwelling-API übernahm. Die nun integrierte [SEV-Ergänzung](./sev-haltestellen.md) lädt für Busfahrten mit RE-/RB-Linienkennung automatisch öffentliche Bahnhofskarten. Ein eindeutig zugeordneter, aktueller und richtungs-/datumsabhängig gültiger Punkt ersetzt nur die Koordinate in der internen Tracking-Projektion; fehlende oder mehrdeutige Ergebnisse erhalten die API-Koordinate. Fahrtdetailzeilen zeigen Quelle, Richtung, Wegbeschreibung und gegebenenfalls den Grund einer unbestätigten Position. Das weiterhin vorhandene Abrufwerkzeug exportiert Quellen für die Entwicklung unabhängig von der Android-App.

Bus-Kategorie allein belegt keinen Ersatzverkehr. Die App-Erkennung ist deshalb auf Bus-RE/RB-Kandidaten begrenzt und muss zusätzlich die jeweilige Quellenzuordnung bestehen. Die nachgereichte Aufnahme der Busfahrt RE1 Essen Hbf → Mülheim (Ruhr) Hbf → Duisburg Hbf zeigt Namen und Fahrplanzeiten, jedoch keine tatsächlich gelieferten Koordinaten oder Location-Callbacks.

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

Die lokale Auflösung prüft Station und konkreten Fahrtbesuch, Quellenalter, Datum und Richtung anhand der vollständigen API-Haltfolge. Nur eindeutig belegte physische Punkte ergänzen die interne Tracking-Projektion; API-Stations-ID, Stopover-UUID und Zeiten bleiben erhalten. Quellenalter und Maßnahmenende werden bei API-, GPS- und Fahrplanupdates erneut geprüft. Ändert sich der physische Punkt, wird die GPS-Zeitbasis invalidiert und die bisherige Ankunft an diesem Punkt nicht ungeprüft übernommen. Die genauen Grenzen und der Cache stehen unter [SEV-Ersatzhaltestellen](./sev-haltestellen.md).

Ein breites gültiges Kartenintervall überdeckt keine weitere nicht auswertbare `ab`-/`bis`-Einschränkung. Ein abgelaufener erster Richtungsort darf ebenfalls nicht durch einen späteren Gegenrichtungsort ersetzt werden. Diese Prüfungen schützen bereits den physischen Haltbeleg; ohne ihn wird keine darauf beruhende Straßenprognose geladen. Das öffentliche Anfragefenster benötigt außerdem eindeutig zugeordnete und richtig geordnete Check-in-Grenzen.

Falsche Bezugspunkte können Ankunft, Aufenthalt, Abfahrt und Ansage beeinträchtigen. Ersatzhaltkoordinaten allein gewährleisten keine Bus-ETA. Die zusätzliche OSRM-Straßenprojektion muss zur beobachteten Fahrt passen und darf nicht als offizieller Busfahrweg gelten; ungeeignete Geometrie fällt auf API-/Planwerte zurück.

### Kumulative Abfahrt und physische Endpunkte

- G5/G7 sind korrigiert: Die Abfahrtsbeobachtung verwendet kumulierte Bewegung seit einem bestätigten inneren Haltaufenthalt sowie ein frisches vorwärts gerichtetes Fixpaar beim geordneten Besuchsübergang. Der geprüfte gewöhnliche Übergang verliert durch häufige kleine Fixpaare keinen Abfahrtsbeleg. Die unten dokumentierten Nahhalt-/Forecastgrenzen G9/G10 bleiben davon getrennt. Signalpausen, verworfene Sprünge und ungeeignete Projektionen erlauben weiterhin keine Beobachtung.
- Die Geometrie wählt die nächstliegende Kandidatur auf dem gesamten Linienzug, einschließlich ausgeschlossener Endpunktprojektionen. Ein ungestützter physischer Endpunkt darf nicht durch die benachbarte innere Kante ersetzt werden. Kurze/verdichtete Anfangs- und Endkanten, gültige Kurven sowie bestätigte innere Ankunftszonen haben getrennte Regressionen; eine Prognose bleibt vom physischen Zielabschluss getrennt.

## Offene Fragen

- TODO: G8–G10 des [weiteren Main-Nachreviews](../entwicklung/main-review-2026-10-06.md#weiterer-nachreview-von-main-443d6e1) beheben. Native Bootstrap-/Gap-Pfade verlangen aktuell noch 35 Meter im letzten Fixpaar; 24 Meter alle drei Sekunden können trotz kumulierter Weiterfahrt den alten Besuch halten. Bei 180-Meter-Haltabstand kann ein früher Cursorwechsel die noch ausstehende Istabfahrt dauerhaft sperren. Fehlende nächste Planankunft oder ein Fahrtintervall über 90 Minuten blockiert darüber hinaus bereits deren Beobachtung, obwohl nur die ETA ungeeignet ist. Fünf gezielte Produktionsproben bestätigen diese drei Ursachen; keine Genauigkeits-, Sprung-, Radius- oder Forecastgrenze soll dafür pauschal gelockert werden.

- Die im [Main-Review](../entwicklung/main-review-2026-10-06.md) bestätigten G1-/G3-Pfade sind durch Sprungprüfung vor der physischen Mutation und den monotonen Android-Adapter mit Uhrsprung-Reset abgesichert. Reine Regressionen prüfen diese Regeln; der tatsächlich ausgeführte Prüflauf steht unter [Tests](../entwicklung/tests.md). TODO: Providerwechsel und vor-/zurückgestellte Systemzeit auf einem Gerät prüfen; synthetische Fixfolgen belegen keinen Android-Zustellungsverlauf.
- TODO: Den Tunnelbericht Essen Hbf → Bismarckplatz → Savignystraße mit zeitlich zugeordneten Fix-/Audioaufzeichnungen auf dem Gerät prüfen: ohne GPS über mehrere Halte, erste frische Fixes am späteren Halt, Cursor, neutrale Abschnittsdiagnose, Ansagen und erneute Zeitprognose. Die bestätigte logische Wiederverankerungslücke im alten Code belegt keine aufgezeichnete Nutzer-Messfolge. Wiederkehrendes GPS darf weder Tunnel-Ankunftszeiten erfinden noch einen ungeprüften Schienenweg oder eine sofortige neue ETA behaupten.

- TODO: Die automatische [SEV-Zuordnung](./sev-haltestellen.md) auf der gemeldeten RE1-Busfahrt vor Ort prüfen. Die tatsächlich von der API gelieferten Stopover-Koordinaten und die Ankunftshaltestelle in Duisburg verifizieren. Ein fehlender Richtungsbeleg muss den bisherigen API-Punkt mit sichtbarer unbestätigter SEV-Position erhalten.
- TODO: Auf der RE1-Rückfahrt Duisburg → Mülheim → Essen beobachtete Ankunft/Abfahrt, Straßenprojektion, angezeigten Prognoserückfall und GPS-/API-Wechsel gemeinsam mit Fix-/Audioverlauf prüfen. Die spätere Diagnose `OUTSIDE_CORRIDOR` belegt den damaligen Ablehnungszustand, keinen tatsächlichen Busfahrweg. Nach kurzem Halt müssen bestätigte Ereignisse auch ohne Zukunftsprognose sichtbar werden. Falsche Pkw-Wege, Kreuzungen, parallele Wege und fehlende SEV-Endpunkte müssen konservativ behandelt werden. Netzwerkfehler und Neustart dürfen nur mit weiterhin gültiger verfügbarer RAM-Geometrie und frischem Bewegungsbeleg eine Abschnittsprognose liefern, sonst gilt der Rückfall.
- TODO: Website-Struktur, Quellenänderungen, Ablauf temporärer Verlegungen und Bedingungen regelmäßiger Abrufe prüfen. Der Beispielabruf vom 06.10.2026 bleibt eine Momentaufnahme; die App lädt unabhängig davon mit begrenztem Cache und prüft das Quellenalter. Feature-Versionen sind keine Gültigkeitsintervalle.
- TODO: Den Nutzerbericht vom 06.10.2026 zur flackernden Quellenanzeige auf der S28 mit dem stabilisierten Prognosezustand und der aktuellen UI-Vergleichszeit erneut prüfen. Die nachgereichte Bildschirmaufnahme bei eingeschaltetem Display zeigt wechselnde GPS-/API-Quellen für denselben Besuch und Folgehalte, enthält aber keinen Standort- oder Audioverlauf. Insbesondere Bremsen, Ankunft und kurze Haltwechsel dürfen einen noch gültigen passenden Wert nicht unnötig verwerfen; echter Signalverlust muss weiterhin auf API/Plan zurückfallen. Prognosegüte bei Verfrühung, Verspätung, längerem Aufenthalt, Tunnel, Kurven und eng benachbarten Halten bleibt offen.
- TODO: Einheitliche Quellen-/Zeitdarstellung in Fahrtdetail, Widget und Samsung-Sperrbildschirm bei Display-aus-Betrieb und wiederkehrendem Signal prüfen. Reine Kotlin-Tests belegen keine reale ETA-Güte.

## Verwandte Seiten

- [TripTracking](./trip-tracking.md)
- [SEV-Ersatzhaltestellen](./sev-haltestellen.md)
- [StatusDetail](./status-detail.md)
- [Reisefortschritt](./trip-progress.md)
- [Widget](./widget.md)
- [Datenmodell](../daten/datenmodell.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Tests](../entwicklung/tests.md)
- [Offene Fragen](../offene-fragen.md)
