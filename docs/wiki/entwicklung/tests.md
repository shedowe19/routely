# Entwicklung: Tests

## Zweck

Dokumentiert, wie die App getestet wird.

## Testen

- **Unit-Tests ausführen**: `./gradlew :app:testDebugUnitTest`.
- **API-Regressionen und Debug-Build zusammen prüfen**: `./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace`.
- **Vollständiger CI-Prüfumfang einschließlich Release**: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --stacktrace`.
- **Coroutines testen**: Nutzung von `TestScope` und `runTest` in Unit-Tests für ViewModels oder asynchrone Repositories.
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

`.github/workflows/api-compatibility.yml` führt bei Pushes auf `main`, Pull Requests und manuellem Start Unit-Tests sowie Debug- und Release-Build aus. Der Workflow richtet JDK 17, Android-SDK 36 und Build Tools 35.0.0 ein. `assembleRelease` prüft zusätzlich die Release-Lint-Anforderungen, die ein reiner Debug-Build nicht abdeckt.

| Artefakt | Inhalt |
| --- | --- |
| `api-compatibility-test-results` | JUnit-Ergebnisse und HTML-Testberichte |
| `routely-debug-apk` | Debug-APK für Geräteprüfungen nach erfolgreichem Build |
| `routely-release-unsigned-apk` | Unsignierte Release-APK nach erfolgreichem Build |
| `release-lint-results` | Vorhandene Release-Lint-Berichte, auch bei fehlgeschlagenem Build |

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

## Offene Fragen

- TODO: Bei Bedarf die erfolgreiche einmalige GET-Prüfung als wiederholbare Integrationstests einrichten und eine nicht versionierte Tokenbereitstellung festlegen.

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
