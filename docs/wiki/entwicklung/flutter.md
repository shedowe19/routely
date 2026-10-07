# Flutter entwickeln, prüfen und veröffentlichen

## Werkzeugkette

Flutter **3.47.6** enthält Dart **3.13.5**. Das Projekt liegt in `flutter/`; `flutter pub get --enforce-lockfile` darf die gebundenen Versionen nicht stillschweigend verändern. Android benötigt JDK 17, SDK 36 und die Build-Tools. iOS/macOS benötigen macOS und Xcode; der Apple-CI-Job verwendet Xcode 26.3 auf `macos-15` ohne Eigentümer-Signierung. Der am 07.10.2026 gelesene [offizielle Runnerstand](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-Readme.md) enthält diese Version; künftige Imagewechsel müssen erneut geprüft werden.

```bash
cd flutter
flutter pub get --enforce-lockfile
flutter analyze --fatal-infos
flutter test --coverage
flutter run
```

```bash
flutter build apk --debug
flutter build web --release
flutter build ios --release --no-codesign
flutter build macos --release
flutter build windows --release
flutter build linux --release
```

Linux benötigt GTK, libsecret, Clang, CMake und Ninja; Anmeldung benötigt einen verfügbaren Secret-Service. Der gelockte `geolocator_linux` verwendet GeoClue/D-Bus: Tatsächliche Ortung setzt einen verfügbaren Systemstandortdienst und passende Freigaben voraus. Ohne Standortprovider bleibt die Vordergrundanzeige beim API-/Fahrplan-Rückfall. Web benötigt HTTPS/Localhost, Browser-Standortfreigabe und CORS des gewählten Träwelling-Servers. Der Webrelease verwendet den regulären JavaScript-Build; `flutter_tts` meldet bei der optionalen WebAssembly-Vorabprüfung noch Inkompatibilitäten. Keine Serverproxys oder produktiven Hostingdienste werden durch den Build angelegt.

## Prüfung

`.github/workflows/flutter.yml` prüft Dart, Widget-/Fachtests und die Offline-Releaseguards. Die abhängigen Buildjobs sollen Android-Debug/unsignierte APK, Webbundle, unsignierte iOS-App mit geprüfter eingebetteter `RoutelyWidget.appex`, unsignierte macOS-/Windows-Artefakte und Linux-Bundle erzeugen. Erst ein erfolgreicher jeweiliger Job belegt das Artefakt. Android führt zusätzlich native Vertragstests und Lint aus.

Die Geräteabnahme muss mindestens prüfen:

- Android-Upgrade mit echter bisheriger Signatur und validiertem DataStore-Import; Debug-App ist eine eigene Installation.
- iPhone: Standortfreigabe, Display aus, Hintergrundsprache, Tunnelverlust und Wiederaufnahme, Logout/Stop während einer Ansage.
- App-Group, Widgets, Live Activity und deaktivierte Sperrbildschirmdetails auf einem signierten Apple-Gerät.
- Kontowechsel/Logout bei laufenden API-Anfragen, beendete/entfallene Fahrt und ein neuer Check-in während alter Callback-Zustellung.
- SEV mit eindeutig bestätigten Bushalten; unbekannte/mehrdeutige Punkte liefern Hinweise und API-Rückfall.

TODO: Reale Gerätefahrt und signierte Apple-Installation dokumentieren. Ein grüner CI-Lauf bestätigt Builds/Regressionen, keine Betriebssystem-Laufzeitgarantie.

## Release

Der manuelle `.github/workflows/android.yml` verwendet weiterhin den Releaseguard und die bereits vorhandenen Signing-Secrets. Erst nach Preflight wird gebaut:

```bash
flutter build apk --release --build-name="$RELEASE_VERSION_NAME" --build-number="$RELEASE_VERSION_CODE"
```

Die erzeugte APK unter `flutter/build/app/outputs/flutter-apk/` wird mit dem Eigentümerschlüssel signiert. `apksigner verify` muss anschließend die Signatur akzeptieren; danach prüft der Guard Paketidentität, Version und Code anhand des tatsächlichen Manifests. Der Guard reserviert Version, Tag und Draft auf dem exakten Commit; kein nachträglicher Branchwechsel und kein Überschreiben bestehender Assets.

`pubspec.yaml` verwendet eine Entwicklungs-Buildnummer, die keinen neuen Produktivrelease beansprucht. Android-Produktivreleases überschreiben sie ausschließlich mit der beanspruchten monotonen Nummer. Apple-Buildnummern müssen Apples Format erfüllen und mit der Widget-Erweiterung identisch sein. Apple-TestFlight/App-Store-Veröffentlichung erfordert eine separate Eigentümerkonfiguration und wird nicht automatisch ausgelöst.

## Prüfstand vom 07.10.2026

Die vorhandenen lokalen Logs stammen aus der wiederaufgenommenen Migrationssitzung. Änderungen nach einem Lauf benötigen erneute Prüfung; eine Workflowdatei allein belegt keinen ausgeführten GitHub-CI-Lauf.

| Bereich | Nachweis und Grenze |
| --- | --- |
| Werkzeugkette | Flutter 3.47.6 und Dart 3.13.5 lokal ausgeführt |
| Dart und Flutter | Formatprüfung und `flutter analyze --no-pub --fatal-infos` ohne Befunde; vollständiger Lauf `flutter test --no-pub --coverage --reporter expanded`: 334 Tests erfolgreich. Abhängigkeiten mit `pub get --offline --enforce-lockfile` geprüft. Analytics waren für diese lokalen Prüfungen vollständig unterdrückt. |
| Releaseguard | 56 Offline-Regressionen nach Ergänzung der Signaturprüfungs-Reihenfolge erfolgreich; keine Signierung oder Veröffentlichung ausgelöst |
| Web | Regulärer JavaScript-Releasebuild nach den Abschlusskorrekturen erfolgreich (36,2 Sekunden); `flutter_tts` meldet im optionalen WebAssembly-Dry-Run Inkompatibilitäten. Ein WebAssembly-Build wird nicht behauptet. |
| Android | Der lokale APK-Build erreicht wegen `Network is unreachable` beim Gradle-Wrapper-Download keine Compilation. Native Tests, Lint, Debug- und Release-APK sind dadurch lokal nicht belegt. |
| iOS und macOS | Xcode-/Widget-Target und CI-Schritte sind vorhanden; Linux kann deren nativen Build oder signierte Installation nicht ausführen. Ein erfolgreicher Apple-CI-Lauf steht aus. |
| Windows und Linux | Buildziele und CI-Jobs sind eingerichtet; ein erfolgreicher nativer Build ist bislang nicht belegt. |
| Native Quellprüfung | iOS-Xcode-Objektbeziehungen, Widget-Embed-Phase, geteiltes Activity-Schema sowie XML/Plist/Entitlements sind offline geprüft. Sechs Android-TrackingContract-Tests sind im Quellstand vorhanden, einschließlich Fix-Frische; sie sind lokal noch nicht ausgeführt. Dies ersetzt keine native Compilation. |
| Gerätebetrieb | Echte Fahrten, Display-aus-Betrieb, iPhone-Widgets/Live Activity und signiertes Android-Upgrade bleiben praktische Abnahme. |

TODO: Den ersten vollständigen GitHub-CI-Lauf für den veröffentlichten Migrationscommit mit Lauf-URL und tatsächlichen Ergebnissen ergänzen.

## Verwandte Seiten

- [Flutter-Architektur und Plattformgrenzen](../architektur/flutter-migration.md)
- [Build](./build.md)
- [Tests](./tests.md)
- [Deployment](./deployment.md)
- [Secrets](../konfiguration/secrets-und-sicherheit.md)
