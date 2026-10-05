# Entwicklung: Build

## Zweck

Dokumentiert den Build-Prozess und Deployment (CI/CD).

## Gradle Tasks

- `./gradlew assembleDebug` - Debug-Build erstellen
- `./gradlew assembleRelease` - Release-Build erstellen
- `./gradlew compileDebugKotlin` - Kotlin-Code kompilieren ohne vollen Build
- `./gradlew build` - Vollständiger Build
- `./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace` - API-Regressionstests und Debug-APK gemeinsam prüfen
- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --stacktrace` - Unit-Tests sowie Debug- und Release-Build einschließlich Release-Lint prüfen

## Build-Konfiguration

- **compileSdk**: 34
- **minSdk**: 26
- **targetSdk**: 34
- **Java/Kotlin**: JDK 17, JVM Target 17
- **Zeilenenden**: `.gitattributes` erzwingt LF für `gradlew` und zentrale Projekttextdateien, damit der Unix-Wrapper in Linux-basierten CI-Umgebungen ausführbar bleibt.

## GitHub Actions CI/CD (Deployment)

Der Prüfworkflow `.github/workflows/api-compatibility.yml` läuft bei Pushes auf `main`, Pull Requests und manuell. Er führt Android-Unit-Tests, `assembleDebug` und `assembleRelease` mit JDK 17, Android-SDK 34 und Build Tools 34.0.0 aus. Damit wird auch Release-Lint geprüft. Er benötigt keine Signierungssecrets und erstellt kein GitHub Release. Debug-APK, unsignierte Release-APK und Prüfberichte werden als Workflow-Artefakte bereitgestellt; Namen und Prüfstand beschreibt [Tests](./tests.md).

Der Release- und Deployment-Prozess ist über GitHub Actions automatisiert (`.github/workflows/android.yml`).

- **Trigger**: Manueller Start (`workflow_dispatch`), bei dem `version_name` (z.B. `1.0.0`) und `version_code` (z.B. `1`) angegeben werden.
- **Build**: Es wird `./gradlew assembleRelease` ausgeführt.
- **Signierung**: Die generierte APK wird mithilfe von `r0adkll/sign-android-release` unter Verwendung von GitHub Secrets (`SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) signiert.
- **APK-Dateiname**: Das signierte Release-Artefakt wird als `routely-v<version_name>.apk` veröffentlicht.
- **Changelog**: Es wird automatisch ein Changelog aus der Git-Historie (Commits seit dem letzten Tag) generiert.
- **Release**: Erstellt ein GitHub Release (`softprops/action-gh-release`) mit dem generierten Changelog als Body und lädt die signierte APK hoch.
- **Artifact**: Die fertige APK wird zudem als Workflow-Artifact (`actions/upload-artifact`) bereitgestellt.

## Release-Lint: Fragment und ActivityResult

Der manuelle Build für Version `1.7.0` / Version-Code `12` scheiterte mit `InvalidFragmentVersionForActivityResult`: Die neue Standortfreigabe verwendet `registerForActivityResult`, während eine transitive Fragment-Abhängigkeit unter 1.3.0 vorhanden war. Die POMs von `play-services-base` / `play-services-basement` aus Play Services 21.2.0 fordern Fragment 1.0.0 beziehungsweise 1.1.0 an; diese Anforderungen allein sind kein Nachweis der letztlich von Gradle ausgewählten Version. Nachweis: [fehlgeschlagener Release-Lauf 37350908159](https://github.com/shedowe19/routely/actions/runs/37350908159).

`app/build.gradle.kts` verlangt nun explizit `libs.androidx.fragment`; der Versionskatalog setzt `androidx.fragment:fragment` auf `1.7.1`, passend zur SDK-34-Konfiguration. Dadurch wird eine Fragment-Version oberhalb der für diese ActivityResult-Verwendung erforderlichen Mindestversion 1.3.0 angefordert. Die Lint-Prüfung bleibt aktiv; der Prüfworkflow baut künftig auch die Release-Variante.

Ein erfolgreicher vorheriger Debug-Build ist kein Nachweis für Release-Lint oder Signierung. Den Prüfstatus des jeweiligen Commits liefert [API Compatibility auf GitHub Actions](https://github.com/shedowe19/routely/actions/workflows/api-compatibility.yml); der manuelle signierte Release-Lauf wird separat ausgeführt.

## Verwandte Seiten

- [Setup](./setup.md)
- [Config-Dateien](../konfiguration/config-dateien.md)
- [Tests](./tests.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
