# Modul: Fahrterkennung

## Zweck

Routely schlägt anhand frischer GPS-Bewegung und zeitlich passender Träwelling-Abfahrten mögliche Fahrten vor. Das Ergebnis ist eine zu bestätigende Vermutung, keine sichere Identifikation eines Fahrzeugs.

## Kontext

Die Erkennung ergänzt die Stationssuche im Check-in. Der Nutzer aktiviert sie ausdrücklich im Check-in oder in den Einstellungen. `ride_recognition_enabled` ist standardmäßig `false`; eine Anmeldung, präzise Standortfreigabe, eingeschaltete Ortung und das Fehlen einer aktiven Fahrt sind Voraussetzungen.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/RideRecognitionEngine.kt`
- `app/src/main/kotlin/de/traewelling/app/service/RideRecognitionService.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/CheckInScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/MainActivity.kt`

## Ablauf und Datenquellen

1. Die sichtbare `MainActivity` prüft die Voraussetzungen und startet den Location-Foreground-Service. Eine laufende Benachrichtigung macht die Suche sichtbar; sie kann beendet werden.
2. Standortupdates werden mit gewünschten sechs Sekunden und mindestens drei Sekunden angefordert. Ein brauchbarer Fix ist maximal 30 Sekunden alt und höchstens 65 Meter ungenau.
3. Die Suche lädt über den konfigurierten Träwelling-Server nahe Stationen, passende Abfahrten und deren Haltefolgen. **Für diese Nearby-Anfrage werden die aktuelle Breite und Länge an diesen Server übertragen.** Die Zuordnung der Bewegung erfolgt anschließend lokal.
4. Ein Suchdurchlauf ist auf 45 Sekunden begrenzt. Nach einem Durchlauf wartet das Polling 90 Sekunden; ohne brauchbaren Fix wird noch keine Anfrage gesendet. API- und Android-Zustellung sind Best-Effort.
5. Die Engine benötigt mindestens drei präzise Fixes, einen beobachteten Einstiegshalt und mindestens acht Sekunden gerichtete Bewegung zum nächsten nicht gestrichenen Halt. Der Fortschritt muss mindestens 80 Meter beziehungsweise die vierfache maximale Ungenauigkeit und eine mittlere gerichtete Geschwindigkeit von 2,8 bis 95 m/s erreichen. Zeitfenster und räumlicher Korridor begrenzen die Vorschläge.
6. Mehrere ähnlich passende Fahrten bleiben getrennte Vorschläge. Der Nutzer prüft Linie und Richtung, übernimmt eine Vermutung, wählt seinen Ausstieg und bestätigt den normalen Check-in selbst. Die Erkennung führt keinen automatischen `POST` aus.

Ein Vorschlag wird bei Auswahl erneut gegen aktuelle Sitzung, Fixalter und aktive Fahrt geprüft. `open_recognition` in der Erkennungsbenachrichtigung öffnet den Check-in-Bereich. Ein Check-in pausiert die Erkennung; Logout, Server-/Tokenwechsel, ausgeschaltete Ortung oder entzogene präzise Freigabe beenden den Service. Der nächste sichtbare App-Aufruf kann eine weiterhin aktivierte Erkennung wieder starten. Es gibt keinen Boot-Autostart oder unsichtbaren Hintergrundstart.

## Speicher und Grenzen

Fixes, Kandidaten und Tripcache bleiben im RAM. Die Engine begrenzt die Bewegungshistorie auf zwei Minuten beziehungsweise 24 Fixes und hält höchstens zwölf Routenkandidaten mit fünf Minuten Gültigkeit. Nach Service-Ende sind sie entfernt; ausschließlich der Opt-in-Schalter wird gespeichert.

Die Berechnung verwendet einen lokalen Korridor zwischen zwei Stationskoordinaten. Es ist kein exakter Gleis-/Straßenverlauf vorhanden. Kurven, Tunnel, parallele Linien oder fehlende Daten können zu keiner oder mehrdeutiger Erkennung führen; manuelle Auswahl bleibt möglich. Eine bereits eingecheckte Fahrt wird weiterhin durch [TripTracking](./trip-tracking.md) begleitet.

## Abhängigkeiten

- Träwelling: `trains/station/nearby`, `station/{id}/departures`, `trains/trip`
- `FusedLocationProviderClient` und Location-Foreground-Service
- `PreferencesManager`, `CheckInViewModel` und prozesslokaler `StateFlow`

## Offene Fragen

- TODO: Erkennung auf realen Bus-/Bahnfahrten, parallelen Linien, Kurven und nach Standortunterbrechungen prüfen.
- TODO: GPS- und API-Verbrauch während längerer aktivierter Suche auf einem Gerät messen. Synthetische Engine-Tests belegen weder Android-Freigaben noch reale Erkennungsgüte.

## Verwandte Seiten

- [Check-in](./checkin.md)
- [TripTracking](./trip-tracking.md)
- [Settings](./settings.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Datenfluss](../architektur/datenfluss.md)
- [Secrets und Sicherheit](../konfiguration/secrets-und-sicherheit.md)
- [Tests](../entwicklung/tests.md)
