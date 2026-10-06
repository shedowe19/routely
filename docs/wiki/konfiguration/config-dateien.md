# Konfiguration: Config-Dateien

## Zweck

Wichtige Build- und Config-Dateien des Projekts.

## Dateien

- `app/build.gradle.kts`: Zentrale Build-Datei für das App-Modul (compileSdk 36, targetSdk 34, minSdk 26 und Abhängigkeiten).
- `gradle/libs.versions.toml`: Versionskatalog und Dependency-Aliasse; aktueller SDK-36-Stand mit Kotlin-/Compose-Compilerplugin 2.3.21, KSP 2.3.12 und explizitem Fragment 1.9.1. Location und die getrennten Coil-3-Netzwerk-/Cache-Control-Module sind ebenfalls versionierte Aliasse.
- `settings.gradle.kts` / `build.gradle.kts`: Root-Konfiguration; Versionskatalog setzt AGP 8.13.2 und das Kotlin-Compose-Compilerplugin.
- `gradle/wrapper/gradle-wrapper.properties`: Vollständiger Wrapper für Gradle 8.14.5 mit `distributionSha256Sum`; `gradle/wrapper/gradle-wrapper.jar` ist versioniert.
- `app/src/main/AndroidManifest.xml`: Tracking-/Erkennungsservices, Standort-FGS, `POST_PROMOTED_NOTIFICATIONS` für die systemabhängige Live-Update-Anfrage sowie `WAKE_LOCK` für die aktive CPU-Haltung und `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` für die ausdrückliche Systemfreigabeanfrage aus den Einstellungen. Die Deklaration gewährt keine Akku-Ausnahme automatisch.
- `gradle.properties`: Compiler- und Kotlin-Flags.
- `.gitattributes`: Normalisiert Projekttextdateien auf LF, erzwingt LF für `gradlew`, CRLF für Batch-Dateien und behandelt PNGs als Binärdateien.
- `.gitignore`: Schließt lokale Build-Artefakte, IDE-Dateien, `.env`-Dateien und lokale Signing-Dateien aus.
- `.github/workflows/android.yml`: Manueller Release-Workflow für signierte APKs.
- `.github/workflows/api-compatibility.yml`: Unit-, Debug- und Release-Prüfung bei `main`-Pushes, Pull Requests und manuellem Start; lädt APKs und Prüfberichte als Artefakte hoch.

## Verwandte Seiten

- [Build](../entwicklung/build.md)
- [Secrets und Sicherheit](./secrets-und-sicherheit.md)
