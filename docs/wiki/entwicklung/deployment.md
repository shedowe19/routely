# Entwicklung: Deployment

## Zweck

Dokumentiert den Deployment-Prozess.

## GitHub Actions

- `.github/workflows/android.yml` baut Releases manuell per `workflow_dispatch`.
- Eingaben sind `version_name` und `version_code`.
- Der Workflow führt `./gradlew assembleRelease` aus, signiert die APK mit GitHub Secrets und erstellt anschließend ein GitHub Release.
- Das veröffentlichte APK-Artefakt heißt `routely-v<version_name>.apk`.
- Zusätzlich wird das APK als Workflow-Artifact `release-apk` hochgeladen.

## Offene Punkte

- TODO: Play-Store-Release-Prozess dokumentieren, falls ein Store-Deployment vorgesehen ist.

## Verwandte Seiten

- [Build](./build.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
