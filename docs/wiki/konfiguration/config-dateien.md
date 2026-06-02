# Konfiguration: Config-Dateien

## Zweck

Wichtige Build- und Config-Dateien des Projekts.

## Dateien

- `app/build.gradle.kts`: Zentrale Build-Datei für das App-Modul (Dependencies, SDK Versionen).
- `app/src/main/AndroidManifest.xml`: Deklariert Berechtigungen, Activities, Services und Receiver; `TripTrackingService` nutzt `foregroundServiceType="dataSync|location"`.
- `settings.gradle.kts` / `build.gradle.kts`: Root-Konfiguration.
- `gradle.properties`: Compiler- und Kotlin-Flags.
- `.gitattributes`: Normalisiert Projekttextdateien auf LF, erzwingt LF für `gradlew`, CRLF für Batch-Dateien und behandelt PNGs als Binärdateien.
- `.gitignore`: Schließt lokale Build-Artefakte, IDE-Dateien, `.env`-Dateien und lokale Signing-Dateien aus.
- `.github/workflows/android.yml`: Manueller Release-Workflow für signierte APKs.

## Verwandte Seiten

- [Build](../entwicklung/build.md)
- [Secrets und Sicherheit](./secrets-und-sicherheit.md)
- [TripTracking](../module/trip-tracking.md)
