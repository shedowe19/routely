# Entwicklung: Tests

## Zweck

Dokumentiert, wie die App getestet wird.

## Testen

- **Unit-Tests ausführen**: `./gradlew :app:testDebugUnitTest`.
- **API-Regressionen und Debug-Build zusammen prüfen**: `./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace`.
- **Coroutines testen**: Nutzung von `TestScope` und `runTest` in Unit-Tests für ViewModels oder asynchrone Repositories.
- Der Unit-Test `TraewellingApiServiceTest` prüft den Retrofit-Vertrag für den Abfahrts-Endpunkt, damit die Route nicht versehentlich wieder unter `/api/v1/trains/station/...` geführt wird.
- `ApiCompatibilityTest` lädt Fixtures aus `app/src/test/resources/traewelling/`. Die Antworten lassen auslaufende Kompatibilitätsfelder bewusst weg und unterscheiden Stopover-ID und Station-ID.
- Die Modelltests prüfen verschachtelte Stationen, optionale Kennungen, IBNR/RIL100, numerische und UUID-Operator-IDs, echte und geplante Zeiten, berechnete Abfahrtsverspätungen, Stopover-Erkennung bei wiederholten Stationsbesuchen, Deduplizierung einschließlich verschiedener Stopover-UUIDs und die neuen Check-in-Erfolgs- und Konfliktantworten.
- Statusänderungs-Tests prüfen die gemeinsame Übertragung von `destinationId` und `destinationArrivalPlanned` sowie das Weglassen beider Felder bei reinen Textänderungen.
- `StationTrackingEngineTest` verwendet synthetische Positionen und eine feste Uhr ohne Android oder Netzwerk. Geprüft werden GPS bei Verspätung, Annäherung gegenüber Ankunft, Richtungs-/Abfahrtsfortschritt, ungültige beziehungsweise alte Fixes, Signalunterbrechungen, Fahrplan-Rückfall, Radien, Rundfahrten, Ansagemarkierungen nach Neustart und per Gson restaurierte Route/Fortschritt.
- Unit-Tests liegen unter `app/src/test`; Instrumentierungstests unter `app/src/androidTest` sind noch nicht vorhanden.
- API-nahe Tests mit echten Tokens sind derzeit nicht als automatisierte Tests eingerichtet. Falls sie ergänzt werden, müssen Tokens lokal und nicht versioniert bereitgestellt werden.

## Voraussetzungen

- Lokale Gradle-Aufrufe benötigen JDK 17.
- Der Unix-Wrapper `gradlew` muss mit LF-Zeilenenden ausgecheckt sein; dies wird zusammen mit weiteren Projekttextdateien über `.gitattributes` erzwungen.
- Für den Android-Gradle-Lauf werden außerdem Android-SDK 34 und die auflösbaren Gradle-/Maven-Abhängigkeiten benötigt.

## Automatisierte Prüfung

`.github/workflows/api-compatibility.yml` führt die Unit-Tests und den Debug-Build bei Pull Requests sowie bei manuellem Start aus. Der Workflow richtet JDK 17, Android-SDK 34 und Build Tools 34.0.0 ein. JUnit-Ergebnisse und HTML-Testberichte werden als `api-compatibility-test-results` gespeichert. Nach erfolgreichem Build wird `app/build/outputs/apk/debug/app-debug.apk` als `routely-debug-apk` für Geräteprüfungen bereitgestellt.

## Ergebnis der Migration vom 05.10.2026

GitHub Actions hat am 05.10.2026 für Commit `4ed79c781e5ca38005885fb585277fee56c1cfa4` alle 28 Unit-Tests und den vollständigen Android-Debug-Build erfolgreich ausgeführt (`./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace`). Der Prüfstand umfasst 27 neue Modelltests und den vorhandenen Retrofit-Endpunkttest. Die JUnit- und HTML-Berichte sind im Workflow-Artefakt gespeichert.

## Prüfung der GPS-Erweiterung

Die aktuelle Testsuite umfasst 35 Regressionen in `StationTrackingEngineTest` und 28 API-Regressionen, insgesamt 63. Geprüft werden unter anderem verspätete Fahrten, schnelle Zielvorbeifahrt gegenüber langsamer/stabiler Ankunft, vorläufige Zeitcursor nach Cache-Restaurierung, späten Start am Ursprung, getrennten GPS-aus-/Ausfallmodus, Rundfahrten sowie freigegebene und bestätigte Ansageschlüssel. Der zusätzliche Startfall verhindert, dass ein erster GPS-Fix fern aller Stationen einen vorläufigen Zeitcursor festschreibt.

Der erste vollständige GPS-Prüflauf für Commit `672051411cc1f76bf910c0262b8fbe8710844363` war am 05.10.2026 erfolgreich: 62 Tests (34 GPS und 28 API), Android-Debug-Build und APK-Upload. Nachweis: [GitHub-Actions-Lauf 37349706390](https://github.com/shedowe19/routely/actions/runs/37349706390). Dieser historische Lauf enthält die zuletzt ergänzte 35. GPS-Regression noch nicht. Den aktuellen Prüfstand des gesamten PR zeigt [PR #36: Checks](https://github.com/shedowe19/routely/pull/36/checks).

Eine reine Kotlin-Testreihe bestätigt weder Android-Permissiondialoge, tatsächlich gelieferte Standortintervalle, Display-aus-Betrieb noch Audioausgabe auf einem Gerät. Echte Zug-/Busfahrten und Android-Geräteprüfungen stehen aus; hier ist kein physisches Testgerät verfügbar. Cachetests prüfen die restaurierbaren Daten und Engine-Fortsetzung, keine ausgeführte Android-DataStore-/Service-Integration.

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
