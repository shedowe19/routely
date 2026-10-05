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

`.github/workflows/api-compatibility.yml` führt die Unit-Tests und den Debug-Build bei Pull Requests sowie bei manuellem Start aus. Der Workflow richtet JDK 17, Android-SDK 34 und Build Tools 34.0.0 ein. JUnit-Ergebnisse und HTML-Testberichte werden als `api-compatibility-test-results` gespeichert.

## Ergebnis der Migration vom 05.10.2026

GitHub Actions hat am 05.10.2026 für Commit `4186a4e69850840556980109c6f4696d5e557cbe` alle 28 Unit-Tests und den vollständigen Android-Debug-Build erfolgreich ausgeführt (`./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace`). Der Prüfstand umfasst 27 neue Modelltests und den vorhandenen Retrofit-Endpunkttest. Die JUnit- und HTML-Berichte sind im Workflow-Artefakt gespeichert. Ein authentifizierter Live-Test gegen eine Träwelling-Serverinstanz ist damit nicht abgedeckt.

## Offene Fragen

- TODO: Festlegen, ob Integrationstests gegen die Träwelling-API eingerichtet werden sollen und wie lokale Test-Tokens sicher eingespeist werden.

## Verwandte Seiten

- [Setup](./setup.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Build](./build.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
