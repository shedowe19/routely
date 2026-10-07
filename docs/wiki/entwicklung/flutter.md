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

Die lokalen Abschlussprüfungen betreffen den Migrations-Codecommit `c1f1d80a04fcddadcbbfa300766a5de1a5aefb59`. Der erste [Flutter-CI-Lauf 37608461710](https://github.com/shedowe19/routely/actions/runs/37608461710) prüfte dessen synthetischen PR-Merge `a3426aec8cbf3648cb691882abf35fd8b0a99394` mit dem unveränderten Main-Stand `8ecd87b1727d7a95d6fb09d40bb8541ece0c4695`. Dieser historische Lauf ist mit einem Android-Lintfehler abgeschlossen; die übrigen Plattformjobs waren erfolgreich.

| Bereich | Nachweis und Grenze |
| --- | --- |
| Werkzeugkette | Flutter 3.47.6 und Dart 3.13.5 lokal ausgeführt |
| Dart und Flutter | Lokale Formatprüfung, Analyse, Lockfileprüfung und 334 Flutter-Tests erfolgreich. CI bestätigt Lockfileauflösung, Analyse ohne Befunde, alle 334 Tests und den Coverage-Upload. |
| Releaseguard | 56 Offline-Regressionen lokal und in CI erfolgreich; keine Eigentümer-Signierung oder Veröffentlichung ausgelöst |
| Web | JavaScript-Releasebuild lokal und in CI erfolgreich, Web-Artefakt hochgeladen. Der optionale WebAssembly-Dry-Run meldet weiterhin `flutter_tts`-Inkompatibilitäten; ein WebAssembly-Build ist nicht belegt. |
| Android lokal | Der lokale APK-Build erreicht wegen `Network is unreachable` beim Gradle-Wrapper-Download keine Compilation. Native Tests, Lint und APKs sind dadurch lokal nicht belegt. |
| Android in erster CI | Debug-APK erfolgreich gebaut und hochgeladen; `TrackingContractTest` mit sechs Tests, null Fehlern und null übersprungenen Tests erfolgreich. `lintDebug` scheitert an zwei `MissingPermission`-Fehlern in `MainActivity.kt:96` und `TrackingHost.kt:419`. Unsigned Release-Build und dessen Prüfung/Upload wurden deshalb übersprungen. |
| iOS und macOS | Apple-Job erfolgreich: unsigned iOS-App kompiliert, eingebettetes Widget-Binary, Bundle-IDs und übereinstimmende App-/Widget-Versionen geprüft; unsigned iOS- und macOS-Artefakte hochgeladen. Dies ist keine signierte iPhone-Installation oder notarisiertes macOS-Release. |
| Windows und Linux | Native Release-Builds und vollständige Artefaktuploads in CI erfolgreich. Gerätespezifische Standort-, Speicher- und Sprachdienste benötigen weiterhin praktische Prüfung. |
| Erhaltener Kotlin-Stand | [API Compatibility 37608461624](https://github.com/shedowe19/routely/actions/runs/37608461624) erfolgreich: Unit-Tests, vollständiges Debug-Lint, Debug-/Release-Build und Artefaktuploads. Diese Ergebnisse gehören zur Kotlin-Referenz, nicht zu zusätzlichen Flutter-Tests. |
| Native Quellprüfung | iOS-Xcode-Objektbeziehungen, Widget-Embed-Phase, geteiltes Activity-Schema sowie XML/Plist/Entitlements sind offline geprüft. Native Apple-Compilation ist zusätzlich durch den oben genannten Apple-Job belegt. |
| Gerätebetrieb | Echte Fahrten, Display-aus-Betrieb, iPhone-Widgets/Live Activity und signiertes Android-Upgrade bleiben praktische Abnahme. |

Die nachfolgende Android-Quellkorrektur behandelt `SecurityException` ausdrücklich an beiden Standortregistrierungen; `IllegalArgumentException` deckt währenddessen verschwundene Provider ab. Teilregistrierungen werden freigegeben. Die einmalige Suche meldet fehlende Freigabe beziehungsweise fehlenden Provider; normale Begleitung kann auf den Fahrplan zurückfallen. Fahrterkennung braucht mindestens eine tatsächlich registrierte Quelle und wird andernfalls abgelehnt/beendet. Der erste fehlgeschlagene Lauf belegt diesen späteren Fix noch nicht.

Der aktuelle Korrektur- und Prüfstatus steht in [PR 38](https://github.com/shedowe19/routely/pull/38) und bei [Flutter Cross-Platform](https://github.com/shedowe19/routely/actions/workflows/flutter.yml). Für die erhaltene Kotlin-Referenz gilt [API Compatibility](https://github.com/shedowe19/routely/actions/workflows/api-compatibility.yml). Spätere Ergebnisse dürfen nur dem jeweils tatsächlich geprüften Commit zugeordnet werden.

Die Android-Freigabe setzt einen erfolgreichen neuen Lauf mit nativen Tests, vollständigem Lint, Debug- und unsigned Release-Build voraus; maßgeblich sind die commitgebundenen Checks und Artefakte des verlinkten Pull Requests. Eigentümer-Signierung und reale Geräteabnahme bleiben getrennte offene Nachweise.

## Verwandte Seiten

- [Flutter-Architektur und Plattformgrenzen](../architektur/flutter-migration.md)
- [Build](./build.md)
- [Tests](./tests.md)
- [Deployment](./deployment.md)
- [Secrets](../konfiguration/secrets-und-sicherheit.md)
