# Entwicklung: Setup

## Zweck

Dokumentiert die Schritte zur lokalen Einrichtung des Projekts.

## Gemeinsame Flutter-Anwendung

Für die aktuelle Anwendung im Unterordner `flutter/` gelten [Flutter-Setup, Buildbefehle und Prüfgrenzen](./flutter.md). Flutter 3.47.6 mit gelockten Abhängigkeiten ist die gemeinsame Werkzeugkette. Android, Apple, Web und Desktop benötigen jeweils die dort genannten Plattformwerkzeuge.

## Erhaltener Kotlin-Ausgangsstand

1. **Voraussetzungen**: Android Studio mit Unterstützung für AGP 8.13.2, JDK 17, Android-SDK 36 und Build Tools 35.0.0. Der vollständige versionierte Wrapper verwendet Gradle 8.14.5 mit Distributionsprüfsumme; ein global installiertes Gradle ist nicht erforderlich. Kotlin 2.3.21 und das passende Compose-Compilerplugin werden über den Versionskatalog aufgelöst.
2. **Klonen**: Das Repository klonen.
3. **Öffnen**: Das Projekt in Android Studio öffnen und den initialen Gradle-Sync abwarten.
4. **Ausführen**: App auf Emulator (API 26+) oder echtem Gerät starten.
5. **Kompilieren**: Schnell Kotlin-Code prüfen mit `./gradlew compileDebugKotlin`.
6. **Bauen**: `./gradlew assembleDebug` oder `./gradlew build`.

## Flutter-Migrationsstand vom 07.10.2026

Die plattformübergreifende Anwendung liegt unter `flutter/`; der bisherige Kotlin-/Compose-Quellstand unter `app/` bleibt eine Verhaltensreferenz. Aktuelle Schichten, Funktionsvergleich und Plattformgrenzen stehen in der [Flutter-Architektur](../architektur/flutter-migration.md), Werkzeugketten und Releasepfade unter [Flutter-Entwicklung](../entwicklung/flutter.md). Die übrigen Kotlin-Dateipfade auf dieser Seite beschreiben den erhaltenen Ausgangsstand.

## Verwandte Seiten

- [Lokale Entwicklung](./lokale-entwicklung.md)
- [Build](./build.md)
- [Tests](./tests.md)
