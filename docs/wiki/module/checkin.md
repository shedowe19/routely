# Modul: Check-in

## Zweck

Ermöglicht dem Nutzer, Haltestellen zu suchen, Verbindungen abzufragen und letztendlich in einen Zug, Bus oder eine Tram einzuchecken. Dies ist die Hauptfunktionalität der Routely-App.

## Kontext

Der Check-in Prozess führt den Nutzer schrittweise von der Ortung/Suche bis zur Bestätigung der Fahrt. Die UI ist primär im `CheckInScreen` zu finden, der Teil des zentralen HorizontalPagers ist.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/CheckInScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/CheckInViewModel.kt`

## Verhalten und Ablauf

Der typische Ablauf eines Check-ins nutzt mehrere API-Endpunkte nacheinander:

1. **Bahnhofsauswahl (Start):**
   - Entweder über die Textsuche (`GET /api/v1/trains/station/autocomplete/{query}`)
   - Oder über die Ortung (`GET /api/v1/stations` mit Koordinaten der Bounding-Box)
   - _Wichtig:_ Stationsergebnisse müssen dedupliziert werden (z.B. nach Nähe und Namen).

2. **Abfahrtsauswahl:**
   - Sobald ein Startbahnhof gewählt ist, werden die Abfahrten geladen (`GET /api/v1/station/{id}/departures`).
   - Die `id` des Bahnhofs (numerisch) muss verwendet werden.
   - Liefert die ausgewählte Abfahrt ein eigenes `station`-Objekt, ist diese tatsächliche Abfahrtsstation für den Einstieg maßgeblich; sie kann vom zuvor gesuchten Bahnhof abweichen.

3. **Zielauswahl (Trip Detail):**
   - Wählt der Nutzer eine Abfahrt, muss der Zielbahnhof bestimmt werden.
   - Dazu wird die gesamte Route der Fahrt geladen (`GET /api/v1/trains/trip` mit `hafasTripId` und `lineName`).
   - Die App zeigt die Liste der kommenden Haltestellen an.
   - Stationsdaten stammen aus `stopover.station`. Start und Ziel nutzen `stationId`, Namen nutzen `stationName`, Kennungen nutzen `stationIdentifier(type)`.
   - Konkrete Halte werden über `matchesStopover` unterschieden, damit mehrere Besuche desselben Bahnhofs auf einer Fahrt nicht verwechselt werden.

4. **Der eigentliche Check-in:**
   - Wenn Start, Fahrt und Ziel bekannt sind, wird der Check-in durchgeführt.
   - `POST /api/v1/trains/checkin` mit `CheckInRequest` (Start, Ziel, Fahrt-ID, Reisegrund, etc.).
   - Der Reisegrund wird über das API-Feld `business` gesendet. Die App verwendet `TravelReason.PRIVATE` (`0`) als Standard und erlaubt die Auswahl von `BUSINESS` (`1`) und `COMMUTE` (`2`).
   - Manuelle Verspätungs-Overrides (`manualDeparture`, `manualArrival`), die die API zurückgibt, müssen direkt ins Datenmodell gemerged werden, um UI-Flackern zu vermeiden.

Abfahrten zeigen `direction` als Fahrtrichtung und nutzen `delayMinutes`, berechnet aus `when - plannedWhen`. Bei fehlender Echtzeit wird die Planzeit angezeigt. Ein Betreiber wird aus `TripDetails.operator` beziehungsweise `CheckinInfo.operator` gelesen; das veraltete `line.operator` wird nicht vorausgesetzt.

Der Check-in überträgt weiterhin numerische interne Station-IDs für `start` und `destination` und den Provider-Identifier für `tripId`. Eine Stopover-ID, IBNR oder Trip-UUID darf diese Werte nicht ersetzen.

## Zeitfelder und Konflikte

Für einen Halt gelten `effectiveDeparture = departureReal ?: departurePlanned` und `effectiveArrival = arrivalReal ?: arrivalPlanned`. Die Legacy-Felder `departure` und `arrival` sind keine Datenquelle mehr.

Ein erfolgreicher Check-in enthält `data.status` und `data.points`. Bei HTTP 409 liest das Repository `data.conflicts`, erzeugt eine `CheckInConflictException` und nennt die betroffenen Linien, Ziele und Status-IDs. Die auslaufenden Felder `message.status_id` und `message.lineName` werden nicht verwendet. Eine leere Konfliktliste führt zu einer allgemeinen Überschneidungsmeldung.

## Start der Fahrtverfolgung

Nach einem erfolgreichen Check-in speichert `CheckInViewModel` die aktive Status-ID. `MainActivity` übernimmt den sichtbaren, berechtigungsgeprüften Start des [TripTrackingService](./trip-tracking.md); das ViewModel startet keinen GPS-Foreground-Service aus dem Hintergrund.

## Reisegrund

Im Bestätigungsschritt zeigt `CheckInScreen` eine Chip-Auswahl für den Reisegrund an:

| UI-Label       | API-Wert | Bedeutung                             |
| -------------- | -------- | ------------------------------------- |
| `Privat`       | `0`      | Standardwert für private Fahrten      |
| `Geschäftlich` | `1`      | Dienstfahrten                         |
| `Arbeitsweg`   | `2`      | Weg zwischen Wohnort und Arbeitsplatz |

`CheckInViewModel` hält den Wert im `CheckInUiState.travelReason` und übergibt ihn beim Absenden an `CheckInRequest.business`.

Die Reisegrund-Auswahl nutzt `FilterChip`-Elemente statt eines Dropdowns, damit die drei Optionen direkt sichtbar und auf mobilen Geräten schneller erreichbar sind.

Lade-, Fehler- und Empty-States im Check-in verwenden `StateMessage`, um dieselbe visuelle Zustandsdarstellung wie Feed und StatusDetail zu nutzen.

## Abhängigkeiten

- **TraewellingApiService**: Zum Abfragen der nötigen Daten und Absenden des Check-ins.
- **FusedLocationProviderClient**: Wird genutzt, um GPS-Koordinaten für die "In der Nähe" Suche zu generieren.
- **StateMessage**: Einheitliche UI für Lade-, Fehler- und Empty-States.

## Offene Fragen

- TODO: Detailbetrachtung der Deduplizierungslogik bei Haltestellen, da APIs häufig Duplikate (teilweise mit fast identischen Koordinaten und Namen) liefern.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Externe Schnittstellen](../api/externe-schnittstellen.md)
- [Datenmodell](../daten/datenmodell.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
