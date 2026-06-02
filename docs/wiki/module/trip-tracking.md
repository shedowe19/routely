# Modul: TripTrackingService

## Zweck

Der `TripTrackingService` ist ein Android-foreground Service, der nach einem Check-in die aktive Fahrt überwacht. Er pollt alle 60 Sekunden die Trip-Daten und zeigt den Fortschritt in einer laufenden Notification.

## Kontext

Nach einem erfolgreichen Check-in wird der Service gestartet (`TripTrackingService.kt`). Er läuft im Hintergrund und informiert den Nutzer über:

- Nächsten Halt und Ankunftszeit
- Optional geschätzte GPS-ETA und Entfernung zum nächsten Halt
- Aktuelle Gleisänderungen
- Erreichen der Zielstation

Zusätzlich kann er TTS-Ankündigungen (Text-to-Speech) für die nächsten Haltestellen machen.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/TripTrackingService.kt`
- `app/src/main/kotlin/de/traewelling/app/widget/TripWidgetProvider.kt`
- `app/src/main/AndroidManifest.xml`

## Verhalten

### Startup

1. Service wird mit `EXTRA_STATUS_ID` gestartet
2. Prüft ob ein `activeStatusId` in `PreferencesManager` gespeichert ist
3. Startet den Tracking-Job mit 60-Sekunden-Pollintervall

### Tracking-Logik

1. Lädt Status-Details via `repo.getStatusDetail(statusId)`
2. Lädt Stopovers via `repo.getStopovers(tripId)`
3. Berechnet den nächsten Halt basierend auf aktueller Zeit
4. Ergänzt optional eine Standortschätzung für den nächsten Halt
5. Prüft ob Ziel erreicht (Zeit vergangen + kein weiterer Halt)
6. Aktualisiert Notification und sendet Widget-Broadcast

### Standortbasierte ETA

Wenn Standortberechtigung, aktueller Standort und Stopover-Koordinaten (`StopStation.latitude`/`longitude`) vorhanden sind, berechnet der Service zusätzlich:

- Distanz vom aktuellen Standort zum nächsten Halt
- geschätzte ETA zum nächsten Halt

Die ETA nutzt bevorzugt die GPS-Geschwindigkeit des aktuellen `Location`-Fixes. Wenn keine verwertbare Geschwindigkeit vorhanden ist, wird eine Segmentgeschwindigkeit aus vorherigem Halt, nächstem Halt und deren geplanten/realen Zeiten geschätzt. Die Berechnung ist bewusst defensiv begrenzt, damit fehlerhafte Koordinaten oder unrealistische Distanzen nicht angezeigt werden.

Der Standortabruf läuft über `FusedLocationProviderClient.getCurrentLocation(...)` und wird nach 5 Sekunden abgebrochen. Dadurch blockiert ein ausbleibender Standort-Fix nicht dauerhaft das 60-Sekunden-Polling.

Fallback-Regel: Wenn GPS, Standortberechtigung, Koordinaten oder eine plausible Geschwindigkeitsbasis fehlen, bleibt die bisherige fahrplan-/API-basierte Logik aktiv. Notification, Widget und TTS funktionieren dann wie zuvor.

Der Service ist im Manifest mit `android:foregroundServiceType="dataSync|location"` registriert. Beim Start wird der `location`-Foreground-Service-Typ nur zusätzlich verwendet, wenn eine Standortberechtigung vorhanden ist. Falls Android den Location-Foreground-Service-Typ zur Laufzeit ablehnt, startet der Service mit `dataSync` weiter und nutzt den regulären Fallback ohne GPS-ETA.

### TTS (Text-to-Speech)

- Spricht Ankündigungen wenn `TTS_ENABLED` in Preferences
- Unterstützt benutzerdefinierte Engine, Sprache und Stimme
- Annahme: Ankündigung wenn Ankunft in 0-3 Minuten
- Wenn eine GPS-ETA verfügbar ist, kann diese das 0-3-Minuten-Fenster für Ankündigungen bestimmen; andernfalls wird die reguläre Fahrplanzeit verwendet
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
- **FusedLocationProviderClient**: Optionaler Standort-Fix für GPS-basierte ETA

## Offene Fragen

- Unklar: Ob alle Stopover-Antworten der API `latitude` und `longitude` liefern. Wenn nicht, greift automatisch der Fallback auf reguläre Zeiten.

## Verwandte Seiten

- [Check-in](./checkin.md)
- [Architektur Überblick](../architektur/ueberblick.md)
