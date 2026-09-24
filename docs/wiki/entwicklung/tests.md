# Entwicklung: Tests

## Zweck

Dokumentiert, wie die App getestet wird.

## Testen

- **Unit-Tests ausführen**: `./gradlew test` oder spezifisch `./gradlew testDebugUnitTest`.
- **Coroutines testen**: Nutzung von `TestScope` und `runTest` in Unit-Tests für ViewModels oder asynchrone Repositories.
- Der Unit-Test `TraewellingApiServiceTest` prüft den Retrofit-Vertrag für den Abfahrts-Endpunkt, damit die Route nicht versehentlich wieder unter `/api/v1/trains/station/...` geführt wird.
- `TransitousVehicleEstimatorTest` prüft Polyline-Dekodierung, Schätzung entlang der Streckenlänge, aktive Zeitfenster, Deduplizierung und Begrenzung großer Kartenausschnitte. Er benötigt keine Live-Anfrage an Transitous.
- Aktuell existiert eine lokale Unit-Testquelle unter `app/src/test`; Instrumentierungstests unter `app/src/androidTest` sind noch nicht vorhanden.
- API-nahe Tests mit echten Tokens sind derzeit nicht als automatisierte Tests eingerichtet. Falls sie ergänzt werden, müssen Tokens lokal und nicht versioniert bereitgestellt werden.

## Automatische CI-Prüfung

`.github/workflows/android-ci.yml` läuft bei Pull Requests und Pushes auf `main`. Der Workflow nutzt JDK 17 und führt `./gradlew --no-daemon testDebugUnitTest assembleDebug` aus. Der manuell gestartete Release-Workflow mit Signierung ist davon getrennt.

## Voraussetzungen

- Lokale Gradle-Aufrufe benötigen JDK 17.
- Der Unix-Wrapper `gradlew` muss mit LF-Zeilenenden ausgecheckt sein; dies wird zusammen mit weiteren Projekttextdateien über `.gitattributes` erzwungen.

## Offene Fragen

- TODO: Festlegen, ob Integrationstests gegen die Träwelling-API eingerichtet werden sollen und wie lokale Test-Tokens sicher eingespeist werden.

## Verwandte Seiten

- [Setup](./setup.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Build](./build.md)
- [Transitous Live-Karte](../features/transitous-live-karte.md)
