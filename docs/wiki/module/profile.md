# Modul: Profile

## Zweck

Eigenes Profil mit Statistiken, letzten Fahrten, Einstellungen-Einstieg und Logout-Funktionalität.

## Kontext

Der Profile-Tab zeigt nach dem Login die eigenen Nutzerdaten, Statistiken (Fahrten, Distanz, Zeit), letzte Fahrten und Aktionen für Einstellungen und Logout. Die Detailkonfiguration für Theme und Text-to-Speech liegt im `SettingsScreen`.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/ProfileScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/ProfileViewModel.kt`

## Verhalten

### Lade-Prozess

1. `loadProfile()` lädt den User und anschließend die Statistiken sequenziell über `repo.getCurrentUser()` und `repo.getStatistics()`.
2. Nach erfolgreicher User-Antwort werden die letzten Fahrten über `repo.getUserStatuses()` geladen; erst danach werden die geladenen Statistiken in den UI-Zustand übernommen.

### Einstellungen-Einstieg

Der `ProfileScreen` enthält einen Button zum `SettingsScreen`. Dort werden Theme und TTS konfiguriert.

### Statistiken

Zeigt Fahrten (letzte 28 Tage) nach Verkehrsmittel kategorisiert:

- Kategorien wie ICE, IC, RE, RB, S-Bahn, etc.
- Jeweils Anzahl und Dauer

### UI-Darstellung

- Profilkopf als große Gradient-Hero-Card mit Avatar, Benutzername, Bio und Statistik-Chips
- Statistik-Chips zeigen Distanz, Zeit und Punkte kompakt einzeilig; Kilometer werden mit deutschem Tausenderpunkt formatiert
- Lade- und Fehlerzustände via `StateMessage`
- Letzte Fahrten werden über `StatusCard` dargestellt. Der Herz-Handler ist hier leer (`onLike = {}`); Tippen führt keinen Like-Aufruf aus.

## UI-Zustand (ProfileUiState)

| Feld                                   | Typ             | Beschreibung               |
| -------------------------------------- | --------------- | -------------------------- |
| `user`                                 | User?           | Eigene Nutzerdaten         |
| `statistics`                           | StatisticsData? | Fahrten-Statistiken        |
| `recentStatuses`                       | List<Status>    | Letzte Check-ins           |
| `isLoading`                            | Boolean         | Ladezustand                |
| `error`                                | String?         | Profil-Ladefehler          |

TTS-Zustand liegt im `SettingsViewModel`, nicht im `ProfileUiState`.

## Abhängigkeiten

- **TraewellingRepository**: getCurrentUser, getStatistics, getUserStatuses
- **SettingsScreen**: Ziel für globale Theme- und TTS-Konfiguration

## Offene Fragen

- TODO: Sichtbaren Herz-Button der Fahrtkarten mit Like/Unlike verbinden oder die Aktion in dieser Ansicht deaktivieren.

## Verwandte Seiten

- [TripTracking](./trip-tracking.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Settings](./settings.md)
- [Offene Fragen](../offene-fragen.md)
