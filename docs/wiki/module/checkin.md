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

3. **Zielauswahl (Trip Detail):**
   - Wählt der Nutzer eine Abfahrt, muss der Zielbahnhof bestimmt werden.
   - Dazu wird die gesamte Route der Fahrt geladen (`GET /api/v1/trains/trip` mit `hafasTripId` und `lineName`).
   - Die App zeigt die Liste der kommenden Haltestellen an.

4. **Der eigentliche Check-in:**
   - Wenn Start, Fahrt und Ziel bekannt sind, wird der Check-in durchgeführt.
   - `POST /api/v1/trains/checkin` mit `CheckInRequest` (Start, Ziel, Fahrt-ID, Reisegrund, etc.).
   - Der Reisegrund wird über das API-Feld `business` gesendet. Die App verwendet `TravelReason.PRIVATE` (`0`) als Standard und erlaubt die Auswahl von `BUSINESS` (`1`) und `COMMUTE` (`2`).
   - Manuelle Verspätungs-Overrides (`manualDeparture`, `manualArrival`), die die API zurückgibt, müssen direkt ins Datenmodell gemerged werden, um UI-Flackern zu vermeiden.

## Reisegrund

Im Bestätigungsschritt zeigt `CheckInScreen` eine Auswahl für den Reisegrund an:

| UI-Label       | API-Wert | Bedeutung                             |
| -------------- | -------- | ------------------------------------- |
| `Privat`       | `0`      | Standardwert für private Fahrten      |
| `Geschäftlich` | `1`      | Dienstfahrten                         |
| `Arbeitsweg`   | `2`      | Weg zwischen Wohnort und Arbeitsplatz |

`CheckInViewModel` hält den Wert im `CheckInUiState.travelReason` und übergibt ihn beim Absenden an `CheckInRequest.business`.

## Abhängigkeiten

- **TraewellingApiService**: Zum Abfragen der nötigen Daten und Absenden des Check-ins.
- **FusedLocationProviderClient**: Wird genutzt, um GPS-Koordinaten für die "In der Nähe" Suche zu generieren.

## Offene Fragen

- TODO: Detailbetrachtung der Deduplizierungslogik bei Haltestellen, da APIs häufig Duplikate (teilweise mit fast identischen Koordinaten und Namen) liefern.

## Verwandte Seiten

- [API Überblick](../api/ueberblick.md)
- [Externe Schnittstellen](../api/externe-schnittstellen.md)
- [Datenmodell](../daten/datenmodell.md)
