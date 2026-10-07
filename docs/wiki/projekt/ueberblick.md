# Projekt: Überblick

## Zweck

Dokumentiert, was das Projekt macht und für wen es gedacht ist.

## Kontext

**Routely** ist eine Reisebegleitung für die Träwelling-Plattform mit Check-in in Zügen und Bussen. Die Flutter-Anwendung unter `flutter/` unterstützt Android, iOS, Web, Windows, macOS und Linux; die native Hintergrundbegleitung ist für Android/iOS implementiert. Der vorherige Android-Quellstand unter `app/` bleibt als Verhaltensreferenz erhalten.

## Hauptfunktionen

- Check-in bei Transitfahrten (Feed, Suchen).
- Haltestellen- und Fahrtenverfolgung.
- Meldungen (Notifications) und Profil-Verwaltung.
- Anzeige von Live-Status, Verspätungen und manuellen Zeitedits.

## Technologien

- **Gemeinsame Anwendung**: Flutter 3.47.6 und Dart mit Material-Oberflächen.
- **Architektur**: sessiongebundene Controller, API-/Speicherschicht und deterministische Reiseberechnung.
- **Netzwerk**: HTTPS über `http`; getrennte öffentliche Strecken-/SEV-Abfragen ohne Kontotoken.
- **Speicherung**: plattformspezifischer sicherer Sitzungsdatensatz, SharedPreferences-Einstellungen und partitionierter Dateicache; im Browser bleibt der Feedcache im Arbeitsspeicher.
- **Native Hosts**: Kotlin für Android-Standortdienst, Mitteilungen und Widget; Swift für iOS CoreLocation, Sprachausgabe und ActivityKit/WidgetKit.

Der erhaltene Android-Ausgangsstand verwendet Kotlin/Compose, Retrofit/OkHttp/Gson und Room. Er ist nicht die gemeinsame Flutter-Laufzeit.

## Verwandte Seiten

- [Ziele](./ziele.md)
- [Architektur Überblick](../architektur/ueberblick.md)
- [Flutter-Migration](../architektur/flutter-migration.md)
