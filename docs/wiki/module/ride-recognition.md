# Modul: Fahrterkennung

## Zweck

Routely schlägt anhand frischer GPS-Bewegung und zeitlich passender Träwelling-Abfahrten mögliche Fahrten vor. Das Ergebnis ist eine zu bestätigende Vermutung, keine sichere Identifikation eines Fahrzeugs.

## Kontext

Die Erkennung ergänzt die Stationssuche im Check-in. Der Nutzer aktiviert sie ausdrücklich im Check-in oder in den Einstellungen. `ride_recognition_enabled` ist standardmäßig `false`; eine Anmeldung, präzise Standortfreigabe, eingeschaltete Ortung und das Fehlen einer aktiven Fahrt sind Voraussetzungen.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/service/RideRecognitionEngine.kt`
- `app/src/main/kotlin/de/traewelling/app/service/RideRecognitionService.kt`
- `app/src/main/kotlin/de/traewelling/app/service/RideDiscoveryRequests.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/ui/screens/CheckInScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/MainActivity.kt`

## Ablauf und Datenquellen

1. Die sichtbare `MainActivity` prüft die Voraussetzungen und startet den Location-Foreground-Service. Eine laufende Benachrichtigung macht die Suche sichtbar; sie kann beendet werden.
2. Standortupdates werden mit gewünschten sechs Sekunden und mindestens drei Sekunden angefordert. Ein brauchbarer Fix ist maximal 30 Sekunden alt und höchstens 65 Meter ungenau.
3. Die Suche lädt über den konfigurierten Träwelling-Server nahe Stationen, passende Abfahrten und deren Haltefolgen. `TraewellingRepository.getNearbyStations` berechnet aus dem aktuellen Standort eine ungefähr einen Kilometer in jede Richtung reichende Bounding-Box und ruft `GET /api/v1/stations` auf. **Die daraus abgeleiteten Standortparameter `min_lat`, `max_lat`, `min_lon` und `max_lon` werden an diesen Server übertragen; die Mitte der Box entspricht dem verwendeten Standortfix.** Die Zuordnung der Bewegung erfolgt anschließend lokal.
4. Ein Suchdurchlauf ist auf 45 Sekunden begrenzt. Nach einem Durchlauf wartet das Polling 90 Sekunden; ohne brauchbaren Fix wird noch keine Anfrage gesendet. API- und Android-Zustellung sind Best-Effort.
5. Die Engine benötigt mindestens drei präzise Fixes, einen beobachteten Einstiegshalt und mindestens acht Sekunden gerichtete Bewegung zum nächsten nicht gestrichenen Halt. Der Fortschritt muss mindestens 80 Meter beziehungsweise die vierfache maximale Ungenauigkeit und eine mittlere gerichtete Geschwindigkeit von 2,8 bis 95 m/s erreichen. Zusätzlich darf kein aufeinanderfolgendes Fixpaar mehr als `100 m/s × Zeitabstand + beide Genauigkeiten` Bewegung enthalten. Eine plausible Gesamtgeschwindigkeit darf einen nahezu sofortigen GPS-Sprung nicht verdecken. Zeitfenster und räumlicher Korridor begrenzen die Vorschläge.
6. Mehrere ähnlich passende Fahrten bleiben getrennte Vorschläge. Der Nutzer prüft Linie und Richtung, übernimmt eine Vermutung, wählt seinen Ausstieg und bestätigt den normalen Check-in selbst. Die Erkennung führt keinen automatischen `POST` aus.

Ein Vorschlag wird bei Auswahl erneut gegen aktuelle Sitzung, Fixalter und aktive Fahrt geprüft. `open_recognition` in der Erkennungsbenachrichtigung öffnet den Check-in-Bereich. Ein Check-in pausiert die Erkennung; Logout, Server-/Tokenwechsel, ausgeschaltete Ortung oder entzogene präzise Freigabe beenden den Service. Der nächste sichtbare App-Aufruf kann eine weiterhin aktivierte Erkennung wieder starten. Es gibt keinen Boot-Autostart oder unsichtbaren Hintergrundstart.

Der Service liest Anmeldung, aktive Status-ID, GPS-Schalter und Erkennungs-Opt-in aus einem atomaren `trackingConfiguration`-Snapshot. Sichtbare Startaufträge, Benachrichtigungsaktionen und `RideRecognitionState.authSessionRevision` sind an die Zugangsgeneration gebunden. Das Check-in-ViewModel zeigt und übernimmt nur Vorschläge mit exakt passender aktueller Revision. Auch eine spätere Anmeldung mit denselben Zugangsdaten darf dadurch keine früheren Kandidaten oder verspäteten Aktionen übernehmen; die Revision bleibt RAM-Metadatum des Vorschlags, keine persistierte Fahrtvermutung.

Der gemeinsame Helfer `SessionStartRequests` lässt erst atomar validierte Starts frühere Initialisierungen verdrängen. Eine verspätete ungültige Startanfrage darf keinen passenden neuen Scan oder wartenden Start beenden; ohne gültigen Scan beziehungsweise passenden Start bleibt keine leere Foreground-Anzeige stehen.

Die Suche unterscheidet einen vollständigen Anfrageausfall von einem erfolgreichen Durchlauf ohne passenden Vorschlag. Scheitern alle tatsächlich angefragten Abfahrtsantworten oder sind alle benötigten Tripdetails erfolglos, meldet der Service `ERROR` und einen erneuten Versuch nach 90 Sekunden. Mindestens eine erfolgreiche Antwort hält Teilergebnisse nutzbar; auch eine erfolgreiche leere Abfahrtsliste und ein noch gültiger Tripcache-Eintrag gelten als Erfolg. Werden in einer Stufe keine Anfragen benötigt, entsteht daraus kein Fehler. `RideDiscoveryRequests` sammelt diese Entscheidung je Stufe und reicht Coroutine-Abbrüche sofort weiter. Nur die eigene 45-Sekunden-Zeitgrenze wird als Zeitüberschreitung angezeigt; eine beendete Such-Coroutine darf keinen nachträglichen Erfolgs- oder Fehlerzustand publizieren. Erfolgreiche Abfahrtsantworten entfernen als entfallen gemeldete Fahrten bereits vor den Tripdetail-Anfragen aus der Engine, nach erneuter Sitzungs-/Freigabeprüfung. Ein späterer Detailausfall oder Timeout darf diese Kandidaten nicht durch die alte Route wieder anbieten.

## Speicher und Grenzen

Fixes, Kandidaten und Tripcache bleiben im RAM. Die Engine begrenzt die Bewegungshistorie auf zwei Minuten beziehungsweise 24 Fixes und hält höchstens zwölf Routenkandidaten mit fünf Minuten Gültigkeit. Nach Service-Ende sind sie entfernt; ausschließlich der Opt-in-Schalter wird gespeichert.

Ein prozesslokaler höchster beobachteter Fixzeitstempel verhindert, dass verspätete Batches den Zustand zurückdrehen. Auch ein neuer zeitlich gültiger, aber ungenauer Fix beendet die vorherige Verlässlichkeit; ein danach eintreffender älterer präziser Fix kann sie nicht wiederherstellen. Ein tatsächlich zukünftiger Zeitstempel verbraucht dieses Wasserzeichen nicht. Das Löschen der Engine setzt es zurück; es wird nicht persistiert.

Die Berechnung verwendet einen lokalen Korridor zwischen zwei Stationskoordinaten. Es ist kein exakter Gleis-/Straßenverlauf vorhanden. Kurven, Tunnel, parallele Linien oder fehlende Daten können zu keiner oder mehrdeutiger Erkennung führen; manuelle Auswahl bleibt möglich. Eine bereits eingecheckte Fahrt wird weiterhin durch [TripTracking](./trip-tracking.md) begleitet.

## Abhängigkeiten

- Träwelling: `stations` mit Bounding-Box, `station/{id}/departures`, `trains/trip`. Der ebenfalls deklarierte Retrofit-Endpunkt `trains/station/nearby` wird in diesem Ablauf nicht aufgerufen.
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
