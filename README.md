# 🚅 Routely

[![Flutter](https://img.shields.io/badge/Flutter-3.47.6-02569B.svg)](https://flutter.dev)
[![Platforms](https://img.shields.io/badge/Buildziele-Android%20%7C%20iOS%20%7C%20Desktop%20%7C%20Web-534BAE.svg)](docs/wiki/architektur/flutter-migration.md)

**Routely** verbindet Träwelling-Check-ins mit GPS-Reisebegleitung, Haltestellenansagen, SEV-Hinweisen und Community. Die gemeinsame Flutter-Anwendung liegt unter [`flutter/`](flutter/). Ihr Material-Design übernimmt die bisherigen Indigo-, Teal- und Amber-Farben, Verkehrsfarben, runden Karten sowie Hell-, Dunkel- und AMOLED-Einstellungen.

## Funktionen

- Anmeldung an einem HTTPS-Träwelling-Server, Feeds, Profile, Nutzersuche, Folgen und Meldungen.
- Check-in mit Stations-/Abfahrtsuche, konkretem Zielhalt, manuellen Zeiten und Konfliktbehandlung; Fahrtdetails mit Bearbeitung und Löschung.
- GPS-Fortschritt, Haltestellenansagen, Fahrtänderungen und lokale Zeitprognosen mit API-/Fahrplan-Rückfall.
- Träwelling-Bahn-/Tram-Geometrie, vorsichtige SEV-Ersatzhaltestellenauflösung und Straßenprojektion für bestätigte Ersatzhalte.
- Bestätigungspflichtige Fahrterkennung und plattformspezifische Reiseanzeigen.

Android und iOS enthalten native Adapter für Hintergrundbegleitung, Widgets und Sperrbildschirmfortschritt. Web, Windows, macOS und Linux verwenden die gemeinsame Oberfläche und begleiten im Vordergrund. Die [Funktions- und Plattformübersicht](docs/wiki/architektur/flutter-migration.md) erklärt die jeweiligen Grenzen.

## Start

Verwende Flutter **3.47.6** mit Dart **3.13.5**:

```bash
cd flutter
flutter pub get --enforce-lockfile
flutter analyze --fatal-infos
flutter test
flutter run
```

Android benötigt JDK 17 und SDK 36; iOS/macOS benötigen macOS und Xcode. Web benötigt die CORS-Freigabe des ausgewählten API-Servers; Linux einen Secret-Service für die Anmeldung. [Setup und Buildbefehle](docs/wiki/entwicklung/flutter.md) enthalten die weiteren Voraussetzungen.

## Prüf- und Releasestand

Die CI ist für Android, iOS, Web, Windows, macOS und Linux eingerichtet. Ein eingerichteter Buildjob ist kein erfolgreicher Plattformnachweis: Der [aktuelle Prüfstand](docs/wiki/entwicklung/flutter.md#prüfstand-vom-07102026) unterscheidet ausgeführte Tests, Builds und offene Geräteabnahme. iPhone-Hintergrundbetrieb, Widgets, Android-Upgrade und echte Fahrten benötigen weiterhin praktische Prüfung.

Der manuelle Android-Releaseworkflow baut Flutter, verwendet die bestehenden Eigentümer-Signing-Secrets und schützt Versionscodes, Commitbindung und neue Releases vor Überschreiben. Apple-Veröffentlichung benötigt Eigentümer-Signierung und App-Group-Provisionierung. Unsigned CI erzeugt keine installierbare iPhone-IPA und veröffentlicht keinen Store.

Der vorherige Kotlin-/Compose-Quellstand unter `app/` bleibt als Verhaltensreferenz mit seinen Regressionen erhalten.

## Projekt-Wiki

- [Projekt-Wiki](docs/wiki/index.md)
- [Flutter-Architektur und Funktionsvergleich](docs/wiki/architektur/flutter-migration.md)
- [Entwicklung, Prüfung und Release](docs/wiki/entwicklung/flutter.md)
- [Offene Geräteprüfungen](docs/wiki/offene-fragen.md)

*Entwickelt für die Träwelling-Community.*
