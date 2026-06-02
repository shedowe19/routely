# Modul: TripTrackingService

## Zweck

Der `TripTrackingService` ist ein Android-foreground Service, der nach einem Check-in die aktive Fahrt überwacht. Er pollt alle 60 Sekunden die Trip-Daten und zeigt den Fortschritt in einer laufenden Notification.

## Kontext

Nach einem erfolgreichen Check-in wird der Service gestartet (`TripTrackingService.kt`). Er läuft im Hintergrund und informiert den Nutzer über:

- Nächsten Halt und Ankunftszeit
- Aktuelle Gleisänderungen
- Erreichen der Zielstation

Zusätzlich kann er TTS-Ankündigungen (Text-to-Speech) für die nächsten Haltestellen machen.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/widget/TripWidgetProvider.kt`

## Verhalten

### Startup

1. Service wird mit `EXTRA_STATUS_ID` gestartet
2. Prüft ob ein `activeStatusId` in `PreferencesManager` gespeichert ist
3. Startet den Tracking-Job mit 60-Sekunden-Pollintervall

### Tracking-Logik

1. Lädt Status-Details via `repo.getStatusDetail(statusId)`
2. Lädt Stopovers via `repo.getStopovers(tripId)`
3. Berechnet den nächsten Halt basierend auf aktueller Zeit
4. Prüft ob Ziel erreicht (Zeit vergangen + kein weiterer Halt)
5. Aktualisiert Notification und sendet Widget-Broadcast

### TTS (Text-to-Speech)

- Spricht Ankündigungen wenn `TTS_ENABLED` in Preferences
- Unterstützt benutzerdefinierte Engine, Sprache und Stimme
- Annahme: Ankündigung wenn Ankunft in 0-3 Minuten
- Spezielle Texte für Start- und Endstation

### Widget-Update

Broadcast an `TripWidgetProvider` mit:

- `lineName`, `nextStop`, `destination`, `time`, `platform`, `delay`

## Stopp-Bedingungen

- Zielbahnhof erreicht (Ankunftzeit vergangen + kein weiterer Halt)
- Manuell über Notification-Aktion "Beenden"
- `prefs.saveActiveStatusId(null)` wird aufgerufen

## Abhängigkeiten

- **TraewellingRepository**: Für API-Aufrufe
- **PreferencesManager**: Für TTS-Einstellungen und activeStatusId
- **TextToSpeech**: Android TTS Engine

## Offene Fragen

- Keine spezifischen aktuell.

## Verwandte Seiten

- [Check-in](./checkin.md)
- [Architektur Überblick](../architektur/ueberblick.md)
