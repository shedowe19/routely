# Entwicklung: Setup

## Zweck

Dokumentiert die Schritte zur lokalen Einrichtung des Projekts.

## Setup

1. **Voraussetzungen**: Android Studio mit Unterstützung für AGP 8.9.1, JDK 17, Android-SDK 36 und Build Tools 35.0.0. Der versionierte Wrapper verwendet Gradle 8.11.1; ein global installiertes Gradle ist nicht erforderlich.
2. **Klonen**: Das Repository klonen.
3. **Öffnen**: Das Projekt in Android Studio öffnen und den initialen Gradle-Sync abwarten.
4. **Ausführen**: App auf Emulator (API 26+) oder echtem Gerät starten.
5. **Kompilieren**: Schnell Kotlin-Code prüfen mit `./gradlew compileDebugKotlin`.
6. **Bauen**: `./gradlew assembleDebug` oder `./gradlew build`.

## Verwandte Seiten

- [Lokale Entwicklung](./lokale-entwicklung.md)
- [Build](./build.md)
- [Tests](./tests.md)
