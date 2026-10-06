# Entwicklung: Deployment

## Zweck

Dokumentiert den Deployment-Prozess.

## GitHub Actions

- `.github/workflows/android.yml` baut Releases manuell per `workflow_dispatch`.
- Eingaben sind `version_name` und `version_code`.
- Der Workflow validiert beide Versionseingaben, installiert Android-SDK 36 und Build Tools 35.0.0, führt `:app:testDebugUnitTest` vor `:app:assembleRelease` mit gequoteten Versionsargumenten aus, signiert erst danach die APK mit GitHub Secrets und erstellt anschließend ein GitHub Release. Der Signierschritt verlangt ebenfalls Build Tools 35.0.0. Zulässige Eingaben stehen unter [Build](./build.md).
- Das veröffentlichte APK-Artefakt heißt `routely-v<version_name>.apk`.
- Zusätzlich wird das APK als Workflow-Artifact `release-apk` hochgeladen.

## Prüfung vor einem Release

`.github/workflows/api-compatibility.yml` prüft bei Pushes auf `main`, Pull Requests und manuellem Start Unit-Tests, vollständiges `lintDebug`, Debug-Build und Release-Build einschließlich Release-Lint. Nach erfolgreichem Build stellt er eine unsignierte Release-APK als `routely-release-unsigned-apk` bereit; Signierung und Veröffentlichung erfolgen weiterhin über den manuellen Workflow `android.yml`.

Der fehlgeschlagene Release-Lauf für `1.7.0` / Version-Code `12` führte zur expliziten Fragment-Abhängigkeit und zur zusätzlichen Release-Prüfung. Hintergrund und aktueller Nachweis stehen unter [Build](./build.md) und [Tests](./tests.md).

## Offene Punkte

- TODO: Vor dem nächsten Release bestehende Tags/Releases ablehnen, höhere Versionscodes verbindlich prüfen, parallele Veröffentlichungen serialisieren und neue Tags mit `target_commitish: ${{ github.sha }}` an den gebauten Commit binden. Der [Main-Review](./main-review-2026-10-06.md), R1–R3, belegt die derzeit erlaubten fehlerhaften Auslöser; eine solche Veröffentlichung wurde nicht durchgeführt.
- TODO: Play-Store-Release-Prozess dokumentieren, falls ein Store-Deployment vorgesehen ist.

## Verwandte Seiten

- [Build](./build.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Tests](./tests.md)
