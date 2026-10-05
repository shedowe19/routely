# Konfiguration: Config-Dateien

## Zweck

Wichtige Build- und Config-Dateien des Projekts.

## Dateien

- `app/build.gradle.kts`: Zentrale Build-Datei für das App-Modul (Dependencies, SDK Versionen).
- `gradle/libs.versions.toml`: Versionskatalog und Dependency-Aliasse; `libs.androidx.fragment` verlangt explizit Fragment `1.7.1` für die ActivityResult-/Release-Lint-Kompatibilität.
- `settings.gradle.kts` / `build.gradle.kts`: Root-Konfiguration.
- `gradle.properties`: Compiler- und Kotlin-Flags.
- `.gitattributes`: Normalisiert Projekttextdateien auf LF, erzwingt LF für `gradlew`, CRLF für Batch-Dateien und behandelt PNGs als Binärdateien.
- `.gitignore`: Schließt lokale Build-Artefakte, IDE-Dateien, `.env`-Dateien und lokale Signing-Dateien aus.
- `.github/workflows/android.yml`: Manueller Release-Workflow für signierte APKs.
- `.github/workflows/api-compatibility.yml`: Unit-, Debug- und Release-Prüfung bei `main`-Pushes, Pull Requests und manuellem Start; lädt APKs und Prüfberichte als Artefakte hoch.

## Verwandte Seiten

- [Build](../entwicklung/build.md)
- [Secrets und Sicherheit](./secrets-und-sicherheit.md)
