# Entwicklung: Build

## Zweck

Dokumentiert den Build-Prozess und Deployment (CI/CD).

## Gradle Tasks

- `./gradlew assembleDebug` - Debug-Build erstellen
- `./gradlew assembleRelease` - Release-Build erstellen
- `./gradlew compileDebugKotlin` - Kotlin-Code kompilieren ohne vollen Build
- `./gradlew build` - Vollständiger Build
- `./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace` - API-Regressionstests und Debug-APK gemeinsam prüfen

## Build-Konfiguration

- **compileSdk**: 34
- **minSdk**: 26
- **targetSdk**: 34
- **Java/Kotlin**: JDK 17, JVM Target 17
- **Zeilenenden**: `.gitattributes` erzwingt LF für `gradlew` und zentrale Projekttextdateien, damit der Unix-Wrapper in Linux-basierten CI-Umgebungen ausführbar bleibt.

## GitHub Actions CI/CD (Deployment)

Der Prüfworkflow `.github/workflows/api-compatibility.yml` läuft bei Pull Requests, Pushes auf `codex/traewelling-api-2026` und manuell. Er führt Android-Unit-Tests und `assembleDebug` mit JDK 17, Android-SDK 34 und Build Tools 34.0.0 aus. Er benötigt keine Signierungssecrets und erstellt kein Release. Testberichte werden als Workflow-Artefakt gespeichert; den aktuellen Prüfstand beschreibt [Tests](./tests.md).

Der Release- und Deployment-Prozess ist über GitHub Actions automatisiert (`.github/workflows/android.yml`).

- **Trigger**: Manueller Start (`workflow_dispatch`), bei dem `version_name` (z.B. `1.0.0`) und `version_code` (z.B. `1`) angegeben werden.
- **Build**: Es wird `./gradlew assembleRelease` ausgeführt.
- **Signierung**: Die generierte APK wird mithilfe von `r0adkll/sign-android-release` unter Verwendung von GitHub Secrets (`SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) signiert.
- **APK-Dateiname**: Das signierte Release-Artefakt wird als `routely-v<version_name>.apk` veröffentlicht.
- **Changelog**: Es wird automatisch ein Changelog aus der Git-Historie (Commits seit dem letzten Tag) generiert.
- **Release**: Erstellt ein GitHub Release (`softprops/action-gh-release`) mit dem generierten Changelog als Body und lädt die signierte APK hoch.
- **Artifact**: Die fertige APK wird zudem als Workflow-Artifact (`actions/upload-artifact`) bereitgestellt.

## Verwandte Seiten

- [Setup](./setup.md)
- [Config-Dateien](../konfiguration/config-dateien.md)
- [Tests](./tests.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
