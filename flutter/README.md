# Routely für Flutter

Die gemeinsame Anwendung übernimmt das bestehende Routely-Design und die Träwelling-Funktionen. Eingerichtete Buildziele: Android, iOS, Web, Windows, macOS und Linux.

```bash
flutter pub get --enforce-lockfile
flutter analyze --fatal-infos
flutter test
flutter run
```

Verwende Flutter **3.47.6**. Android benötigt JDK 17 und SDK 36; Apple-Builds benötigen macOS/Xcode. Hintergrunddienste, Widgets und Sperrbildschirm-Fortschritt sind nativ für Android/iOS angebunden. Browser/Desktop begleiten im Vordergrund. Web benötigt HTTPS und CORS des ausgewählten API-Servers, Linux einen Secret-Service.

Die bisherige Kotlin-App bleibt unter `../app/` als Verhaltensreferenz. Der manuelle Android-Releaseworkflow baut Flutter und bewahrt die bestehenden Versions- und Signing-Guards. Apple-Publikation braucht die Eigentümer-Signierung und App-Group-Konfiguration. Unsigned CI ist kein installierbares Store-Release.

- [Architektur und Funktionsvergleich](../docs/wiki/architektur/flutter-migration.md)
- [Entwicklung, Prüfung und Release](../docs/wiki/entwicklung/flutter.md)
- [Offene Geräteprüfungen](../docs/wiki/offene-fragen.md)

Der [aktuelle Prüfstand](../docs/wiki/entwicklung/flutter.md#prüfstand-vom-07102026) unterscheidet vorhandene Plattformintegration, tatsächlich erfolgreiche Builds und offene Geräteabnahme.
