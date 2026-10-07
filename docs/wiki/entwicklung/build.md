# Entwicklung: Build

## Zweck

Dokumentiert den Build-Prozess und Deployment (CI/CD).

## Aktuelle Flutter-Builds

Die gemeinsame App wird im Unterordner `flutter/` mit Flutter 3.47.6 gebaut. [Flutter-Entwicklung](./flutter.md) enthält die Plattformbefehle und den aktuellen Prüfstand. Die folgenden rootbezogenen Gradleaufgaben und Katalogversionen gelten für die erhaltene Kotlin-Referenz unter `app/`.

## Gradle Tasks der Kotlin-Referenz

- `./gradlew assembleDebug` - Debug-Build erstellen
- `./gradlew assembleRelease` - Release-Build erstellen
- `./gradlew compileDebugKotlin` - Kotlin-Code kompilieren ohne vollen Build
- `./gradlew build` - Vollständiger Build
- `./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace` - API-Regressionstests und Debug-APK gemeinsam prüfen
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --stacktrace` - Unit-Tests, vollständiges Debug-Lint sowie Debug- und Release-Build einschließlich Release-Lint prüfen

## Build-Konfiguration

- **compileSdk**: 36
- **Android Gradle Plugin / Wrapper**: AGP 8.13.2 / Gradle 8.14.5
- **Build Tools**: 35.0.0
- **Kotlin / Compose-Compilerplugin / AndroidX Core**: 2.3.21 / 2.3.21 / 1.18.0
- **KSP / Compose BOM**: 2.3.12 / 2026.06.01
- **minSdk**: 26
- **targetSdk**: 34
- **Java/Kotlin**: JDK 17, JVM Target 17
- **Zeilenenden**: `.gitattributes` erzwingt LF für `gradlew` und zentrale Projekttextdateien, damit der Unix-Wrapper in Linux-basierten CI-Umgebungen ausführbar bleibt.

## Gezielte Bibliotheksaktualisierung vom 06.10.2026

Der Versionskatalog enthält einen gemeinsam gewählten SDK-36-Stand statt ungeprüft aller höchsten Releases. AGP 8.13.2 mit gebündeltem R8 8.13.19 und Gradle 8.14.5 stützt Kotlin 2.3.21. Neuere Kotlin-2.4-/AndroidX-Releases wurden zurückgestellt, wenn deren R8- beziehungsweise AAR-Metadaten eine neuere Toolchain, API 37 oder AGP 9.1 verlangen. compileSdk 36, targetSdk 34, minSdk 26 und JVM 17 bleiben erhalten. Die vollständigen gewählten Pins und Gründe stehen unter [Externe Abhängigkeiten](../architektur/externe-abhaengigkeiten.md).

Die tatsächliche Android-Variantenauflösung zeigte bei OkHttp 5.5.0 eine AAR-Mindestanforderung von compileSdk 37. Die App verwendet deshalb OkHttp 5.4.0, dessen Android-AAR API 36 erlaubt. JVM-POMs und allgemeine Java-/Kotlin-Kompatibilität ersetzen diesen Android-Metadatencheck nicht.

Kotlin 2 verwendet `org.jetbrains.kotlin.plugin.compose` in derselben Version wie Kotlin. Die bisherige `composeOptions.kotlinCompilerExtensionVersion = "1.5.11"` entfällt; JVM 17 wird über `kotlin.compilerOptions` mit `JvmTarget.JVM_17` gesetzt. KSP bleibt für Room-Codegenerierung eingebunden. Feed und Meldungen verwenden das aktuelle Material-3-`PullToRefreshBox`, Settings beziehen `LocalLifecycleOwner` aus Lifecycle Compose. Diese API-Anpassungen gehören zur Migration, nicht zu einer neuen Navigations- oder Speicherarchitektur.

Coil wechselt auf 3.4.0 mit `coil3`-Imports und `SingletonImageLoader.Factory`. Netzwerkunterstützung wird ausdrücklich über `coil-network-okhttp` ergänzt, HTTP-Cache-Control über `coil-network-cache-control` und `CacheControlCacheStrategy`. Der Bildclient behält den User-Agent. Mehr zum Quellwechsel: [Coil-3-Migration](https://coil-kt.github.io/coil/upgrading_to_coil3/) und [Netzwerkunterstützung](https://coil-kt.github.io/coil/network/).

Der vollständige offizielle Gradle-8.14.5-Wrapper einschließlich `gradle-wrapper.jar` ist versioniert. `distributionSha256Sum` prüft die Distribution gegen den offiziellen SHA-256; Wrapper-JAR und Distribution wurden beim Aktualisieren mit den [Gradle-Prüfsummen](https://gradle.org/release-checksums/) abgeglichen. Ein installierbares Wrapperpaket oder auflösbare Metadaten allein ist noch kein erfolgreicher App-Build. Automatisierte Ergebnisse bleiben unter [Tests](./tests.md) an den tatsächlich geprüften Commit gebunden.

Toolchain-Grundlagen: [AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [Android-Kotlin-/R8-Kompatibilität](https://developer.android.com/build/kotlin-support), [Kotlin-Gradle-Konfiguration](https://kotlinlang.org/docs/gradle-configure-project.html) und [KSP-Releases](https://github.com/google/ksp/releases).

## GitHub Actions CI/CD (Deployment)

Der Prüfworkflow `.github/workflows/api-compatibility.yml` läuft bei Pushes auf `main`, Pull Requests und manuell. Er führt zuerst die Offline-Releaseguard-Tests und danach Android-Unit-Tests, vollständiges `lintDebug`, `assembleDebug` und `assembleRelease` mit JDK 17, Android-SDK 36 und Build Tools 35.0.0 aus. Damit werden vollständiges Debug-Lint und zusätzlich Release-Lint geprüft. Er benötigt keine Signierungssecrets und erstellt kein GitHub Release. Debug-APK, unsignierte Release-APK und Prüfberichte werden als Workflow-Artefakte bereitgestellt; Namen und Prüfstand beschreibt [Tests](./tests.md).

Der Release- und Deployment-Prozess ist über GitHub Actions automatisiert (`.github/workflows/android.yml`).

- **Trigger**: Manueller Start (`workflow_dispatch`), bei dem `version_name` (z.B. `1.0.0`) und optional `version_code` angegeben werden. Leer bedeutet veröffentlichter Höchstwert + 1.
- **Eingabeprüfung**: `version_name` beginnt mit einem Buchstaben oder einer Ziffer, ist höchstens 64 Zeichen lang und enthält ausschließlich Buchstaben, Ziffern, Punkt, Unterstrich oder Bindestrich. `version_code` ist eine ganze Zahl von 1 bis 2.100.000.000 ohne führende Null. Ein expliziter Code muss zusätzlich über dem veröffentlichten Höchstwert liegen; ungültige Eingaben und vorhandene Versionsbezeichnungen brechen vor dem Build ab.
- **Build**: Der aktuelle manuelle Workflow baut die Flutter-App unter `flutter/`. `flutter analyze --fatal-infos` und `flutter test` laufen vor `flutter build apk --release --no-pub --build-name "$RELEASE_VERSION_NAME" --build-number "$RELEASE_VERSION_CODE"`. Anschließend prüfen `android/gradlew :app:testDebugUnitTest :app:lintDebug` die nativen Verträge und Debug-Lint vor der Signierung. Workflow-Eingaben werden als validierte Jobvariablen mit gequoteten Argumenten übernommen, nicht direkt in Shellcode eingefügt. Die Gradle-Befehle für `:app:assembleRelease` oben beziehen sich auf den erhaltenen Kotlin-Ausgangsstand.
- **Signierung**: Die generierte APK wird mithilfe von `r0adkll/sign-android-release` unter Verwendung von GitHub Secrets (`SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) signiert. `apksigner verify` prüft die erzeugte Signatur vor Umbenennung und Release-Reservierung.
- **APK-Dateiname**: Das signierte Release-Artefakt wird als `routely-v<version_name>.apk` veröffentlicht.
- **APK-Buildpfad**: Die Flutter-APK stammt aus `flutter/build/app/outputs/flutter-apk/`; Signierung und Manifestprüfung verwenden genau dieses Artefakt.
- **Changelog**: Es wird automatisch ein Changelog aus der Git-Historie (Commits seit dem letzten Tag) generiert.
- **Release**: Der Python-Standardbibliothek-Helfer `.github/scripts/release_guard.py` reserviert einen neuen Tag am exakten Dispatch-Commit und einen neuen Draft. Create-only Uploads betreffen ausschließlich dessen Release-ID. Erst nach Prüfung von Tag, APK-Manifest, Größe, SHA-256-Digest und Versionsmetadaten wird der Draft veröffentlicht. Parallel laufende manuelle Releases sind serialisiert. Details und Wiederanlaufgrenzen stehen unter [Deployment](./deployment.md).
- **Artifact**: APK und `release-version.json` werden zudem als Workflow-Artifact (`actions/upload-artifact`) bereitgestellt.

## Framework-Fortschrittsanzeige

compileSdk 36 wird für den typisierten Zugriff auf `Notification.ProgressStyle` benötigt. Die Verwendung bleibt zur Laufzeit auf API 36+ beschränkt. Die Promotion-Anfrage verwendet den offiziellen Bundle-Wert aus API 36.1; targetSdk 34 und minSdk 26 bleiben unverändert. Der ursprüngliche Fortschrittsstand verwendete AGP 8.9.1 / Gradle 8.11.1; die getrennte Bibliotheksaktualisierung nutzt nun den oben dokumentierten Stand. Beide Workflows installieren `platforms;android-36` und `build-tools;35.0.0`. Der Signierschritt des manuellen Workflows verwendet ebenfalls 35.0.0.

Ein erfolgreicher Build prüft die API-Grenzen, garantiert aber keine Live-Update-Hervorhebung durch Android oder Samsung. Details und Geräteprüfungen stehen unter [Reisefortschritt](../module/trip-progress.md).

## Release-Lint: Fragment und ActivityResult

Der manuelle Build für Version `1.7.0` / Version-Code `12` scheiterte mit `InvalidFragmentVersionForActivityResult`: Die neue Standortfreigabe verwendet `registerForActivityResult`, während eine transitive Fragment-Abhängigkeit unter 1.3.0 vorhanden war. Die POMs von `play-services-base` / `play-services-basement` aus Play Services 21.2.0 fordern Fragment 1.0.0 beziehungsweise 1.1.0 an; diese Anforderungen allein sind kein Nachweis der letztlich von Gradle ausgewählten Version. Nachweis: [fehlgeschlagener Release-Lauf 37350908159](https://github.com/shedowe19/routely/actions/runs/37350908159).

Die ursprüngliche Korrektur verlangte explizit `libs.androidx.fragment` mit Version `1.7.1`, bereits in der damaligen SDK-34-Konfiguration. Der aktuelle Versionskatalog verlangt `1.9.1`; die direkte Abhängigkeit und die Mindestanforderung 1.3.0 bleiben damit berücksichtigt. Die Lint-Prüfung bleibt aktiv, und der Prüfworkflow baut auch die Release-Variante.

Ein erfolgreicher vorheriger Debug-Build ist kein Nachweis für Release-Lint oder Signierung. Den Prüfstatus des jeweiligen Commits liefert [API Compatibility auf GitHub Actions](https://github.com/shedowe19/routely/actions/workflows/api-compatibility.yml); der manuelle signierte Release-Lauf wird separat ausgeführt.

## Flutter-Migrationsstand vom 07.10.2026

Die plattformübergreifende Anwendung liegt unter `flutter/`; der bisherige Kotlin-/Compose-Quellstand unter `app/` bleibt eine Verhaltensreferenz. Aktuelle Schichten, Funktionsvergleich und Plattformgrenzen stehen in der [Flutter-Architektur](../architektur/flutter-migration.md), Werkzeugketten und Releasepfade unter [Flutter-Entwicklung](../entwicklung/flutter.md). Die übrigen Kotlin-Dateipfade auf dieser Seite beschreiben den erhaltenen Ausgangsstand.

Der Android-Teil der Flutter-App verwendet den vollständig versionierten offiziellen Gradle-9.3.1-Wrapper mit passenden Startskripten und JAR. `distributionSha256Sum` prüft die `gradle-9.3.1-all.zip`-Distribution gegen `17f277867f6914d61b1aa02efab1ba7bb439ad652ca485cd8ca6842fccec6e43`; der Wrapper-JAR wurde gegen `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13` aus den [offiziellen Gradle-Prüfsummen](https://gradle.org/release-checksums/) verifiziert. Die Flutter-Template-Ignore-Regeln für die Wrapper-Dateien sind entfernt, damit ein frischer Checkout denselben Wrapper enthält. Der plattformübergreifende Workflow läuft für `main`-Pushes, Pull Requests und manuell; ein Migrationbranch-Push startet keinen zusätzlichen Doppelbuild.

## Verwandte Seiten

- [Setup](./setup.md)
- [Config-Dateien](../konfiguration/config-dateien.md)
- [Tests](./tests.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
