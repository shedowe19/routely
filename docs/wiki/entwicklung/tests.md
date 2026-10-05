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
- Unit-Tests liegen unter `app/src/test`; Instrumentierungstests unter `app/src/androidTest` sind noch nicht vorhanden.
- API-nahe Tests mit echten Tokens sind derzeit nicht als automatisierte Tests eingerichtet. Falls sie ergänzt werden, müssen Tokens lokal und nicht versioniert bereitgestellt werden.

## Voraussetzungen

- Lokale Gradle-Aufrufe benötigen JDK 17.
- Der Unix-Wrapper `gradlew` muss mit LF-Zeilenenden ausgecheckt sein; dies wird zusammen mit weiteren Projekttextdateien über `.gitattributes` erzwungen.
- Für den Android-Gradle-Lauf werden außerdem Android-SDK 34 und die auflösbaren Gradle-/Maven-Abhängigkeiten benötigt.

## Automatisierte Prüfung

`.github/workflows/api-compatibility.yml` führt die Unit-Tests und den Debug-Build bei Pull Requests, Pushes auf `codex/traewelling-api-2026` sowie bei manuellem Start aus. Der Workflow richtet JDK 17, Android-SDK 34 und Build Tools 34.0.0 ein. JUnit-Ergebnisse und HTML-Testberichte werden als `api-compatibility-test-results` gespeichert.

## Ergebnis der Migration vom 05.10.2026

TODO: Android-Unit-Tests und Debug-Build sind noch ausstehend. In der lokalen Ausgangsumgebung stehen JDK 17, aber kein Android-SDK und kein Gradle-Abhängigkeitscache zur Verfügung. Ein angelegter Test oder Workflow ist kein erfolgreicher Testlauf; das Ergebnis muss nach tatsächlicher Ausführung ergänzt werden.

## Offene Fragen

- TODO: Festlegen, ob Integrationstests gegen die Träwelling-API eingerichtet werden sollen und wie lokale Test-Tokens sicher eingespeist werden.

## Verwandte Seiten

- [Setup](./setup.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Build](./build.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
