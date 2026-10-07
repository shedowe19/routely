# Entwicklung: Tests

## Zweck

Dokumentiert, wie die App getestet wird.

## Testen

- **Unit-Tests ausführen**: `./gradlew :app:testDebugUnitTest`.
- **API-Regressionen und Debug-Build zusammen prüfen**: `./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace`.
- **Vollständiger CI-Prüfumfang einschließlich Debug-Lint und Release**: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --stacktrace`.
- **Coroutines testen**: Auth-/Repository-Regressionen verwenden `runBlocking` und kontrollierte Fakes beziehungsweise `CompletableDeferred`. Für Meldungs- und Feedcontroller ist zusätzlich `kotlinx-coroutines-test` mit `runTest` und kontrolliertem Test-Dispatcher eingerichtet; verzögerte Antworten und virtuelle Zeit prüfen die Requestreihenfolge ohne sleeps.
- Der Unit-Test `TraewellingApiServiceTest` prüft den Retrofit-Vertrag für den Abfahrts-Endpunkt, damit die Route nicht versehentlich wieder unter `/api/v1/trains/station/...` geführt wird.
- `ApiCompatibilityTest` lädt Fixtures aus `app/src/test/resources/traewelling/`. Die Antworten lassen auslaufende Kompatibilitätsfelder bewusst weg und unterscheiden Stopover-ID und Station-ID.
- Die Modelltests prüfen verschachtelte Stationen, optionale Kennungen, IBNR/RIL100, numerische und UUID-Operator-IDs, echte und geplante Zeiten, berechnete Abfahrtsverspätungen, Stopover-Erkennung bei wiederholten Stationsbesuchen, Deduplizierung einschließlich verschiedener Stopover-UUIDs und die neuen Check-in-Erfolgs- und Konfliktantworten.
- Statusänderungs-Tests prüfen die gemeinsame Übertragung von `destinationId` und `destinationArrivalPlanned` sowie das Weglassen beider Felder bei reinen Textänderungen.
- `StationTrackingEngineTest` verwendet synthetische Positionen und eine feste Uhr ohne Android oder Netzwerk. Geprüft werden GPS bei Verspätung, Annäherung gegenüber Ankunft, Richtungs-/Abfahrtsfortschritt, ungültige beziehungsweise alte Fixes, Signalunterbrechungen, Fahrplan-Rückfall, Radien, Rundfahrten, Ansagemarkierungen nach Neustart und per Gson restaurierte Route/Fortschritt.
- `StopTimelineProgressTest` prüft genau einen markierten Besuch, überlappende Zeiten, GPS vor der Fahrplanzeit, die Zuordnung einer Teilroute zur vollständigen Timeline, wiederholte Stationsbesuche, unbekannte Cursor, gestrichene Halte und Abschluss am eingecheckten Ziel.
- `SpeechDeliveryQueueTest` prüft die Zuordnung eindeutiger Ansageversuche, mehrere wartende Ansagen, Wiederholungen und verspätete Callbacks früherer Fahrten. Die Klasse testet das Bookkeeping; sie ersetzt keinen Android-Audiofokustest.
- Unit-Tests liegen unter `app/src/test`; Instrumentierungstests unter `app/src/androidTest` sind noch nicht vorhanden.
- API-nahe Tests mit echten Tokens sind derzeit nicht als automatisierte Tests eingerichtet. Falls sie ergänzt werden, müssen Tokens lokal und nicht versioniert bereitgestellt werden.

## Voraussetzungen

- Lokale Gradle-Aufrufe benötigen JDK 17.
- Der Unix-Wrapper `gradlew` muss mit LF-Zeilenenden ausgecheckt sein; dies wird zusammen mit weiteren Projekttextdateien über `.gitattributes` erzwungen.
- Für den Android-Gradle-Lauf werden außerdem Android-SDK 36 und die auflösbaren Gradle-/Maven-Abhängigkeiten benötigt.

## Automatisierte Prüfung

`.github/workflows/api-compatibility.yml` führt bei Pushes auf `main`, Pull Requests und manuellem Start Unit-Tests, vollständiges `lintDebug` sowie Debug- und Release-Build aus. Der Workflow richtet JDK 17, Android-SDK 36 und Build Tools 35.0.0 ein. `assembleRelease` prüft zusätzlich die Release-Lint-Anforderungen, die ein reiner Debug-Build nicht abdeckt. Der manuelle signierte Release-Workflow führt ebenfalls Unit-Tests vor dem Release-Build und der Signierung aus.

| Artefakt | Inhalt |
| --- | --- |
| `api-compatibility-test-results` | JUnit-Ergebnisse und HTML-Testberichte |
| `routely-debug-apk` | Debug-APK für Geräteprüfungen nach erfolgreichem Build |
| `routely-release-unsigned-apk` | Unsignierte Release-APK nach erfolgreichem Build |
| `release-lint-results` | Vorhandene Debug- und Release-Lint-Berichte, auch bei fehlgeschlagenem Build; Artefaktname unverändert |

Die Ursache und der explizite Fragment-Versionsfix für den fehlgeschlagenen `1.7.0`-Release-Build stehen unter [Build](./build.md). Bisherige Debug-Nachweise enthalten keinen Release-Lint-Nachweis für diesen Fix; aktuelle Ergebnisse des erweiterten Prüfumfangs stehen unter [API Compatibility](https://github.com/shedowe19/routely/actions/workflows/api-compatibility.yml). Signierung und Veröffentlichung erfolgen weiterhin ausschließlich im manuellen Workflow `android.yml`.

## Ergebnis der Migration vom 05.10.2026

GitHub Actions hat am 05.10.2026 für Commit `4ed79c781e5ca38005885fb585277fee56c1cfa4` alle 28 Unit-Tests und den vollständigen Android-Debug-Build erfolgreich ausgeführt (`./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace`). Der Prüfstand umfasst 27 neue Modelltests und den vorhandenen Retrofit-Endpunkttest. Die JUnit- und HTML-Berichte sind im Workflow-Artefakt gespeichert.

## Prüfung der GPS-Erweiterung

Der vor der Begleiter-Erweiterung geprüfte Stand enthält 88 Unit-Tests: 47 Engine-, 9 Timeline-, 4 Ansagequeue- und 28 API-Tests. Gegenüber den ursprünglichen 35 Engine-Regressionen sichern zwölf zusätzliche Fälle kurze Halteabstände, frühe richtungsabhängige Übergabe, dieselbe Fixfolge für den Folgehalt, kumulierte Bewegung bei häufigen Standortupdates, Signallücken sowie unveränderte Zielankunftskriterien ab. Ein erster GPS-Fix fern aller Stationen darf weiterhin keinen vorläufigen Zeitcursor festschreiben.

Der erste vollständige GPS-Prüflauf für Commit `672051411cc1f76bf910c0262b8fbe8710844363` war am 05.10.2026 erfolgreich: 62 Tests (34 GPS und 28 API), Android-Debug-Build und APK-Upload. Nachweis: [GitHub-Actions-Lauf 37349706390](https://github.com/shedowe19/routely/actions/runs/37349706390). Dieser historische Lauf enthält die späteren Startup-, Kurzhalte-, Timeline- und Ansagequeue-Regressionen noch nicht. Aktuelle Ergebnisse zeigt der Workflow [API Compatibility](https://github.com/shedowe19/routely/actions/workflows/api-compatibility.yml); die Quellanzahl allein ist kein Nachweis eines erfolgreichen Laufs.

Eine reine Kotlin-Testreihe bestätigt weder Android-Permissiondialoge, tatsächlich gelieferte Standortintervalle, Display-aus-Betrieb noch Audioausgabe auf einem Gerät. Der Nutzerbericht aus einer Fahrt mit `1.7.0` und die daraus abgeleitete erneute Geräteprüfung sind unter [TripTracking](../module/trip-tracking.md) dokumentiert. Die aktuellen Korrekturen wurden hier nicht auf einem physischen Gerät erprobt. Cachetests prüfen die restaurierbaren Daten und Engine-Fortsetzung, keine ausgeführte Android-DataStore-/Service-Integration; der Timeline-Maler erfordert zusätzlich eine visuelle Prüfung mit großer Schrift.

## GPS-Zeiten: neue Prüfziele

Die [GPS-Zeiterweiterung](../module/gps-zeiten.md) ergänzt synthetische Kotlin-Regressionen ohne Android oder Netzwerk:

- `GpsJourneyTimeEstimatorTest`: früher/später räumlicher Planversatz, Mindestbeobachtung, gerichtete Bewegung, Jitter, falsche Richtung, Korridor, stabile erste Ankunft, spätes Warten, keine erfundene Frühabfahrt, fehlende Geschwindigkeit, schnelle Vorbeifahrt und unterstützte Abfahrtsbeobachtung auch auf langen Segmenten. Weitere Fälle prüfen alte/ungenaue/ungültige Fixes, doppelte oder rückläufige Zeitstempel, Cache-Ablauf, Invalidierung, Neustart, Plan-/Routen-/Koordinatenänderung, Streichungen, fehlende Segmentdaten, wiederholte Besuche und unveränderte Providerdaten/Fortschrittswerte. Eine gültige Planänderung muss alte Prognosen verwerfen und nach neuer Bewegung anhand der geänderten Basis neu schätzen; eine Abfahrt vor der Planankunft verwirft die Prognose auch bei weiteren frischen Fixes.
- `JourneyTimeResolverTest`: GPS vor manueller Zeit/API/Plan, beobachtet gegenüber geschätzt, sofortiger API-Rückfall nach Ablauf, getrennte Ankunft-/Abfahrtsauflösung, ungültige Felder, Besuchsidentität, erhaltene Verfrühungen, unveränderte Rohhalte und Bearbeitungswerte. Die lokale manuelle Timeline-Projektion wird auf eindeutige Besuche, beide Ereignisse, ungültige Zeiten und mehrdeutige Zuordnung geprüft.
- `TripProgressModelTest`: gemeinsame GPS-Zielzeit und API-/manueller Rückfall zusätzlich zum bestehenden Haltefortschritt.
- `StationTrackingEngineTest`: zwei zusätzliche Regressionen sichern den räumlichen Abfahrts-Bootstrap vor der Plan-/API-Abfahrt und den anschließenden Ablauf Engine → GPS-Zeitschätzer nach einem Trackingstart während verfrühter Weiterfahrt ab. Die gerichtete Bewegung und der plausible Korridor bleiben erforderlich; Providerzeiten und Zielabschluss werden nicht durch den Bootstrap verändert.

Der GPS-Zeiten-Stand ergänzt 43 Estimator- und 22 Resolver-Tests sowie sechs weitere Fortschrittsmodell-Fälle und zwei weitere Stationsengine-Fälle. Die Stationsengine enthält damit 49 Testmethoden; dieser Stand umfasst 235 Unit-Testmethoden. Die nachfolgenden Review-Korrekturen ergänzen weitere Tests. Prüfziele sind getrennt von historischen erfolgreichen Läufen zu bewerten. Ein erfolgreicher älterer Begleiter-Build belegt die neue Zeitprognose nicht; den passenden aktuellen Commit und CI-Lauf prüfen. Reale Prognosegüte, Kurven/Tunnel, Signalwiederkehr und einheitliche Quellenwechsel in Header, Haltliste, Widget und Samsung-Sperrbildschirm bleiben Gerätetests.

Der GPS-Zeiten-Stand `fb0f75eb9dbdd21f6addb11fbe1cb1f333b07757` bestand am 05.10.2026 alle 235 Unit-Tests ohne Fehler, Fehlschläge oder übersprungene Tests. Die heruntergeladenen JUnit-Berichte bestätigen auch die 43 Estimator- und 22 Resolver-Fälle. Derselbe Lauf baute Debug- und unsignierte Release-APK und bestand `lintVitalRelease`. Nachweis: [GitHub-Actions-Lauf 37376730124](https://github.com/shedowe19/routely/actions/runs/37376730124). Diese Prüfung umfasst die korrigierte gültige Planänderung und die gesonderte Ablehnung einer Abfahrt vor der Planankunft; sie ersetzt die genannten Geräteprüfungen nicht.

## Begleiter-Erweiterung: neue Prüfziele

Die Erweiterung um [Fahrterkennung](../module/ride-recognition.md), [Fahrtänderungen](../module/trip-changes.md) und [Reisefortschritt](../module/trip-progress.md) ergänzt folgende reine Kotlin-Regressionen:

- `RideRecognitionEngineTest`: gerichtete und kumulierte Bewegung, beobachteter Einstieg, Aktualität/Genauigkeit, parallele Kandidaten, Mehrfachbesuche, Ausfälle, reale Abfahrt gegenüber Cache-Zeiten und begrenzte RAM-Historie.
- `TripChangeMonitorTest`: stille erste Basis, Folge-Snapshots, Gleis-/Ausfall-/Wiederherstellungshinweise, kumulierte Fünf-Minuten-Schwelle, manuelle Zeiten, fehlende Providerfelder, Besuchsschlüssel, Deduplizierung und Cache-Neustart.
- `TripProgressModelTest`: Einstieg ausschließen, gestrichene Halte, Annäherung/Ankunft, wiederholte Besuche, unbekannter Cursor, Zeitmodus ohne bestätigte Zielankunft, vollständiger Abschluss und Plan-/Echtzeitangaben.
- `SpeechDeliveryQueueTest`: zusätzliche Trennung von Stations- und Änderungssprache, auch bei identischen Ereignisschlüsseln.
- `HttpLogSanitizerTest`: redaktierte Standortparameter einschließlich Bounding-Box-Werten und unveränderte unkritische URL-Parameter.

Der erste bestätigte Begleiter-Stand `898130b91db2fd291d94d92752084070668c43ea` bestand am 05.10.2026 alle 162 Unit-Tests (keine Fehler, Fehlschläge oder übersprungenen Tests). Derselbe Lauf baute die Debug- und unsignierte Release-APK und bestand `lintVitalRelease`. Die JUnit-Berichte bestätigen 22 Erkennungs-, 31 Änderungs-, 18 Fortschritts-, 5 Ansagequeue-, 47 GPS-, 9 Timeline-, 28 API- und 2 Logredaktionstests. Nachweis: [GitHub-Actions-Lauf 37363237606](https://github.com/shedowe19/routely/actions/runs/37363237606). Spätere Änderungen müssen dem jeweils passenden CI-Lauf zugeordnet werden.

Geräteprüfungen müssen zusätzlich die präzise Standortfreigabe der Opt-in-Suche, sichtbaren FGS, Pause bei Check-in/Logout, Kalte-/Warmstartnavigation, Benachrichtigungs- und TTS-Zustellung sowie API-35/36/36.1-Layouts und Sperrbildschirm-Privatsphäre abdecken. Die Erkennung verwendet synthetische Bewegung für Logiktests; reale Erkennungsgüte, Akkuverbrauch und Hersteller-Live-Updates sind dadurch nicht belegt.

## Review-Korrekturen vom 05.10.2026

- `RideDiscoveryRequestsTest`: acht neue Fälle unterscheiden vollständige Anfrageausfälle von erfolgreicher leerer Antwort, verwertbaren Teilergebnissen, gültigem Tripcache und einer Stufe ohne Anfragen. Ein in `Result` verpackter Coroutine-Abbruch muss trotz vorherigem Teilerfolg weitergegeben werden.
- `TripProgressModelTest`: fünf zusätzliche Fälle sichern den noch ausstehenden Zielhalt bei Annäherung, eine Route mit ausschließlich Einstieg und Ziel, gestrichene Zwischenhalte, ein gestrichenes Ziel sowie die vom Service explizit übergebene manuelle Zielzeit. Der vorhandene Zielankunftsfall prüft zusätzlich null Resthalte ohne bestätigten Fahrtabschluss, die unvollständige Balkenposition und `Am Ziel · Ankunft wird geprüft`.
- Android-spezifische Korrekturen verwenden `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` ab API 36 und erfassen jede neue Service-`startId` vor der Wegwisch-Verarbeitung. Einheitenlogik belegt weder die tatsächliche Android-Freigabeoberfläche noch das Lebenszyklusverhalten eines laufenden Services; beides bleibt eine Geräteprüfung.

Der Review-Quellstand vom 05.10.2026 enthält 248 Unit-Testmethoden: 235 aus dem zuvor geprüften GPS-Zeiten-Stand, acht neue Discovery- und fünf neue Fortschrittsmodell-Tests. `TripProgressModelTest` enthält jetzt 29 Fälle. Diese Quellanzahl allein belegt keinen erfolgreichen neuen CI-Lauf; historische Nachweise gelten weiterhin ausschließlich für ihre genannten Commits.

## GPS-Quellenstabilität und Abfahrtsansage vom 06.10.2026

- `GpsJourneyTimeEstimatorTest`: 14 weitere Fälle prüfen ausreichenden zeitlichen Bewegungsbeleg bei einsekündigen und häufigeren Location-Updates, Bremsen und gültige Genauigkeitsschwankungen, durchgehend dieselbe Quelle bei normalen Drei-Sekunden-Fixes und reinen Uhrticks sowie begrenzte Gültigkeit bei stationärer Bewegungslücke. Ankunft und geordnete Folgeabschnitte dürfen die zuletzt belegte gültige Prognose während der neuen Belegsammlung erhalten. Erkennbare Rückwärtsbewegung und eine Position außerhalb des Korridors verwerfen sie weiterhin sofort. Vier zusätzliche Ankunftsgrenzfälle prüfen den ersten langsamen Fix leicht hinter der Stationskoordinate, die dortige achtsekündige Aufenthaltsbeobachtung ohne Geschwindigkeitswert einschließlich Uhrtick, weiterhin unzulässige Querabweichung trotz innerer Ankunftszone und Rückwärtsbewegung. Die Klasse enthält damit 57 Testmethoden.
- `StationTrackingEngineTest`: 13 weitere Fälle prüfen den vor der Bewegung gesprochenen Wartehinweis am Einstieg, ein durch API-Aktualisierung erreichtes Drei-Minuten-Fenster, verfrühte Abfahrt mit anschließendem Näherungsjitter, vergangene API-Abfahrt, fehlende Geschwindigkeit mit notwendigem Aufenthalt, Bewegung trotz gemeldeter Nullgeschwindigkeit, freigegebene Ansageversuche während der Weiterfahrt und die Folgehaltansage bei zwei Minuten Verfrühung. Fünf Fälle sichern zusätzlich die Relevanzprüfung verzögerter Einstiegsansagen ab: abgelaufenes Zeitfenster, aktuelle Bewegung einschließlich einer früheren Fahrplanansage, unbrauchbar gewordene GPS-Basis beziehungsweise gewechselter Besuch, Annäherung ohne belegtes Warten und zulässiger reiner Fahrplan-Rückfall. Die Klasse enthält damit 62 Testmethoden.

Der Quellstand dieser Korrektur enthält 275 Unit-Testmethoden: 248 aus dem Review-Stand, 14 zusätzliche Estimator- und 13 zusätzliche Engine-Fälle. Diese Quellanzahl ist kein neuer CI-Nachweis.

Der Main-Stand `454e61710e8f418cdf70b08195657cbf31545d71` bestand am 06.10.2026 alle 275 Unit-Tests ohne Fehler, Fehlschläge oder übersprungene Tests. Die heruntergeladenen JUnit-Berichte bestätigen 57 Estimator- und 62 Engine-Fälle in insgesamt zwölf Testklassen. Derselbe Lauf baute Debug- und unsignierte Release-APK und bestand `lintVitalRelease`. Nachweis: [GitHub-Actions-Lauf 37433060421](https://github.com/shedowe19/routely/actions/runs/37433060421). Der erfolgreiche automatisierte Lauf ersetzt die genannten GPS-/TTS-Geräteprüfungen nicht.

Die neuen Fälle verwenden synthetische Positionen und eine feste Uhr. Sie prüfen weder die tatsächlich von Android gelieferten Location-Callbacks noch den Zeitpunkt hörbarer TTS auf einem Gerät. Der erneute S28-Fahrtversuch mit Bremsen, Ankunft und kurzen Haltwechseln bleibt unter [TripTracking](../module/trip-tracking.md) und [GPS-Zeiten](../module/gps-zeiten.md) offen. Der CI-Nachweis ist weiterhin dem tatsächlich geprüften Commit zuzuordnen; historische 248-Test-Ergebnisse belegen diese neuen Fälle nicht.

## UI-Uhr und GPS-Quellenwechsel vom 06.10.2026

Eine rund 19 Sekunden lange Bildschirmaufnahme zeigt bei eingeschaltetem Display mehrfach den Wechsel zwischen GPS- und API-Zeitquellen für denselben aktuellen Besuch und Folgehalte. Sie enthält keinen Audio- oder Standortverlauf und belegt weder eine Doze-Ursache noch unzureichende GPS-Genauigkeit.

Die Codeprüfung ergibt einen unabhängigen UI-Fehler: Ein zwischen zwei sekündlichen Ticks veröffentlichter GPS-Datensatz kann neuer als die zuletzt gespeicherte UI-Uhrzeit sein. Die korrekte Zukunftssperre in `GpsJourneyTimes.timeFor` verwirft ihn dann bis zum nächsten Tick. `StatusDetailContent` verwendet jetzt bei jeder Zusammensetzung die aktuelle Systemzeit; der Ein-Sekunden-Ticker bleibt für den Ablauf ohne Service-Updates bestehen. Die vorhandenen Tests `futureDatedGpsSnapshotDoesNotOverrideApi` und `timeForRejectsExpiredFutureOrUnboundedSnapshots` sichern weiterhin die strikte Zukunfts- und Ablaufsperre. Diese reine UI-Uhrkorrektur ändert weder Standortanforderungen noch die ursprüngliche 30-Sekunden-Gültigkeit.

Der Main-Stand `15dfe20911efcbb1fa2b230bcbd5197ea5e33b89` bestand am 06.10.2026 alle 283 vorhandenen Unit-Tests ohne Fehler, Fehlschläge oder übersprungene Tests. Die heruntergeladenen JUnit-Berichte bestätigen 13 Testklassen einschließlich 57 Estimator- und 22 Resolver-Fällen. Derselbe Lauf baute Debug- und unsignierte Release-APK und bestand `lintVitalRelease`. Nachweis: [GitHub-Actions-Lauf 37437192661](https://github.com/shedowe19/routely/actions/runs/37437192661). Diese Prüfungen bestätigen Kompilierung und bestehende Logikregressionen; sie führen weder Compose-Neuberechnungen noch eine GPS-Fahrt auf dem Gerät aus.

TODO: Mit der aktualisierten APK frische GPS-Veröffentlichungen zwischen UI-Ticks sowie anschließenden Signalverlust auf dem Gerät prüfen. Der Codepfad erklärt einen möglichen Anzeigewechsel; eine erfolgreiche Bildschirmaufnahme ohne erneutes Flackern bleibt ein eigener Gerätenachweis.

## Display-aus- und Doze-Prüfung vom 06.10.2026

Ein Nutzerbericht meldet ausbleibende Ansagen bei ausgeschaltetem Display. Der Bericht enthält keinen Standort-, Service- oder Audioverlauf; die konkrete Geräteursache ist daher nicht bewiesen. Die [Absicherung der aktiven Fahrt](../module/trip-tracking.md) und die [Android-Akkueinstellung](../module/settings.md) müssen auf einem physischen Gerät zusätzlich zum Kotlin-/Build-Prüflauf validiert werden.

`TrackingWakeLockLeaseTest` ergänzt acht reine Kotlin-Fälle mit einem künstlichen Plattformhandle: keine Haltung im Leerlauf, immer begrenzter Erwerb, keine verspätete Erneuerung nach Stopp, Generationswechsel, Wiedererwerb nach Plattformablauf, Retry nach fehlgeschlagenem Erwerb, kein Erneuern nach fehlgeschlagener Freigabe und neuer Start nach Abschluss. Der Quellstand enthält damit 283 Unit-Testmethoden in 13 Klassen. Diese Anzahl ist kein neuer erfolgreicher CI-Lauf. Die Tests prüfen den Lease-Zustand und die an den Adapter übergebenen Timeouts, keinen echten Android-WakeLock, Doze-Modus oder Service-Lebenszyklus.

Der Main-Stand `58dcaea7b700dfb4c0ce6b65fb77b884a80729d4` bestand am 06.10.2026 alle 283 Unit-Tests ohne Fehler, Fehlschläge oder übersprungene Tests. Die heruntergeladenen JUnit-Berichte bestätigen die acht neuen Lease-Fälle in insgesamt 13 Testklassen. Derselbe Lauf baute Debug- und unsignierte Release-APK und bestand `lintVitalRelease`. Nachweis: [GitHub-Actions-Lauf 37434663721](https://github.com/shedowe19/routely/actions/runs/37434663721). Die Android-Ausnahme, echte CPU-Haltung und hörbare Display-aus-Zustellung werden dadurch nicht auf einem Gerät nachgewiesen.

| Gerätefall | Zu prüfendes Ergebnis |
| --- | --- |
| Samsung S26 Ultra mit einer eingecheckten S28-Fahrt, Display mindestens 30 Minuten aus | Die laufende Fahrtbenachrichtigung bleibt vorhanden. Frische Standortupdates, Haltwechsel und hörbare Stationsansagen werden protokolliert; Einschalten darf nicht erst einen Stau alter Ansagen auslösen. |
| Android-Doze-Test auf einem Testgerät mit gewährter und mit abgelehnter Akku-Ausnahme | Den tatsächlichen CPU-, Standort-, Netzwerk- und TTS-Verlauf getrennt erfassen. Eine gewährte Ausnahme ist keine Garantie für identische GPS-Zustellintervalle. |
| Energiesparmodus und Samsung-Einschränkungen für Hintergrundbetrieb | Die Android-Ausnahme und die Samsung-Einstellungen getrennt prüfen; beim Rückkehren in Routely muss der angezeigte Ausnahmestatus dem aktuellen Systemwert entsprechen. |
| GPS-Verlust, entzogene Standortfreigabe oder ausgeschaltete Ortung bei Display aus | Die etablierte Haltidentität bleibt geschützt; verfügbare API-/Fahrplanzeiten werden gekennzeichnet verwendet. Eine Zielankunft wird nicht allein aus der Uhrzeit erfunden. |
| Netzwerk-/API-Ausfall bei weiterhin frischem GPS und zuvor verfügbarer Route | GPS-Auswertung und Stationsansagen dürfen nicht auf die nächste erfolgreiche API-Antwort warten. |
| TTS nicht bereit, Audiofokus verweigert oder eine andere Ansage läuft | Kein fälschlich bestätigter Ansageversuch; die vorhandene Freigabe-/Retry-Logik und das Abfahrtsfenster bleiben wirksam. |
| Fahrt manuell beenden, Zielabschluss, Logout, Fahrtwechsel und Service-Zerstörung | Standortcallbacks und Jobs der beendeten Fahrt verschwinden; kein verwaister CPU-WakeLock bleibt gehalten. Während der abschließenden Zielansage bleibt deren bestehender Abschluss-/Timeoutschutz erhalten. |

Für einen kontrollierten Android-Test kann `adb shell dumpsys deviceidle force-idle` Doze erzwingen; anschließend `adb shell dumpsys deviceidle unforce` und `adb shell dumpsys battery reset` aufrufen. Falls vorher `adb shell dumpsys battery unplug` verwendet wird, ist das Reset auch nach einem abgebrochenen Test nötig. `adb shell dumpsys power` hilft beim Prüfen der gehaltenen WakeLocks. Diese Befehle gehören ausschließlich zur manuellen Testumgebung; die App führt sie nicht aus. Die Fahrten-, Standort- und Audiozeitpunkte müssen separat gemessen werden, weil ein sichtbarer Foreground-Service allein weder frische GPS-Daten noch eine hörbare Ansage belegt.

Die Test- und Ausnahmegrundlagen stehen in der [offiziellen Android-Doze-Dokumentation](https://developer.android.com/training/monitoring-device-state/doze-standby). Der gewährte Ausnahmestatus und die CPU-Haltung sind gesondert zu prüfen; gewöhnliches Doze darf WakeLocks ignorieren.

TODO: Die obige Matrix einschließlich Akkuverbrauch über mindestens 30 Minuten auf dem Nutzergerät ausführen. Automatisierte Unit-Tests ersetzen keinen echten Doze-/OEM-/TTS-Nachweis.

## Automatische SEV-Haltestellen vom 06.10.2026

Die [SEV-Erweiterung](../module/sev-haltestellen.md) ergänzt 14 Parser- und 22 Zuordnungsfälle sowie zwei Tracking-Regressionen. Die Parser-Fixtures prüfen öffentliche Essen-Koordinaten, fragmentierte Next-Daten, falsche Stationszuordnung, konflikthafte Features, undefinierte Namen, Richtungszeilen und ungültige Koordinaten. Die Zuordnung prüft Bus RE/RB gegenüber normalen Bussen und Zügen, deutsche Stationsnamen, aktuelle Einzelpunkte, Richtungen aus der vollständigen Fahrt, unklare Endhalte, getrennte Fristen, Zeitfenster und Haltbesuchsidentität. Das Mülheimer Richtungslabel enthält den tatsächlich veröffentlichten Zeilenumbruch vor der Oberhausen-Frist.

Die Tracking-Regressionen verlangen bei einer veränderten physischen Position einen neuen Ankunftsbeleg, erhalten aber den konkreten Besuch und bereits gesprochene Ansagen. Ein reiner API-Zeitwechsel am selben Punkt erhält dagegen die bestätigte Ankunft. Die HTTP-, Cache-, Android-Service- und Compose-Lebenszyklen werden von diesen reinen Unit-Tests nicht auf einem Gerät ausgeführt.

Der Main-Stand `5333921b078ae79fe0cc99add80a435daab15f48` bestand am 06.10.2026 alle **321 Unit-Tests** ohne Fehler, Fehlschläge oder übersprungene Tests. Die heruntergeladenen JUnit-Berichte bestätigen 15 Testklassen, darunter 14 Parser-, 22 SEV-Zuordnungs- und 64 Stationsengine-Fälle. Derselbe Lauf baute Debug- und unsignierte Release-APK und bestand `lintVitalRelease`. Nachweis: [GitHub-Actions-Lauf 37450392024](https://github.com/shedowe19/routely/actions/runs/37450392024). Damit sind Kotlin-/Compose-Kompilierung und die Logikregressionen belegt; tatsächliche HTTP-Lebenszyklen, GPS-/Audiozustellung und die Zuverlässigkeit auf dem Gerät bleiben gesonderte Prüfungen.

TODO: Mit einer aktuellen Bus-RE/RB-Fahrt Essen → Mülheim → Duisburg den Abruf, den tatsächlichen Einstiegspunkt, richtungsabhängige Ansagen und die Netzunterbrechung prüfen. Die öffentliche Duisburger Abfahrtsbeschriftung belegt keinen eindeutigen Ankunftspunkt eines dort endenden Busses; dieser Fall behält API-Koordinaten. Die spätere Straßenprojektion benötigt beidseitig bestätigte Ersatzhaltepunkte und muss einen tatsächlichen Umweg weiterhin sicher ablehnen.

## Fehlende GPS-Zeitprognose und beobachtete Ereignisse vom 06.10.2026

Eine weitere Aufnahme der RE1-Busfahrt Duisburg → Mülheim → Essen zeigt um 13:03 Uhr einen GPS-Besuchscursor bei weiterhin ausschließlich angezeigten Fahrplanzeiten. Sie enthält weder Positionsmessungen noch deren Genauigkeit und beweist deshalb keinen bestimmten Ablehnungsgrund. Die [GPS-Zeitprüfung](../module/gps-zeiten.md) unterscheidet den Besuchscursor von der strengeren Zeitprognose; Straßenumwege können den geraden Verbindungskorridor verlassen.

Die Regressionen prüfen, dass tatsächlich bestätigte Ankünfte und Abfahrten auch ohne Prognoseversatz als `GPS beobachtet` veröffentlicht werden. Ein solcher Datensatz enthält ausschließlich beobachtete Ereignisse, keine ersatzweise erfundenen Zukunftszeiten. Frische Beobachtungen dürfen eine abgelaufene Prognose nicht wiederbeleben. Beim Zusammenführen mit einer weiterhin gültigen Prognose bleiben deren ursprünglicher Unterstützungszeitpunkt und Ablauf erhalten; doppelte Positionszeitstempel beziehungsweise Uhr-/API-Ticks zählen nicht als Bewegung oder Aufenthaltsbestätigung. Auch Beobachtungen bleiben an zuverlässiges GPS, Route, Besuchsidentität und eine begrenzte Snapshot-Gültigkeit gebunden.

`GpsTimeUnavailableReason` beschreibt den fehlenden Prognosebeleg und wird im Fahrtdetail angezeigt. Die Gründe umfassen unter anderem ungenaue oder fehlende aktuelle Position, noch unbestätigten Besuch, unbrauchbaren Abschnitt, Warten am Einstieg, Lage außerhalb des Korridors sowie fehlende oder unplausible Fahrbewegung. Sie werden aus dem tatsächlichen Auswertungspfad abgeleitet; die Aufnahme allein wird keinem dieser Gründe zugeordnet.

Der Main-Code `9e0212979e21028f893b155db50abaa1a2fba4b4` bestand am 06.10.2026 alle **329 Unit-Tests** ohne Fehler, Fehlschläge oder übersprungene Tests. Die heruntergeladenen JUnit-Berichte bestätigen 15 Klassen und 65 Estimator-Fälle einschließlich der acht zusätzlichen Regressionen. Derselbe Lauf baute Debug- und unsignierte Release-APK und bestand `lintVitalRelease`. Nachweis: [GitHub-Actions-Lauf 37454963229](https://github.com/shedowe19/routely/actions/runs/37454963229). Der Prüflauf belegt Kompilierung und Logikregressionen, keinen tatsächlichen Fixverlauf oder hörbare Ansage der abgebildeten Busfahrt.

TODO: Auf einer echten RE1-SEV-Rückfahrt die ausgegebenen Gründe, gemessene Abfahrt und Ankunft, Zeitquellen sowie Signalverlust und Kurvenfahrt gegen einen zeitlich zugeordneten Fix-/Audioverlauf prüfen. Die damalige Erweiterung lieferte noch keine Straßenroute; die folgende Straßenprojektion ergänzt ein Modell, keine garantierte Bus-ETA.

## GPS-Straßenprognose für SEV vom 06.10.2026

Die spätere Aufnahme um 13:29 Uhr zeigt den Diagnosegrund „Der Fahrtweg weicht von der berechneten Verbindung zwischen den Halten ab.“ Damit ist die Ablehnung durch das räumliche Segmentmodell für diese Aufnahme sichtbar; die tatsächliche GPS-Position und der genaue Querabstand werden daraus nicht rekonstruiert. Die [GPS-Auswertung](../module/gps-zeiten.md) verwendet für SEV nun passende Straßenkandidaten zwischen bestätigten Ersatzhaltepunkten.

Die zusätzlichen Regressionen prüfen den vollständigen Weg vom OSRM-GeoJSON zum örtlichen Segmentfortschritt, Kurven außerhalb der bisherigen Geraden, Kreuzungs- und Alternativenmehrdeutigkeit, gerichtete Bewegung, Quellen-/Geometriewechsel, Gültigkeitsablauf und unveränderte GPS-Qualitätsgrenzen. Die Haltauswahl prüft geordnete Besuche, Streichungen, fehlende physische SEV-Punkte und getrennte Fahrtrichtungen. Repository-Tests prüfen anonymes HTTPS, reine öffentliche Endpunkte, Abrufbegrenzung, Cache-Lebensdauer und die Kündigung eines einzelnen Mitwartenden.

Die [öffentlichen OSRM-Testdaten](../../../app/src/test/resources/routing/README.md) wurden am 06.10.2026 ohne Kontotoken oder Gerätestandort abgerufen: Duisburg → Mülheim und Mülheim → Essen lieferten jeweils HTTP 200, `code = Ok` und zwei GeoJSON-Fahrwege. Diese Quellenproben belegen Abruf und Antwortformat, nicht den tatsächlichen Busweg oder eine Geräteprognose. Die wiederholbaren Kotlin-Regressionen verwenden die gespeicherten Antworten und erzeugte Messfolgen ohne weitere Netzabfragen.

Der Main-Code `ed94e12de406a0598f156b539ca7f6aeeef6d959` bestand am 06.10.2026 alle **381 Unit-Tests** in 19 Klassen ohne Fehler, Fehlschläge oder übersprungene Tests. Die heruntergeladenen JUnit-Berichte bestätigen 72 Estimator-Fälle, 14 separate Straßenvalidierungen, elf Abschnittsauswahl-, 13 Parser- und sieben Repository-Fälle. Gegenüber dem zuvor bestätigten Stand sind das 52 zusätzliche Tests. Derselbe Lauf baute Debug- und unsignierte Release-APK und bestand `lintVitalRelease`; beide APK-Artefakte gehören zu diesem Code-Commit. Nachweis: [GitHub-Actions-Lauf 37458753370](https://github.com/shedowe19/routely/actions/runs/37458753370). Die Wiki-Synchronisierung desselben Stands war ebenfalls erfolgreich. Dieser Nachweis belegt Kotlin-Ausführung und Builds, keine tatsächliche GPS-Prognose oder hörbare Ansage auf dem Gerät.

TODO: Auf dem Gerät eine SEV-Kurvenfahrt, einen bestätigten Alternativzweig mit anschließendem Zusammenlauf, einen tatsächlichen Umweg, fehlendes Netz beim Neustart und den Rückfall auf API-/Planzeit prüfen. Das Pkw-Straßenprofil belegt weder eine offizielle SEV-Führung noch Echtzeitverkehr oder Buszufahrtsrechte.

## GPS-Wiederverankerung nach Tunnel vom 06.10.2026

Der erste Prüflauf führte alle 410 Tests aus und fand eine Regression im bestehenden `sparseMovementPastAnObservedStopDoesNotWaitForABusStopDwell`: Die neue Kandidatenprüfung hielt einen bereits räumlich belegten Vorbeifahrtfortschritt zu einem nahen Folgehalt zurück. Die Korrektur priorisiert die unveränderte normale Abfahrts-/Vorbeifahrtbedingung durch eine gemeinsame reine Vorschau, bevor eine neue Kandidatenfolge beginnt. Die bestehende Regression bestand den abschließenden Prüflauf unverändert; ihre Erwartung wurde nicht an den Fehler angepasst.

Der Nutzerbericht Essen Hbf → Bismarckplatz → Savignystraße beschreibt fehlendes GPS über mehrere Halte und ein wiederkehrendes Signal am späteren Halt. Im alten Code konnte ein geschützter etablierter Cursor eine neue Initialisierung blockieren; die bisherige Lückenerkennung deckte nur den geordneten Nachfolger mit vorherigem Annäherungsbeleg ab. Dies ist ein aus dem Code belegter Wiederverankerungsfehler, keine aufgezeichnete Rekonstruktion der realen Nutzerfahrt.

Die neuen Engine-Regressionen sichern den getrennten Kandidatenbeleg für einen eindeutigen späteren Besuch: mindestens drei strikt neue Fixes über sechs Sekunden, höchstens 75 Meter Ungenauigkeit, Kandidat innerhalb von Entfernung plus Genauigkeit bis 150 Meter, konsistente Fixfolge und keine Auswahl anhand der Uhr. Mehrdeutigkeit über die gesamte Route, wiederholte Stationsbesuche, Initial-/Cachezustand, Standortinvalidierung, Signalflattern, weiterhin korrekt belegter aktueller Abschnitt und unveränderte normale Einhalt-Fortsetzung müssen konservativ behandelt werden. Bestätigung setzt Aufenthaltsbelege zurück; ein später ausgewähltes Ziel darf dadurch nicht sofort abschließen.

Zusätzliche Regressionen erhalten die Mehrhalt-Wiederverankerung nach einem geordneten Einhalt-Lückenübergang sowie nach einer äußeren Initial- oder Wiederverankerungsauswahl, solange der neue Besuch noch keinen eigenen physischen Beleg besitzt. Ein Kandidat benötigt außerdem einen nicht leeren eindeutigen Besuchsschlüssel. Der höchste akzeptierte Fixzeitstempel bleibt während Standortinvalidierung und GPS-Umschaltung prozesslokal erhalten; bereits verbrauchte Fixes dürfen weder Cursor noch dreiteiligen Kandidatenbeleg wiederbeleben. API-/Uhr-Replays zählen nicht erneut und löschen laufenden frischen Kandidatenbeleg nicht. Diese Zustände werden nicht persistiert.

`TunnelGpsRecoveryTest` prüft als reine Kotlin-Kette Engine → Zeitschätzer → Resolver: Ablauf der früheren Prognose im Tunnel, geschützter Cursor und API-/manueller Rückfall, Fahrplanquelle während der noch offenen Zuordnung, Wiederverankerung eines restaurierten Cursors und neue beobachtete Zeiten auf dem passenden Besuch. Weitere Fälle verhindern rückwirkende Tunnel-Istzeiten und Ansagen, Wiederbelebung eines bereits verbrauchten Prognosefixes, Veränderungen der Provider-/manuellen Werte und erfundenen Zielabschluss. Die Namen der Testhalte orientieren sich am Bericht; Koordinaten und Messfolgen sind synthetisch und beschreiben keine aufgezeichnete Essener Fahrt.

Die Detaildiagnose `OUTSIDE_CORRIDOR` wird neutral als fehlende sichere Zuordnung zum aktuellen Abschnitt formuliert. Eine bestätigte Wiederverankerung liefert Haltidentität, keine Schienen-Geometrie: Dieser historische Korrekturstand behielt für normale Tram-/Bahnprognosen gerade Haltverbindungen bei. Die spätere native Formquelle wird gesondert unten geprüft. SEV-Straßengeometrie und deren Qualitätsgrenzen ändern sich durch die Wiederverankerung nicht. Die Regressionen und der Service-Schutz einer während TTS-Initialisierung zurückgestellten alten Ansage ersetzen keine Android-Audio-/Lebenszyklusprüfung.

Der korrigierte Main-Code `d2d376afdf08b6468621382fe9c3601256df8d1c` bestand am 06.10.2026 alle **410 Unit-Tests** in 20 Klassen ohne Fehlschlag, Fehler oder übersprungene Fälle. Die heruntergeladenen JUnit-Berichte bestätigen 84 Engine-Fälle einschließlich 20 neuer Wiederverankerungsfälle und neun neue Tunnel-Integrationstests; gegenüber dem zuvor bestätigten Stand sind dies 29 zusätzliche Tests. Der zuvor fehlgeschlagene Bestands-Test ist ebenfalls erfolgreich. Derselbe [GitHub-Actions-Lauf 37473778560](https://github.com/shedowe19/routely/actions/runs/37473778560) baute Debug- und unsignierte Release-APK und bestand `lintVitalRelease`. Beide APK-Artefakte gehören zum genannten Code-Commit; die Wiki-Synchronisierung dieses Stands war ebenfalls erfolgreich. Dieser Nachweis belegt Kotlin-Ausführung und Builds, keine tatsächlich aufgezeichnete Tunnelfahrt oder Audiozustellung.

TODO: Auf dem Gerät Essen Hbf → Bismarckplatz → Savignystraße ohne GPS im Tunnel und mit Signalrückkehr am späteren Halt prüfen. Fix-/Audioverlauf, drei unabhängige Kandidatenfixes, Cursor/Timeline, neutrale Diagnose, erneute Zeiten und verspätete TTS-Initialisierung gemeinsam erfassen. Zusätzlich Mehrdeutigkeit, wiederholte Stationen, stationäres Wiederfinden, erneuter Ausfall, Cache-Neustart und Rückkehr erst am Ziel prüfen. Keine reale GPS-/Audiozustellung oder genaue Tram-ETA ist durch diese synthetischen Tests nachgewiesen.

## Vollständiger Main-Audit vom 06.10.2026

Die Quellprüfung verwendet den Main-Ausgangsstand `b2bd7b75d01ca05377433455563f176c34956c1c` und umfasst API-/Auth-/Cache-Verträge, Feature-Verbraucher, GPS-Zeit- und Besuchslogik, SEV-/Straßenquellen, Android-Begleitung und Workflows. Die zusätzlichen Regressionen prüfen folgende bestätigte Fehler:

- `AuthRepositoryTest`, `ApiServerUrlTest`, `AuthSessionTest` und `SessionViewModelStoreTest`: Persistenz erst nach gültiger vollständiger Profilantwort, HTTPS-URL-Prüfung, temporäre Startupfehler, 401/403 nur für dieselbe Sitzung, lokale Abmeldung vor Netzwerk, Abmelde-Speicherfehler, späte OAuth-/Refreshantworten, Abbruch und getrennte Generationen auch bei gleichen Zugangsdaten. Atomare Reducer verhindern zusätzlich alte Check-in-/Fahrtcache-Schreiboperationen nach Kontowechsel; der Feature-Store wird bei Rotation erhalten und bei Sessionwechsel geleert.
- `SessionStartRequestsTest`: Ein neuer ungültiger Start verdrängt keinen passenden wartenden Start; ein neuerer validierter Start sperrt ältere Initialisierungen. Gelöschte Aufträge halten keinen Service am Leben, und eine falsche Fahrt-ID begründet keinen passenden Start. Die reine Queueprüfung führt den Android-Foreground-Lebenszyklus nicht selbst aus.
- `TraewellingRepositoryTest`: getrennte Server-/Konten-/Feedpartitionen, gleichzeitige Status-ID in verschiedenen Feeds, atomarer Ersatz einer leeren ersten Seite, erhaltene ursprüngliche API-Reihenfolge, kein Cache-Rückfall bei Auth-/Formatfehlern oder Abbruch, Sitzungswechsel während Netzwerk- und DAO-Verarbeitung sowie ein HTTP-Fehler des Ungelesenzählers statt eines erfundenen erfolgreichen Nullwerts. Der fake DAO prüft den Repositoryvertrag, keine tatsächlich ausgeführte Room-Migration.
- `GpsJourneyTimeEstimatorTest` und `JourneyTimeResolverTest`: Wird bei einem Besuch ohne UUID einer der beiden Planmarker entfernt oder ein zuvor fehlender ergänzt, darf ein noch frischer früherer GPS-Datensatz die neue API-Basis nicht überlagern. Ein gültiger Ein-Marker-Besuch bleibt verwendbar. Qualitätsgrenzen, Gültigkeit und mehrdeutige Zuordnung werden nicht gelockert.
- `SevStopResolverTest` und `SevJourneyEnricherTest`: zusätzliche nicht auswertbare Datumsgrenzen trotz breitem gültigem Intervall, vollständig ungültige Jahreswerte, abgelaufener erster Richtungsort vor späterer Gegenrichtung, eindeutiger gültiger Punkt für denselben ersten Ort, konkurrierende unbekannte Labels und ein begrenztes Anfragefenster nur bei eindeutigen geordneten Check-in-Grenzen. Streichungen, wiederholte zeitlich eindeutige Besuche und die 64-Slug-Grenze bleiben berücksichtigt.
- `RideRecognitionEngineTest`, `TrackingLocationObservationTest`, `TripProgressModelTest` und `TripChangeMonitorTest`: plausible Gesamtgeschwindigkeit verdeckt keinen fast sofortigen GPS-Sprung; verspätete genaue Fixes überschreiben keinen neueren ungenauen Zustand; zukünftige Zeitstempel blockieren keinen folgenden gültigen Fix. Gemeldete ungültige Geschwindigkeit bleibt von tatsächlich fehlender Angabe unterscheidbar. Ursprung verwendet Abfahrtsgleis, spätere Besuche Ankunftsgleis, SEV weiterhin kein Bahngleis; ein gleichzeitig gestrichener nächster Halt verhindert den Gleishinweis zum nun nächsten bedienten Halt nicht.
- `CheckInSelectionTest`, `NotificationReadStateTest` und `StatusEditRequestTest` prüfen wiederholte Einstiege anhand der gewählten Abfahrtszeit, gültige Ziele ausschließlich nach dem Einstieg, unbekannte beziehungsweise schon gelesene Meldungs-IDs, Rollback eigener Zähleranteile und unveränderte Status-PUT-Bearbeitungswerte. Reine Textänderungen dürfen keine manuellen Providerzeit-Overrides erzeugen; geänderte Einzelzeiten frieren das andere Ereignis nicht ein. Bei Zielwechsel wird ein unveränderter alter manueller Ankunfts-Override ausdrücklich geleert, eigene Zeitänderung bleibt erhalten. Asynchrone ViewModel-/TTS-Generationen, sichtbare Teilfehler, abgebrochene Standortabfragen und deaktivierte nicht angebundene Aktionen sind zusätzliche Quell-/Buildprüfungen; die Helfertests ersetzen keinen vollständigen Compose-/Netzwerkablauf auf dem Gerät.

Der Prüfworkflow fordert zusätzlich vollständiges `lintDebug` an. Der manuelle signierte Release-Workflow validiert Versionswerte und führt Unit-Tests vor Build und Signierung aus. Ein Main-Commit mit grünem Prüfworkflow ist kein Nachweis eines ausgeführten signierten Releases. Auch Widget-Receiverfreigabe und der synchrone `onTimeout`-Lebenszyklus werden durch die Kotlin-Helfertests nicht als Android-Ablauf ausgeführt. Die Lint-Nachprüfung ergänzt die Manifest-Abfrage installierter TTS-Engines und den direkten Start der Android-Anzeigeeinstellungen mit Rückfall von Promotion- auf normale Benachrichtigungseinstellungen und Appdetails; deren tatsächliche Engine-/OEM-Zustellung bleibt eine Geräteprüfung. Historische grüne Läufe oben gelten ausschließlich für ihre genannten Commits; Testmethoden im Quellcode sind allein kein neuer Erfolgsnachweis.

Der abschließende Main-Code `36479de6cf84b6575754b3315eedaa07b119b109` bestand am 06.10.2026 alle **503 Unit-Tests in 31 Klassen** ohne Fehlschlag, Fehler oder übersprungenen Fall. Die heruntergeladenen JUnit-Berichte bestätigen gegenüber dem zuvor geprüften Stand mit 410 Fällen **93 zusätzliche Tests**. Derselbe [GitHub-Actions-Lauf 37517145174](https://github.com/shedowe19/routely/actions/runs/37517145174) bestand vollständiges `lintDebug`, `lintVitalRelease` sowie Debug- und unsignierten Release-Build. Der heruntergeladene Debug-Lintbericht enthält **0 Fehler und 86 Warnungen**, darunter 40 Bibliotheks- und drei Buildplugin-Versionshinweise. Die Metadaten beider APK-Artefakte und der Berichte gehören zum genannten Code-Commit; dessen Wiki-Synchronisierung war ebenfalls erfolgreich. Das belegt ausgeführte Kotlin-Tests und Android-Builds, keine Geräte-/Compose-/Audiozustellung und kein neues signiertes Release.

TODO: Auf dem Gerät verzögerte Antworten mit Logout/Kontowechsel, Rotation, schnelle Auswahl-/Suchwechsel, gleichzeitig laufenden Refresh und Pagination, wiederholtes Tippen auf Like/Lesemarkierung/Bestätigung sowie den Upgrade-Neuaufbau des Feedcaches prüfen. Alte oder revisionslose Stopp-Benachrichtigungen dürfen eine neu gestartete Fahrt beziehungsweise Erkennung nicht beenden. Für Android 15+ den `dataSync`-Timeout auf einem Testgerät ausdrücklich per Kompatibilitätsgrenze aktivieren, Cleanup ohne verspätete Neuveröffentlichung und sichtbaren Wiederanlauf aus erhaltenem Fahrtcache prüfen. Target 34 aktiviert das reguläre Sechs-Stunden-Budget noch nicht. Außerdem Widget-Systemupdates mit `exported=false`, tatsächliche Eingangs-Geschwindigkeitswerte und Erkennungssprünge prüfen; kein realer Nutzer-Fix-/Audioverlauf wurde dadurch reproduziert.

## Native Bahn-/Tram-Geometrie und Bibliotheksmigration vom 06.10.2026

Die [native Streckenform](../entscheidungen/2026-10-06-native-streckenverlaeufe.md) ergänzt Regressionen mit eingefrorenen öffentlichen Antworten und synthetischen Messfolgen:

- `TransitRouteParserTest`: echte ICE- und Tramformen, eindeutige monotone Besuchszuordnung, nächste Kandidaten vor globaler Bindung, Schleifen und Kreuzungen, strikt begrenzte GeoJSON-Struktur sowie reine, verdichtete und geodätische Stationssehnen. Jede ursprüngliche Teilpaarung über gestrichene Zwischenhalte benötigt eigenen Kurvenbeleg; ein benachbarter echter Kurvenabschnitt legitimiert keine fehlende Teilform.
- `TransitRouteRepositoryTest`: Bearer ausschließlich am gewählten HTTPS-Ursprung, getrennte Sitzungsstores und exakte Besuchsbasen, begrenzte Parallelität und RAM-Größe, Softrefresh nach 14 Minuten, harter Ablauf nach 15 Minuten ohne Verlängerung durch Fehler, zweiminütiger Fehlcache sowie uncached 401/403/406. Geteilte Abrufe überleben einen einzelnen abgebrochenen Mitwartenden; Store-Schließung verwirft auch verspätete nicht abbrechbare Antworten.
- `RailStationTrackingEngineTest` und `GpsRailGeometryTest`: gerichteter Kurvenfortschritt statt ungeeigneter Haltsehne, kein Sehnenrückfall für einen Fix außerhalb einer gültigen Form, Mehrdeutigkeit, neue Bewegungsbasis bei tatsächlichem Formwechsel und erhaltene bestätigte Ereignisse. Ein anderer Abrufzeitpunkt derselben Form beziehungsweise vorgeladene Folgeform erzeugt keinen Reset oder neuen Fix. Physische Ankunft, Wiederverankerung, Zielabschluss und die 75-Meter-/30-Sekunden-/Bewegungsgrenzen bleiben getrennt; lange Railformen erhöhen das 90-Minuten-Prognoseintervall nicht.
- `TransitRouteTrackingTest`: bekannte Schienenkategorien statt Bus/SEV, vollständiges eindeutiges Check-in-Fenster einschließlich Streichungen, Sitzung/Fahrtgeneration/Anfragebasis vor Übernahme, Softrefresh und Ablauf, keine späte Veröffentlichung nach Stop. Eine frische aktive Railbasis darf trotz eines über 90 Minuten langen Fahrintervalls ohne GPS-ETA sichtbar sein; nach Fixablauf verschwindet auch dieser Quellenbeleg.
- `NativeRailGpsTest`: öffentliche ICE-Form über Parser → geordnete Stationsengine → Zeitschätzer auf einer tatsächlichen Formkurve außerhalb der Haltsehne. Die räumlichen Formpunkte sind öffentlich, die Zeit-/GPS-Folge ist synthetisch; der Fall zeichnet keine reale Gerätefahrt nach.

Die [öffentlichen Träwelling-Fixtures](../../../app/src/test/resources/routing/transit/README.md) enthalten die anonym am 06.10.2026 abgerufenen Formen und begrenzten Haltlisten für ICE 929 sowie Dresdner Tram 7. Sie enthalten keine Zugangsdaten, Gerätepositionen oder Bewegungshistorien. Die Kotlin-Tests verwenden diese Dateien ohne laufenden Netzwerkabruf. Die Quellenprobe belegt das Antwortformat und geeignete Teilgeometrie, keine Garantie aktueller offizieller Gleisführung.

Der aktualisierte [Bibliotheks-/Buildstand](./build.md) muss Unit-Tests, vollständiges Debug-Lint sowie Debug- und Release-Build mit Kotlin-Compose-Plugin, KSP und Coil 3 bestehen. Die kompatiblen Pins stammen aus Primärdokumentation und tatsächlich aufgelösten AAR-/POM-Metadaten; Versionsprüfung allein ist kein erfolgreicher Android-Build. Ein neuer Ausführungsnachweis wird ausschließlich dem konkret geprüften Commit zugeordnet. Ältere grüne Nachweise oben belegen diese Erweiterung noch nicht.

Der umgesetzte Main-Code `028ffe32a8338761e4adefc20a67a500404a73a5` bestand im [GitHub-Actions-Lauf 37521497829](https://github.com/shedowe19/routely/actions/runs/37521497829) den vollständigen Aufruf `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --stacktrace`: **BUILD SUCCESSFUL in 8m 4s**. Die heruntergeladenen JUnit-XMLs bestätigen **571 Tests in 37 Klassen, 0 Fehlschläge, 0 Fehler und 0 übersprungene Fälle**, somit 68 zusätzliche Regressionen gegenüber dem unmittelbar vorher geprüften Code. Debug-Build, unsignierter Release-Build und `lintVitalRelease` sind erfolgreich; die [Wiki-Synchronisierung 37521499667](https://github.com/shedowe19/routely/actions/runs/37521499667) für diesen Commit ebenfalls.

Der heruntergeladene vollständige Debug-Lintbericht enthält **0 Fehler und 53 Warnungen**. Dazu gehören ein AGP-, sechs AndroidX-/BOM- und sieben weitere Versionshinweise; höhere inkompatible Android-Varianten wurden bewusst anhand ihrer Paketanforderungen zurückgestellt. Die übrigen Hinweise betreffen unter anderem Ziel-API, Akkuverhalten, Icons, KTX und Texte. Der unabhängige lokale Lauf mit denselben Quellen und Gradle 8.14.5/JDK 17/SDK 36 bestand alle vier Aufgaben in 8m 6s und ebenfalls alle 571 Tests; seine 43 Lint-Warnungen unterscheiden sich ausschließlich durch verfügbare beziehungsweise zwischengespeicherte Versionshinweise. Lokales `dependencyInsight` bestätigt `kotlin-stdlib:2.3.21` ohne 2.4-Abhängigkeit.

Alle vier Artefaktarchive sind dem genannten Code-SHA zugeordnet, ihre ZIP-Digests wurden gegen die GitHub-Metadaten und ihre CRCs geprüft. Beide enthalten jeweils eine vollständige APK mit Manifest und DEX: `app-debug.apk` mit 23.328.288 Bytes und `app-release-unsigned.apk` mit 16.299.344 Bytes. Dieser Nachweis umfasst ausgeführte Kotlin-Tests und Android-Builds; ein signiertes Release und die unten aufgeführten Geräteprüfungen wurden dadurch nicht ausgeführt.

TODO: Auf dem Gerät Kurven, parallele Gleise, Rundfahrten, Umleitungen, Tunnel und GPS-Rückkehr, harte Form-/Fixabläufe, Kontowechsel und Netzverlust prüfen. Quellenhinweis und GPS-Zeit müssen unabhängig und fristgerecht zurückfallen. Laufzeit und Speicherverbrauch langer Formen sowie Coil-3-Bilder/HTTP-Cache, Material-3-Refresh und geänderte Compose-Layouts bleiben Geräteprüfungen; es liegt kein aufgezeichneter Nutzer-Fix-/Audioverlauf für diese Erweiterung vor.

## Erneuter Main-Review nach der Streckenmigration

Der eigene [CI-Lauf 37523081347](https://github.com/shedowe19/routely/actions/runs/37523081347) von Main `9415e290309bb3e3d2e108e6ba9ca42f4fe03243` ist ebenfalls erfolgreich: 571 Tests in 37 Klassen, 0 Fehlschläge/Fehler/Skipped, Debug-/unsignierter Release-Build sowie vollständiges Debug-Lint mit 0 Fehlern und 53 Warnungen. Dieser Commit unterscheidet sich vom oben geprüften produktiven `028ffe32…` ausschließlich durch die vorherige Testnachweisdokumentation.

Der anschließende [Review mit sechs unabhängigen Perspektiven](./main-review-2026-10-06.md) fand ursprünglich 16 priorisierte Befunde. Zwei zusätzliche temporäre Testklassen gegen unveränderten Produktivcode bestätigten drei fehlende Schutzinvarianten: Zielansage und Fahrtabschluss nach einem unplausiblen kurzen GPS-Sprung sowie Rücksetzen eines inzwischen serverseitig geänderten Ziels bei reinem Textedit. Alle drei Assertions schlugen wie vorhergesagt fehl; es gab keine Compilefehler. Der gezielte Gradle-Lauf dauerte 17 Sekunden. Diese reproduzierten Lücken sind nicht von der grünen bestehenden Suite abgedeckt. Die temporären Quelltests wurden anschließend aus dem Repository entfernt; der ursprüngliche reine Review veröffentlichte noch keine Codekorrektur. Die nachfolgende Umsetzung ergänzt dauerhafte Regressionen.

Die bestätigten Lücken sind inzwischen mit den [Befundkorrekturen](./main-review-2026-10-06.md) dauerhaft abgesichert. Geräte-GPS, Samsung/Doze und tatsächliche Audiozustellung bleiben ungeprüft.

## Vollständige Befundkorrekturen vom 06.10.2026

Die 16 Befunde sind im [Main-Review mit Korrekturtabelle](./main-review-2026-10-06.md) als umgesetzt dokumentiert. Sechs Implementierer und gegenseitige Quellprüfungen decken GPS/Android-Laufzeit, Daten/UI, Auth/Backup und Release ab. Produktive Bibliothekspins bleiben unverändert; `kotlinx-coroutines-test` ergänzt ausschließlich die kontrollierbaren Test-Gateways.

87 zusätzliche Android-Regressionen gegenüber dem zuvor geprüften Stand sichern insbesondere diese Verträge:

- `StationTrackingJumpTest` (6) und vier zusätzliche Clockfälle: A → unplausibles B → A, kein falscher Zielabschluss/Ansageverbrauch, plausible schnelle Fahrt, Uhrsprung und Replay ohne neue Beobachtung.
- `LifecycleRetryBudgetTest` und `SpeechInitializationLifecycleTest` (zusammen 10): begrenzte Backoffs, Besitzerwechsel, Init-/Retryzustand und verspätete Enginecallbacks.
- `StatusEditRequestTest` (4 zusätzlich), `NotificationControllerTest` (7), `FeedControllerTest` (7), `DeletedStatusCompletionTest` (4): Editor-Anfangsabsicht, bestätigte Lesemarkierungen, geordnete Countantworten, Mutationsereignisse, späte GETs und einmaliger erfolgreicher DELETE-Abschluss trotz lokalem Fehler. Controller verwenden virtuelle Coroutine-Zeit und verzögerte Antworten.
- `NearbyStationIdentityTest` (6), `DepartureTimePresentationTest` (6), `CheckInSubmissionTest` (12), `StatusMutationRepositoryTest` (12): nahe verschiedene IDs, Echtzeit/Verfrühung, unveränderte POST-Planmarker und nachgelagerte Istzeit-PUTs, Teilerfolg, fehlende akzeptierte Antwort sowie gleiche Credentials nach neuer Loginrevision. Zwei Repositoryinstanzen teilen sich bei der verspäteten GET-Regressionsprüfung denselben DAO-/Mutationsstore.
- `AuthRepositoryTest` (9 zusätzlich): fehlender/blanker Refresh-Ersatz, echte Rotation, präzises invalid_grant und Erhalt bei generischen/malformed/temporären Fehlern.

26 bestehende Engine-Fixfolgen wurden zeitlich plausibel gemacht, weil sie bisher große Ortswechsel innerhalb einer Sekunde enthielten. Schutzgrenzen und bestehende Assertions bleiben erhalten; lediglich die Wiederverankerung nach mehrfach verworfenen Kurzsprungausreißern benötigt nun eine echte längere Lücke und danach neue unabhängige Belege.

658 Androidtests in 47 Klassen sind im frischen lokalen Lauf erfolgreich: 0 Fehlschläge, 0 Fehler, 0 übersprungen. Zusätzlich bestehen 41 deterministische Python-Releaseguardtests ohne Netzwerk- oder Releasewrites. Beide Workflows führen diese Python-Tests aus: `python3 -m unittest discover -s .github/tests -p 'test_release_guard.py'`.

Der vollständige frische Lauf mit `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-build-cache` ist nach 4 Minuten 15 Sekunden erfolgreich; alle 108 ausführbaren Tasks wurden ausgeführt. Debug-Lint enthält 0 Fehler und 40 Warnungen, Release-Vital-Lint ist ebenfalls erfolgreich. Debug-APK und unsignierte Release-APK wurden gebaut. Die 189 Nicht-Wiki-Eingabedateien blieben vor/nach dem Lauf per SHA-256 identisch. Versionshinweis-Warnungen können sich je erreichbarer Maven-Metadatenquelle von den historischen CI-Warnungszahlen unterscheiden.

Zwei vorherige lokale APK-Versuche fanden eine doppelte `androidx.activity.compose.R` in unterschiedlich alten Dexarchiven; auch der erste Gradle-Clean-Lauf behielt alte Zwischenartefakte. Nach vollständig entferntem generiertem `app/build` und deaktiviertem Buildcache wurden beide APKs erfolgreich frisch gebaut. Dafür wurde kein Produktivcode geändert und keine Prüfung unterdrückt. Der grüne Neuaufbau ist der lokale Buildnachweis dieser Korrektur; der ursprüngliche fehlgeschlagene Lauf wird nicht als erfolgreicher APK-Build gewertet.

Die Android-Adapter sind gegengeprüft, jedoch nicht instrumentiert auf einem physischen Gerät getestet. Die Backupregelwerke wurden als XML und in beiden zusammengeführten Manifesten geprüft. Tatsächliche FLP-/TTS-Bindung, Display-aus-/Doze-/OEM-Zustellung, Backup-Restore und erster neuer signierter Release bleiben Systemprüfungen. Es wurden keine echten Kontotokens verwendet, keine schreibenden Träwelling-Livetests ausgeführt und kein signierter Release veröffentlicht.

## Authentifizierte Live-Prüfung vom 05.10.2026

Zusätzlich wurden 16 lesende Anfragen gegen `https://traewelling.de` durchgeführt. Alle lieferten HTTP 200; die geprüften Antwortstrukturen entsprachen dem erwarteten Vertrag. Die folgenden Pfade haben jeweils das Präfix `/api/v1/`:

| Bereich | Geprüfte GET-Endpunkte |
| --- | --- |
| Nutzer | `auth/user`, `user/{username}`, `user/{username}/statuses`, `user/search/{query}` |
| Feeds und Statistik | `dashboard`, `statuses`, `statistics` |
| Benachrichtigungen | `notifications`, `notifications/unread/count` |
| Stationssuche | `trains/station/autocomplete/{query}`, `trains/station/nearby`, `stations` mit Bounding-Box |
| Abfahrt und Fahrt | `station/{id}/departures`, `trains/trip` |
| Status und Halte | `status/{id}?withIdentifiers=true`, `stopovers/{tripId}` |

Bestätigt wurden insbesondere verschachtelte Stationsobjekte bei Abfahrten und Fahrthalte mit eigenen UUIDs, Plan-/Echtzeitfelder sowie die Map-Struktur der Stopovers-Antwort. Die UUIDs von Einstieg und Ziel des Status stimmten mit den entsprechenden Halten aus `stopovers` überein.

Die Produktion lieferte weiterhin einige deprecated Felder wie `train`, `userDetails`, `delay` und `stop`; Operatoren enthielten eine numerische `id` und eine `uuid` als String. Der Produktionsstand unterscheidet sich somit vom geprüften Upstream-`develop`. Routely verwendet weiterhin die neuen Felder und berücksichtigt beide Operator-ID-Formate.

Diese Live-Prüfung umfasst ausschließlich GET-Anfragen. Check-in-Erfolgs-/Konfliktantworten (201/409) und Statusänderungen per PUT wurden über Vertrag und Unit-Test-Fixtures geprüft, nicht durch schreibende Live-Aufrufe. Es wurden keine Daten erstellt, geändert oder gelöscht, keine Benachrichtigungen als gelesen markiert und kein Logout ausgelöst. Der Token blieb im Prozessspeicher; Zugangsdaten und persönliche Antwortdaten wurden nicht ins Repository oder Wiki übernommen.

## Nachreview von Main f406bad

Der [CI-Lauf 37529409222](https://github.com/shedowe19/routely/actions/runs/37529409222) bestätigt exakt `f406bad3ba44bf2f008eb09f1856590c7183770d`: 658 Unit-Tests in 47 Klassen ohne Fehlschläge/Fehler/übersprungene Tests, 41 Releaseguardtests, beide APK-Builds und Debug-Lint mit 0 Fehlern und 53 Warnungen. JUnit-/Lint-Artefakte wurden heruntergeladen und unabhängig gezählt. Die Differenz zu 40 lokalen Warnungen besteht aus 13 zusätzlichen Versionshinweisen.

Gegen unveränderten Produktivcode wurden anschließend sieben temporäre JUnit-Klassen mit neun Prüffällen ausgeführt. Sieben Schutzassertions schlugen gezielt fehl: häufige Fixe ohne beobachtete Abfahrt (G5), native Polyline-Endprojektion (G7), zwei GET/Like-Reihenfolgen (D5), neue Meldung während verzögerter MarkAll-Antwort (D6), verborgen mitgesendete Sichtbarkeit (D4) und zwei erfolgreiche PUT-Antworten in umgekehrter Auslieferungsreihenfolge (D8). Zwei erfolgreiche Clock-/Engine-/Adapterkontrollen zeigen das unterschiedliche Wiederverankerungsverhalten für 150- gegenüber 80-Meter-Fixes (G6). Keine Probe führte einen Android-Service aus. Die Gradle-Läufe kompilierten und endeten nach 20 beziehungsweise 19 Sekunden ausschließlich an den erwarteten Assertions.

Drei weitere Offline-Python-Prüfungen gegen den echten Releaseguard bestätigen R4: Code 18 bleibt veröffentlicht → nächster Code 19; derselbe Release wird zum Draft beziehungsweise gelöscht, sein Tag bleibt → nächster Code 14. Zwei Schutzassertions `>18` schlagen fehl, die erhaltene Verlaufskontrolle besteht. Keine Probe verwendet Netzwerkzugriff, Zugangsdaten, Signing oder tatsächliche Releasewrites.

Alle sieben temporären Kotlin-Klassen wurden danach entfernt. Ein erneuter lokaler Lauf `:app:testDebugUnitTest` bestand in 17 Sekunden mit allen ursprünglichen 658 Tests/47 Klassen und null Fehlschlägen/Fehlern/übersprungenen Tests. Produktivcode und dauerhafte Tests blieben unverändert. Die zusätzliche Prüfmatrix ist getrennt von der grünen vorhandenen CI-Suite; dauerhafte Regressionen werden bei den späteren Korrekturen ergänzt. Die zwölf neuen Befunde samt statischen Lifecycle-/UI-Belegen stehen im [Main-Nachreview](./main-review-2026-10-06.md).

TODO: Rotation/Editorerhalt, Notification-Navigation während POST, Settings-TTS-Initialfehler und TalkBack-/Semantiklabels zusätzlich instrumentiert prüfen. Die Codeverträge beweisen die jeweiligen Pfade, keine tatsächlich ausgeführte Geräteprüfung.

## Dauerhafte Korrekturen des zwölfteiligen Nachreviews

Der endgültige, nach Abschluss aller produktiven Änderungen neu kompilierte Code besteht **726 Android-Unit-Tests in 56 Klassen**, mit 0 Fehlschlägen, Fehlern und übersprungenen Tests. Die JUnit-XML-Dateien wurden unabhängig gezählt. Gegenüber Main `2121cc1` sind 68 dauerhafte Regressionen ergänzt. Alle **56 Offline-Python-Releaseguardtests** bestehen ebenfalls; vorher waren es 41. Es gab keine schreibenden Träwelling-Liveanfragen, keine Verwendung echter Kontotokens und keinen signierten Release.

| Bereich | Zusätzliche Regressionen | Kontrollierte Grenzen |
| --- | --- | --- |
| GPS-Abfahrt und Linienendpunkte | 13 | Häufige Fixe, Verfrühung, fehlende Geschwindigkeit, Lücke/Sprung, gestützter Ursprung, Kurven/Mehrdeutigkeit, verdichtete Endkanten |
| Clock-/Recoveryadapter und Settings-TTS | 10 | Unbrauchbare neue vs. alte Fixes, vollständige neue Recoveryfolge, unabhängiger Enginekatalog, Fehler/Retry/Timeout, alte und synchrone Callbacks |
| Editierabsicht, Detailzustand, Check-in und Cleanup | 14 | Unveränderte Sichtbarkeit/Text, ausdrückliches Leeren, Composition-Leases, Busygrenze, verzögerter POST/PUT, lokale Bereinigungsfrist |
| Meldungen | 4 | Servercommit vor verspäteter PUT-Antwort, neue Rows, GET-Start ohne Snapshotbeleg, Rollback trotz Countfehler |
| Feed-, Repository- und Profilreihenfolge | 27 | Alte Likes/GETs, Cross-Repository-Schreibsperre, wartender/abgeschickter Abbruch, Roomepoch, Bodyless-Verifikation auf Seite 1, Follow-/Profil-/Sessiongrenzen und sichtbarer Fehler |

Der vollständige frische lokale Lauf mit `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-build-cache` aus bereinigtem `app/build` bestand nach 3 Minuten 28 Sekunden (108 Tasks, 107 ausgeführt). Nach der abschließenden `onCleared`-Absicherung wurden Produktivcode und Tests mit `-Pkotlin.incremental=false` erneut kompiliert und dieselben vier Aufgaben erfolgreich ausgeführt: 2 Minuten 32 Sekunden, 108 Tasks, 26 ausgeführt und 82 unveränderte Aufgaben aktuell. Die 41 veränderten Produktiv-/Test-/Konfigurationsdateien blieben während dieses letzten Laufs per SHA-256 identisch. JUnit bestätigt weiterhin 726 Tests/56 Klassen ohne Fehler, Fehlschläge oder übersprungene Tests. Debug-Lint hat 0 Fehler und 42 Warnungen; zwei erreichbare Maven-Versionshinweise erklären den Unterschied zum früheren lokalen 40-Warnungen-Stand. Release-Vital-Lint, Debug-APK und unsignierte Release-APK sind erfolgreich. Beide zusammengeführten Manifeste enthalten weiterhin die Backup-/Transferausschlüsse.

Vorbereitende Läufe mit veralteten Inkrementalartefakten, einem korrigierten Testtypfehler beziehungsweise einer während der Gegenprüfung ergänzten Profilregression vor deren Neucompilierung werden nicht als Endnachweis gewertet. Alle endgültigen Regressionen prüfen die tatsächlichen Produktionsgrenzen; UI-Semantik wurde zusätzlich am Quellvertrag geprüft, nicht durch einen fingierten Gerätetest. Der Main-Nachweis wird in der [API-Compatibility-CI](https://github.com/shedowe19/routely/actions/workflows/api-compatibility.yml) und dem [Wiki-Sync-Workflow](https://github.com/shedowe19/routely/actions) gegen den veröffentlichten Commit kontrolliert.

Die unabhängige Gegenprüfung umfasst GPS/Geometrie, Android-Service/Audio/Notification/Widget, API-/Daten-/Cacheconsumer, UI/Editor/Navigation/Zugänglichkeit sowie Auth/Backup/Release. Währenddessen bestätigte Folgefehler wurden vor dem Endstand korrigiert. Umsetzung und Prüfgrenzen stehen im [Main-Review](./main-review-2026-10-06.md) und in der [ADR Nachreview-Korrekturen](../entscheidungen/2026-10-06-nachreview-korrekturen.md).

## Weiterer Nachreview von Main 443d6e1

Der [CI-Lauf 37536735143](https://github.com/shedowe19/routely/actions/runs/37536735143) auf exakt `443d6e17af153d9b81fac8498fc04b9a93519437` ist grün. Die heruntergeladenen JUnit-/Lint-Artefakte wurden unabhängig ausgewertet: **726 Tests/56 Klassen**, null Fehler/Fehlschläge/Skips, **56 Offline-Releaseguardtests**, Debug-Lint **0 Fehler/53 Warnungen**, Debug- und unsignierter Release-Build. Alle 108 ausführbaren Tasks liefen in 4 Minuten 20 Sekunden. Elf zusätzliche erreichbare Gradle-/Maven-Versionshinweise erklären den Unterschied zum lokalen 42-Warnungen-Stand. [Wiki-Sync 37536735025](https://github.com/shedowe19/routely/actions/runs/37536735025) ist ebenfalls erfolgreich.

Der erneute Quellreview verwendete fünf unabhängige Subagents plus Root. Für neue GPS-/Antwortreihenfolgefälle wurden fünf ausschließlich temporäre Quelldateien in die Testquelle kopiert, ohne Produktivcode zu verändern. Der gezielte Lauf umfasste acht Tests in vier Klassen und endete nach 31 Sekunden mit sieben erwarteten Schutzassertionen und einem bestandenen Vergleich; er ist ausdrücklich **kein grüner Suitennachweis**:

| Probe | Ausgeführte Fälle | Ergebnis gegen unverändertes Main |
| --- | --- | --- |
| `GpsLatestReviewProbe` | Bootstrap und Gap auf echter nativer Kurve mit 24-Meter-/3-Sekunden-Fixes; 180-Meter-Haltfolge; Istabfahrt bei 100 Minuten Planfahrt und nullable Folgeankunft | Fünf Schutzassertionen scheitern; Vorbedingungen und kontrollierte ETA-Ablehnung bestehen. Belegt G8–G10. |
| `FeedTabSwitchLikeReviewTest` | Alter Dashboard-Like bestätigt während neuer erster Globaltabladung | `[]` statt `[99]`; belegt D10. |
| `UserProfileFollowReadReviewTest` | Alter Profil-GET nach bestätigtem Follow | `following=false` statt `true`; belegt D11. |
| `EditedActiveDestinationReviewTest` | Gleiche plausible Fixfolge am alten Ziel bei alter gegenüber aktualisierter Zielrolle | Vergleich besteht: alte Route beendet/ansagt Ziel, aktualisierte Route fährt weiter. D9s Service-/UI-Trigger zusätzlich von zwei Sichten geprüft. |

Danach wurden alle fünf temporären Quelldateien entfernt; alle 264 ursprünglichen Dateien waren vor Wikiänderung SHA-256-identisch. Der erste Wiederherstellungslauf fand noch 14 alte temporäre `.class`-Dateien in `app/build/tmp/kotlin-classes/debugUnitTest` und führte deshalb weiterhin 734 statt 726 Tests aus. Er wird nicht als Baseline-Nachweis verwendet. Nach Entfernen ausschließlich dieses Test-Ausgabeverzeichnisses und Neucompilierung mit `-Pkotlin.incremental=false` besteht `:app:testDebugUnitTest --no-build-cache` nach 31 Sekunden mit **726 Tests/56 Klassen**, null Fehler/Fehlschläge/Skips. Die temporären Proben gehören nicht zum veröffentlichten Repository. Die Releaseguardsuite wurde im neuen Review ebenfalls nochmals mit 56/56 erfolgreichen Offline-Tests ausgeführt.

U8 (Zurück nach erkanntem Einstieg) ist statisch am echten Navigationszustand belegt. U9 (System-Zurück während Check-in-POST) kombiniert zwei unabhängige Codesichten mit dem [Android-Primärvertrag](https://developer.android.com/guide/components/activities/tasks-and-back-stack#back-tap-behavior-for-root-launcher-activities): Root-Back beendet Activity bis Android 11, ab Android 12 verschiebt es regulär in den Hintergrund. Keine Android-8–11-Emulator-/Geräteausführung wurde durchgeführt. Abgeschickte manuelle Zeit-PUTs bleiben durch den neuen NonCancellable-Schreibabschluss geschützt. Details und Prioritäten aller acht Befunde stehen im [weiteren Main-Review](./main-review-2026-10-06.md#weiterer-nachreview-von-main-443d6e1).

## Vollständige Umsetzung der acht Befunde vom 07.10.2026

Die endgültige, aus bereinigtem `app/build` neu kompilierte Suite besteht **788 Android-Unit-Tests in 62 Klassen**, ohne Fehler, Fehlschläge oder übersprungene Tests. Gegenüber Main `4f9cfd2` sind 62 dauerhafte Regressionen ergänzt: GPS elf, Feed/Follow siebzehn, Check-in neun, Service/Repository/Preferences zwanzig und Detailabruf fünf. Die JUnit-XML-Berichte wurden unabhängig gezählt; die 198 eingefrorenen Quell-/Test-/Konfigurationsdateien blieben während des abschließenden Builds SHA-256-identisch.

Der vollständige Lauf `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-build-cache --max-workers=2 -Pkotlin.incremental=false` besteht nach **3 Minuten 54 Sekunden**; 108 Tasks, 107 ausgeführt und eine unveränderte Generierungsaufgabe aktuell. Debug-Lint meldet **0 Fehler und 43 Warnungen**. Debug-APK und **unsignierte** Release-APK sowie Release-Vital-Lint sind erfolgreich. Kein signierter Release oder schreibender Träwelling-Aufruf wurde ausgeführt.

Alle **56 Offline-Python-Releaseguardtests** wurden im unabhängigen Sicherheits-/Releasevollscan erneut erfolgreich ausgeführt. Der finale Quellscan aus sechs unabhängigen Sichten plus Root bestätigt keine weiteren erreichbaren Codebefunde. Kontrollierte Fälle umfassen das echte Parser→Engine→Estimator-Zusammenspiel, Statuswrite-/Cache-/Completionreihenfolge, verzögerte Feed-/Followantworten und StatusDetailrevision bis nach Stopover-GET.

Der erste kompilierte 788-Test-Lauf hatte einen Fehlschlag in einer bestehenden Feed-Testfixture: Deren alte Deferred-Antwort wurde auch dem neu nach Likebestätigung gestarteten GET gegeben. Die korrigierte Fixture trennt alten und frischen Request, behält den Schutzassert unverändert und prüft zusätzlich den frischen Likecount. Der oben angegebene vollständige Neuaufbau enthält diese Korrektur und keine temporären Klassen.

System-/Gesten-Zurück auf API 26–30, echte Android-Prozess-/Speicher-/Restoreabläufe, Doze/OEM/TTS, TalkBack und GPS-Güte bleiben praktisch zu prüfen. JVM-Tests behaupten keine solche Zustellung. Aktuelle Verträge, zusätzlicher Randfallabgleich und Wiki-Pflege stehen im [Umsetzungs-/Vollscanbericht](./main-review-2026-10-06.md#umsetzung-der-acht-befunde-und-erneuter-vollscan).

## Offene Fragen

- TODO: Bei Bedarf die erfolgreiche einmalige GET-Prüfung als wiederholbare Integrationstests einrichten und eine nicht versionierte Tokenbereitstellung festlegen.
- `kotlinx-coroutines-test` ist als Testabhängigkeit derselben Coroutines-Version ergänzt. Meldungs- und Feedcontroller verwenden `runTest`, kontrollierte Test-Dispatcher und verzögerte Antworten; keine sleeps ersetzen diese Reihenfolgeprüfungen.

## Verwandte Seiten

- [Setup](./setup.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Build](./build.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
- [TripTracking](../module/trip-tracking.md)
- [GPS-Zeiten](../module/gps-zeiten.md)
- [StatusDetail](../module/status-detail.md)
- [Fahrterkennung](../module/ride-recognition.md)
- [Fahrtänderungen](../module/trip-changes.md)
- [Reisefortschritt](../module/trip-progress.md)
