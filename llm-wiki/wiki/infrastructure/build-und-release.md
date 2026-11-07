# Build und Release

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Diese Seite beschreibt, wie die App gebaut, getestet und veröffentlicht wird.
Sie ist die Referenz für CI-Änderungen und Release-Fragen.

## Gradle

- Ein Modul: `:app` (`settings.gradle.kts`).
- Root-Plugins: Android Application, Kotlin Android, Kotlin Compose, KSP.
- Versionen zentral in `gradle/libs.versions.toml`.
- Java/Kotlin-Ziel: JVM 17 (`app/build.gradle.kts:42-63`).
- Android: `compileSdk 36`, `minSdk 26`, `targetSdk 34`.
- `versionCode` und `versionName` kommen aus Gradle-Properties und fallen auf
  `1` bzw. `1.0.0` zurück (`app/build.gradle.kts:18-19`).
- Release: `isMinifyEnabled = false`, `isShrinkResources = false`; ProGuard-
  Regeln sind vorhanden, werden aber nicht zum Verkleinern genutzt.
- Debug hat das Suffix `.debug`.

## Lokale Befehle

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Voraussetzungen: JDK 17, Android SDK 36, Build-Tools 35.0.0.

## CI-Workflows

### API Compatibility

`.github/workflows/api-compatibility.yml`

- Auslöser: Push auf `main`, Pull Requests, manuell.
- Führt zuerst die Python-Tests der Hilfsskripte aus.
- Danach Unit-Tests, vollständiges Debug-Lint und beide APK-Builds.
- Lädt Debug-APK, unsignierte Release-APK, Lint-Berichte und JUnit-Ergebnisse
  als Artefakte hoch.
- Keine Netzwerkaufrufe gegen die echte API.

### Android Build, Sign & Release

`.github/workflows/android.yml`

- Nur manuell (`workflow_dispatch`) mit `version_name` und optional
  `version_code`.
- Eine globale Concurrency-Gruppe verhindert parallele signierte Releases;
  ein laufendes Release wird nicht abgebrochen.
- Testet zuerst die Release-Guard-Logik offline.
- `release_guard.py preflight` validiert Version und beansprucht dauerhaft
  einen monotonen Version-Code.
- Baut und testet, signiert mit GitHub-Secrets, benennt die APK um, erzeugt
  Release Notes aus Commits seit dem letzten Tag.
- `reserve` prüft Version, APK und reserviert Tag plus Draft; `publish`
  veröffentlicht nur die reservierte Release mit unveränderlichen Assets.
- `target_commitish` ist der validierte Commit, nie ein beweglicher Branch.

### Wiki Sync

`.github/workflows/wiki-sync.yml`

- Beobachtet `docs/wiki/**` und synchronisiert in ein GitHub-Wiki.
- Benennt `index.md` in `Home.md` um und ersetzt `index.md` in allen
  Markdown-Dateien.
- **Dieser Pfad existiert im Arbeitsbaum nicht mehr**; siehe
  [Dokumentationspfade](../contradictions/2026-11-04-dokumentationspfade.md).

## Release-Guard

`.github/scripts/release_guard.py` (Python-Standardbibliothek):

- Version Name: 1–64 Zeichen aus Buchstaben, Ziffern, Punkt, Unterstrich,
  Bindestrich; keine `..`, kein abschließender Punkt, kein `.lock`.
- Version Code: positive Ganzzahl bis 2.100.000.000.
- Repository-Identität und Commit-SHA werden streng validiert.
- GitHub-Origins sind auf `api.github.com`, `uploads.github.com` und die
  Release-Asset-Hosts beschränkt; Redirects sind deaktiviert.
- Assets werden create-only hochgeladen; vorhandene Assets werden nicht
  überschrieben.
- `.github/release-version-floor.json` hält historische Release-IDs und den
  höchsten Legacy-Version-Code fest.

## Signatur

Die CI nutzt GitHub-Secrets für Keystore, Alias und Passwörter. Die Namen
lauten `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`,
`WIKI_TOKEN`. Werte stehen nicht im Repository und nicht in diesem Wiki.

## Risiken

- `targetSdk 34` bei `compileSdk 36`: Plattformverhalten neuerer Android-
  Versionen wird nicht automatisch erzwungen.
- Der Release-Workflow ist manuell; ein vergessener Lauf erzeugt keinen Build.
- Der Wiki-Sync bearbeitet Dateien im Checkout und ist an einen Pfad gebunden,
  der nicht mehr existiert.
- Signierte Releases sind irreversibel veröffentlicht; der Guard schützt die
  Reihenfolge, nicht die inhaltliche Qualität.

## Verwandte Seiten

- [Teststrategie](../testing/teststrategie.md)
- [Überblick](../overview.md)
- [Aktueller Projektstand](../current-project-state.md)
- [Index](../index.md)
