# Projektüberblick

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Routely ist eine Android-App für die Träwelling-Community. Sie verbindet die
Träwelling-Webplattform mit einem mobilen Ablauf: Station suchen, Fahrt
auswählen, einchecken, den Reiseverlauf mit Echtzeit- und GPS-Daten begleiten
und Statusmeldungen lesen oder bearbeiten. Der Schwerpunkt liegt auf einer
präzisen Darstellung von Fahrzeiten und einem dichten Live-Erlebnis während der
Fahrt (siehe `README.md:1-42`).

## Für wen

- Reisende, die Träwelling auf Android nutzen und ihre Fahrten live verfolgen
  wollen.
- Entwickler und Agenten, die das Repository ändern und dafür ein
  belastbares Einstiegsmodell brauchen.

## Stack und Laufzeit

| Bereich | Technik | Quelle |
|---|---|---|
| Sprache | Kotlin 2.3.21 | `gradle/libs.versions.toml` |
| Build | Android Gradle Plugin 8.13.2, Gradle Wrapper | `build.gradle.kts`, `gradle/libs.versions.toml` |
| UI | Jetpack Compose, Material 3, Navigation Compose | `app/build.gradle.kts:74-80` |
| Architektur | MVVM, StateFlow, Controller + ViewModel | `app/src/main/kotlin/de/traewelling/app` |
| Netzwerk | Retrofit 3.0.0, OkHttp 5.4.0, Gson 2.14.0 | `gradle/libs.versions.toml`, `app/build.gradle.kts:82-94` |
| Persistenz | Room 2.8.5, DataStore Preferences 1.2.1 | `app/build.gradle.kts:87-98` |
| Bilder | Coil 3.4.0 | `app/build.gradle.kts:88-90` |
| Standort | Google Play Services Location 21.4.0 | `app/build.gradle.kts:94` |
| Android | `compileSdk 36`, `minSdk 26`, `targetSdk 34`, JVM 17 | `app/build.gradle.kts:10-45` |

## Top-Level-Struktur

```
routely/
├── app/                    Android-App (einziger Build-Modul)
│   └── src/
│       ├── main/kotlin/de/traewelling/app/
│       │   ├── data/       API, DBF, Room, Modelle, Repositories, Routing, SEV
│       │   ├── service/    Tracking, GPS-Zeiten, Fahrterkennung, TTS
│       │   ├── ui/         Compose-Screens, Komponenten, Navigation, Theme
│       │   ├── viewmodel/  Feature-ViewModels und reine Controller
│       │   ├── util/       Preferences, AuthSession, Formatierung
│       │   └── widget/     Home-Screen-Widget
│       ├── test/           JVM-Unit-Tests und Fixtures
│       └── main/res/       Ressourcen (fast alle Texte stehen im Kotlin-Code)
├── tools/                  Python-Werkzeug zum Extrahieren von SEV-Haltestellen
├── .github/                CI-Workflows, Release-Guard, API-Read-only-Check
├── gradle/                 Versionskatalog
└── llm-wiki/               Diese Wissensbasis
```

## Ausführen, testen, bauen

Voraussetzungen: JDK 17 und Android SDK 36 (CI installiert
`platforms;android-36` und `build-tools;35.0.0`,
`.github/workflows/api-compatibility.yml`).

| Ziel | Befehl |
|---|---|
| Unit-Tests | `./gradlew :app:testDebugUnitTest` |
| Lint | `./gradlew :app:lintDebug` |
| Debug-APK | `./gradlew :app:assembleDebug` |
| Release-APK (unsigniert) | `./gradlew :app:assembleRelease` |
| Release signieren/veröffentlichen | GitHub-Workflow „Android Build, Sign & Release“ |

Es gibt kein `local.properties` im Repository; die SDK-Pfade kommen aus der
Umgebung. Ein Instrumentation- oder Compose-UI-Testverzeichnis existiert nicht.

## Aktueller Status

- Aktive Entwicklung. Letzter Tag: `v1.12.0` (2026-11-03), letzter Commit
  `d485d17` „Document the verified v1.12.0 release“.
- Die App ist ein Client; Backend, Webfrontend und Träwelling-Server sind nicht
  Teil dieses Repositories.
- Funktionsumfang: Token-Login, Feed, Check-in, Statusdetail mit Haltestellen-
  verlauf, Bearbeiten/Löschen, Profile, Suche, Benachrichtigungen, Live-Tracking,
  Sprachansagen, Widget, Fahrterkennung, SEV-Erkennung.
- Reifegrad: umfangreiche JVM-Testabdeckung für Fachlogik; keine automatisierten
  Geräte-/UI-Tests.

## Ausdrücklich außerhalb des Umfangs

- Kein eigener Server und keine eigene Datenbank im Backend.
- Keine iOS-, Desktop- oder Web-App.
- Kein Browser-OAuth-Ablauf im Produktivpfad: Der Manifest-Filter für
  `traewelling://oauth-callback` ist registriert, aber `MainActivity` liest
  `intent.data` nicht aus. Login erfolgt durch Einfügen eines API-Tokens.
- Keine Garantie für Geräteverhalten (Foreground-Service-Timeouts, OEM-Akku-
  Optimierungen); diese Grenzen sind in [Offene Fragen](questions/offene-fragen.md)
  festgehalten.

## Unbekanntes

- `confidence: low` — Es gibt keine `LICENSE`-Datei im Repository; die
  Lizenzierung ist aus dem Repository allein nicht bestimmbar.
- `confidence: low` — Produktentscheidungen hinter dem halbfertigen
  OAuth-Code sind nicht dokumentiert; siehe [Offene Fragen](questions/offene-fragen.md).
- `confidence: low` — Ein lokaler Build wurde bei dieser Initialisierung nicht
  ausgeführt; die Befehle stammen aus CI und Gradle-Konfiguration.

## Wichtige Quellen

- [README.md](../../README.md) — Produktüberblick, Highlights, Tech-Stack.
- [app/build.gradle.kts](../../app/build.gradle.kts) — Android-Konfiguration.
- [.github/workflows/api-compatibility.yml](../../.github/workflows/api-compatibility.yml)
  — Test-, Lint- und Build-Pipeline.
- [Architekturüberblick](architecture/ueberblick.md)
- [Index](index.md)
