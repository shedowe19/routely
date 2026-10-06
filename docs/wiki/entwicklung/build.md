# Entwicklung: Build

## Zweck

Dokumentiert den Build-Prozess und Deployment (CI/CD).

## Gradle Tasks

- `./gradlew assembleDebug` - Debug-Build erstellen
- `./gradlew assembleRelease` - Release-Build erstellen
- `./gradlew compileDebugKotlin` - Kotlin-Code kompilieren ohne vollen Build
- `./gradlew build` - Vollständiger Build
- `./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace` - API-Regressionstests und Debug-APK gemeinsam prüfen
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --stacktrace` - Unit-Tests, vollständiges Debug-Lint sowie Debug- und Release-Build einschließlich Release-Lint prüfen

## Build-Konfiguration

- **compileSdk**: 36
- **Android Gradle Plugin / Wrapper**: AGP 8.9.1 / Gradle 8.11.1
- **Build Tools**: 35.0.0
- **Kotlin / AndroidX Core**: 1.9.23 / 1.13.0
- **minSdk**: 26
- **targetSdk**: 34
- **Java/Kotlin**: JDK 17, JVM Target 17
- **Zeilenenden**: `.gitattributes` erzwingt LF für `gradlew` und zentrale Projekttextdateien, damit der Unix-Wrapper in Linux-basierten CI-Umgebungen ausführbar bleibt.

## GitHub Actions CI/CD (Deployment)

Der Prüfworkflow `.github/workflows/api-compatibility.yml` läuft bei Pushes auf `main`, Pull Requests und manuell. Er führt Android-Unit-Tests, vollständiges `lintDebug`, `assembleDebug` und `assembleRelease` mit JDK 17, Android-SDK 36 und Build Tools 35.0.0 aus. Damit werden vollständiges Debug-Lint und zusätzlich Release-Lint geprüft. Er benötigt keine Signierungssecrets und erstellt kein GitHub Release. Debug-APK, unsignierte Release-APK und Prüfberichte werden als Workflow-Artefakte bereitgestellt; Namen und Prüfstand beschreibt [Tests](./tests.md).

Der Release- und Deployment-Prozess ist über GitHub Actions automatisiert (`.github/workflows/android.yml`).

- **Trigger**: Manueller Start (`workflow_dispatch`), bei dem `version_name` (z.B. `1.0.0`) und `version_code` (z.B. `1`) angegeben werden.
- **Eingabeprüfung**: `version_name` beginnt mit einem Buchstaben oder einer Ziffer, ist höchstens 64 Zeichen lang und enthält ausschließlich Buchstaben, Ziffern, Punkt, Unterstrich oder Bindestrich. `version_code` ist eine ganze Zahl von 1 bis 2.100.000.000 ohne führende Null. Ungültige Eingaben brechen vor dem Build ab.
- **Build**: Unit-Tests laufen vor dem Release-Build: `./gradlew :app:testDebugUnitTest :app:assembleRelease` mit validierten gequoteten Versionsargumenten. Workflow-Eingaben werden über Jobvariablen übernommen, nicht direkt in Shellcode eingefügt.
- **Signierung**: Die generierte APK wird mithilfe von `r0adkll/sign-android-release` unter Verwendung von GitHub Secrets (`SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) signiert.
- **APK-Dateiname**: Das signierte Release-Artefakt wird als `routely-v<version_name>.apk` veröffentlicht.
- **Changelog**: Es wird automatisch ein Changelog aus der Git-Historie (Commits seit dem letzten Tag) generiert.
- **Release**: Erstellt ein GitHub Release (`softprops/action-gh-release`) mit dem generierten Changelog als Body und lädt die signierte APK hoch.
- **Artifact**: Die fertige APK wird zudem als Workflow-Artifact (`actions/upload-artifact`) bereitgestellt.

## Framework-Fortschrittsanzeige

compileSdk 36 wird für den typisierten Zugriff auf `Notification.ProgressStyle` benötigt. Die Verwendung bleibt zur Laufzeit auf API 36+ beschränkt. Die Promotion-Anfrage verwendet den offiziellen Bundle-Wert aus API 36.1; targetSdk 34 und minSdk 26 bleiben unverändert. AGP 8.9.1 und der Gradle-Wrapper 8.11.1 bilden den zugehörigen Buildstand; Compose-/Kotlin-Abhängigkeiten werden hierfür nicht pauschal aktualisiert. Beide Workflows installieren `platforms;android-36` und `build-tools;35.0.0`. Der Signierschritt des manuellen Workflows verwendet ebenfalls 35.0.0.

Ein erfolgreicher Build prüft die API-Grenzen, garantiert aber keine Live-Update-Hervorhebung durch Android oder Samsung. Details und Geräteprüfungen stehen unter [Reisefortschritt](../module/trip-progress.md).

## Release-Lint: Fragment und ActivityResult

Der manuelle Build für Version `1.7.0` / Version-Code `12` scheiterte mit `InvalidFragmentVersionForActivityResult`: Die neue Standortfreigabe verwendet `registerForActivityResult`, während eine transitive Fragment-Abhängigkeit unter 1.3.0 vorhanden war. Die POMs von `play-services-base` / `play-services-basement` aus Play Services 21.2.0 fordern Fragment 1.0.0 beziehungsweise 1.1.0 an; diese Anforderungen allein sind kein Nachweis der letztlich von Gradle ausgewählten Version. Nachweis: [fehlgeschlagener Release-Lauf 37350908159](https://github.com/shedowe19/routely/actions/runs/37350908159).

`app/build.gradle.kts` verlangt nun explizit `libs.androidx.fragment`; der Versionskatalog setzt `androidx.fragment:fragment` auf `1.7.1`, bereits in der damaligen SDK-34-Konfiguration. Dadurch wird eine Fragment-Version oberhalb der für diese ActivityResult-Verwendung erforderlichen Mindestversion 1.3.0 angefordert. Die Lint-Prüfung bleibt aktiv; der Prüfworkflow baut künftig auch die Release-Variante.

Ein erfolgreicher vorheriger Debug-Build ist kein Nachweis für Release-Lint oder Signierung. Den Prüfstatus des jeweiligen Commits liefert [API Compatibility auf GitHub Actions](https://github.com/shedowe19/routely/actions/workflows/api-compatibility.yml); der manuelle signierte Release-Lauf wird separat ausgeführt.

## Verwandte Seiten

- [Setup](./setup.md)
- [Config-Dateien](../konfiguration/config-dateien.md)
- [Tests](./tests.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
