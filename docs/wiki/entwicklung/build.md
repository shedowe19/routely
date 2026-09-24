# Entwicklung: Build

## Zweck

Dokumentiert den Build-Prozess und Deployment (CI/CD).

## Gradle Tasks

- `./gradlew assembleDebug` - Debug-Build erstellen
- `./gradlew assembleRelease` - Release-Build erstellen
- `./gradlew compileDebugKotlin` - Kotlin-Code kompilieren ohne vollen Build
- `./gradlew build` - Vollständiger Build

## Build-Konfiguration

- **compileSdk**: 34
- **minSdk**: 26
- **targetSdk**: 34
- **Java/Kotlin**: JDK 17, JVM Target 17
- **Zeilenenden**: `.gitattributes` erzwingt LF für `gradlew` und zentrale Projekttextdateien, damit der Unix-Wrapper in Linux-basierten CI-Umgebungen ausführbar bleibt.

## GitHub Actions CI

`.github/workflows/android-ci.yml` prüft Pull Requests und Pushes auf `main`. Mit JDK 17 läuft `./gradlew --no-daemon testDebugUnitTest assembleDebug`. Der Debug-Build wird dabei nicht signiert oder als Release veröffentlicht.

## GitHub Actions Release

Der manuell gestartete Release- und Deployment-Prozess ist über GitHub Actions automatisiert (`.github/workflows/android.yml`).

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
