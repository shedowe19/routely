# Modul: StatusDetail

## Zweck

Detaillierte Ansicht eines Check-ins mit Haltestellenverlauf, Live-Tracking und Bearbeitungsfunktion.

## Kontext

Zeigt einen einzelnen Status mit vollem Timeline-Verlauf der Haltestellen. Ermöglicht auch eigenständige Bearbeitung und Löschung.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/StatusDetailScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/StatusDetailViewModel.kt`

## Verhalten

### Lade-Prozess
1. `loadStatusDetail(statusId)` lädt Status-Details
2. Enriches mit `manualDeparture` und `manualArrival` von CheckinInfo
3. Lädt Stopovers via `repo.getStopovers(tripId)`
4. Prüft via `checkIfOwnStatus()` ob eigener Status (für Bearbeiten/Löschen-Buttons)

### Auto-Refresh
Alle 30 Sekunden wird `refreshSilently()` aufgerufen für Live-Delay-Daten. Der aktualisierte Status wird im UIState gespeichert.

### Manuelle Anpassung von Haltestellen-Zeiten
Zeiten einzelner Halte können nun interaktiv über die Liste (durch Klick auf den entsprechenden Eintrag) angepasst werden. Diese manuellen Werte werden im `manualStopoverDepartures`-State des UI festgehalten und überschreiben die Abfahrtszeiten, bevor die Verspätungsvererbung sie auf alle nachfolgenden Stationen interpoliert. Um offline das Interpolieren bei der Eingabe direkt zu aktualisieren und anzuzeigen, ohne das Netzwerk erneut abzufragen, puffert das ViewModel die rohen (von der API gelieferten) Stationsdaten intern.

### Intelligente Verspätungsvererbung und Verspätungsabbau (Delay Recovery)
Vor der Speicherung des Haltestellenverlaufs im `UIState` (sowohl beim Initialladen, beim manuellen Anpassen von Haltestellen-Zeiten, als auch beim Auto-Refresh) durchläuft die Haltestellen-Liste die Methode `propagateDelays()`. Diese Funktion gleicht Plan- und Echtzeitdaten ab und berechnet die aktuelle Verspätung in Minuten. Wenn die API oder manuell geänderte Check-in-Daten eine geringere Verspätung als den erwarteten vererbten Wert melden, wird aus Gründen der logischen Konsistenz der höhere, vererbte Wert verwendet, um unmögliche Ankunftszeiten (wie eine Ankunft vor der vorherigen Abfahrt) zu verhindern. Um Pufferzeiten zu simulieren, baut die App die vererbte Verspätung bei nachfolgenden Stationen anhand einer realistischen Eisenbahn-Pufferformel anteilig ab:
- **Fahrzeitpuffer**: ca. 5% Puffer auf die reine Fahrzeit zwischen zwei Stationen.
- **Haltezeitpuffer**: Überschüssige Standzeiten/Haltezeiten (alles über 1 Minute Mindesthaltezeit) werden ebenfalls mit einem Skalierungsfaktor von 5% in den Verspätungsabbau einbezogen.
Dadurch nimmt die geschätzte Ankunftszeit am Ziel realistisch ab, anstatt den exakt selben Verspätungswert blind bis zur Endstation mitzuschleppen.

### Bearbeitung (nur eigene Statusen)
- `startEditing()`: Setzt Bearbeitungszustand
- `saveStatusEdit()`: Sendet PUT `/api/v1/status/{id}` mit UpdateStatusRequest

### Löschung
- `deleteStatus()`: Sendet DELETE `/api/v1/status/{id}`

### Timeline-Darstellung (StatusDetailScreen)
Die Timeline zeigt:
- Fortschritts-Balken zwischen Haltestellen, weich interpoliert (via `animateFloatAsState` mit LinearEasing)
- Weiche Status-Übergänge zwischen Ladezuständen, Error und Timeline via `AnimatedContent`
- Gestaffelte Fade-in/Slide-in Animationen der Timeline-Einträge via `AnimatedVisibility`
- "LIVE" Badge mit Puls-Animation wenn Status heute ist
- Verspätungs-Badges (grün/rot)
- "HALT ENTFÄLLT" für gestrichene Halte
- "STARTHALTESTELLE", "ENDSTATION" Badges
- "DEIN EINSTIEG", "DEIN ZIEL" (goldene Premium-Badges)

## UI-Zustand (StatusDetailUiState)

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `status` | Status? | Geladener Status |
| `stopovers` | List<StopStation> | Haltestellen-Verlauf |
| `isOwnStatus` | Boolean | Ist eigener Status |
| `isEditing` | Boolean | Bearbeitungsmodus |
| `isDeleting` | Boolean | Löschvorgang |
| `lastUpdated` | Long | Timestamp letzte Aktualisierung |
| `editingStopoverId` | Int? | ID der derzeit bearbeiteten Haltestelle |
| `editingStopoverDeparture` | String | Temporärer ISO-String für die manuelle Abfahrt |
| `manualStopoverDepartures` | Map<Int, String> | Gespeicherte manuelle Abfahrtszeiten pro Haltestellen-ID |

## Abhängigkeiten

- TraewellingRepository (API-Aufrufe, Stopover-Daten)
- PreferencesManager (Benutzer-Einstellungen)
- Compose Animations (`animateFloatAsState`, `AnimatedVisibility`)

## Offene Fragen

- TODO: EditStatusDialog Layout dokumentieren

## Verwandte Seiten

- [Check-in](./checkin.md)
- [API Überblick](../api/ueberblick.md)