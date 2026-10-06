# Konfiguration: Config-Dateien

## Zweck

Wichtige Build- und Config-Dateien des Projekts.

## Dateien

- `app/build.gradle.kts`: Zentrale Build-Datei für das App-Modul (compileSdk 36, targetSdk 34, minSdk 26 und Abhängigkeiten).
- `gradle/libs.versions.toml`: Versionskatalog und Dependency-Aliasse; `libs.androidx.fragment` verlangt explizit Fragment `1.7.1` für die ActivityResult-/Release-Lint-Kompatibilität.
- `settings.gradle.kts` / `build.gradle.kts`: Root-Konfiguration; Versionskatalog setzt AGP 8.9.1.
- `gradle/wrapper/gradle-wrapper.properties`: Versionierter Wrapper für Gradle 8.11.1.
- `app/src/main/AndroidManifest.xml`: Tracking-/Erkennungsservices, Standort-FGS, `POST_PROMOTED_NOTIFICATIONS` für die systemabhängige Live-Update-Anfrage sowie `WAKE_LOCK` für die aktive CPU-Haltung und `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` für die ausdrückliche Systemfreigabeanfrage aus den Einstellungen. Die Deklaration gewährt keine Akku-Ausnahme automatisch.
- `gradle.properties`: Compiler- und Kotlin-Flags.
- `.gitattributes`: Normalisiert Projekttextdateien auf LF, erzwingt LF für `gradlew`, CRLF für Batch-Dateien und behandelt PNGs als Binärdateien.
- `.gitignore`: Schließt lokale Build-Artefakte, IDE-Dateien, `.env`-Dateien und lokale Signing-Dateien aus.
- `.github/workflows/android.yml`: Manueller Release-Workflow für signierte APKs.
- `.github/workflows/api-compatibility.yml`: Unit-, Debug- und Release-Prüfung bei `main`-Pushes, Pull Requests und manuellem Start; lädt APKs und Prüfberichte als Artefakte hoch.

## Verwandte Seiten

- [Build](../entwicklung/build.md)
- [Secrets und Sicherheit](./secrets-und-sicherheit.md)
