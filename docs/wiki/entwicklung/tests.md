# Entwicklung: Tests

## Zweck

Dokumentiert, wie die App getestet wird.

## Testen

- **Unit-Tests ausführen**: `./gradlew test` oder spezifisch `./gradlew testDebugUnitTest`.
- **Coroutines testen**: Nutzung von `TestScope` und `runTest` in Unit-Tests für ViewModels oder asynchrone Repositories.
- Aktuell sind keine Testquellen unter `app/src/test` oder `app/src/androidTest` vorhanden.
- API-nahe Tests mit echten Tokens sind derzeit nicht als automatisierte Tests eingerichtet. Falls sie ergänzt werden, müssen Tokens lokal und nicht versioniert bereitgestellt werden.

## Voraussetzungen

- Lokale Gradle-Aufrufe benötigen JDK 17.
- Der Unix-Wrapper `gradlew` muss mit LF-Zeilenenden ausgecheckt sein; dies wird zusammen mit weiteren Projekttextdateien über `.gitattributes` erzwungen.

## Offene Fragen

- TODO: Festlegen, ob Integrationstests gegen die Träwelling-API eingerichtet werden sollen und wie lokale Test-Tokens sicher eingespeist werden.

## Verwandte Seiten

- [Setup](./setup.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
