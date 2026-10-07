# ADR: Flutter für plattformübergreifende Routely-Reisebegleitung

Datum: 07.10.2026

## Kontext

Die bisherige Kotlin-/Compose-Anwendung bietet Android-Reisebegleitung mit GPS, SEV, Ansagen und Live-Fortschritt. Gewünscht ist dieselbe App mit weitgehend gleichem Design und Funktionsumfang auch für Apple iOS und weitere Geräte. Die bestehenden Besuchs-, Mutations-, Sitzungs- und Releaseverträge müssen erhalten bleiben.

## Entscheidung

Die gemeinsame Flutter-Anwendung liegt in `flutter/`. Dart teilt UI, API und deterministische Reiseberechnung. Android und iOS erhalten native Lebenszyklusadapter für Standort, Hintergrundausführung und Sperrbildschirm. Ein eigener nativer Hintergrund-Engine-Einstieg berechnet die Reise; die sichtbare Engine beobachtet seine Snapshots. Browser/Desktop begleiten nur im Vordergrund.

Der vorherige `app/`-Quellstand bleibt als Referenz mit Tests erhalten. Der manuelle Android-Releaseworkflow wechselt auf Flutter, behält jedoch dieselbe Paketidentität, Eigentümer-Signierung, permanente Versionsclaims, SHA-Bindung und Manifestprüfung. Die plattformübergreifende Prüfpipeline erzeugt Test-/unsignierte Artefakte und veröffentlicht keine Stores oder Releases.

## Gründe

Eine reine Neuzeichnung der Screens würde die Hintergrundfunktionen verlieren. Eine gemeinsame Dart-Engine mit klarer nativer Verantwortung vermeidet zwei konkurrierende GPS-Berechnungen und erhält die überprüfbaren Fachregeln. Native Betriebssystemgrenzen werden sichtbar dokumentiert und als Geräteprüfungen behandelt.

Die Migration wird auf einem eigenen Branch und in einem englischen PR überprüfbar veröffentlicht. Sie wird nicht mit einer ungeprüften automatischen Store-Veröffentlichung verbunden.

## Folgen

- Flutter/Dart und Xcode kommen als Werkzeugketten hinzu; das SDK ist festgelegt, Abhängigkeiten sind gelockt.
- Android-Altinstallationen benötigen einen validierten, commitgesicherten Datenimport.
- Apple-App-Groups und Signierung müssen im Eigentümerkonto eingerichtet werden.
- Neue Tests prüfen neben Algorithmen die sichtbare/native Übergabe, Sitzungswechsel, Teilfehler und UI-Zustände.
- Unsicher: Zuverlässigkeit auf echten iPhones bei Display aus, längeren Tunneln und iOS-Suspendierung; diese kann kein Linux-Test beweisen.

## Verwandte Seiten

- [Architektur und Funktionsvergleich](../architektur/flutter-migration.md)
- [Flutter-Entwicklung](../entwicklung/flutter.md)
- [Vorherige aktive-Fahrt-Verträge](./2026-10-07-aktive-fahrt-und-beobachtungen.md)
- [Build](../entwicklung/build.md)
- [Deployment](../entwicklung/deployment.md)
