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

1. `loadProfile()` lädt User, Statistiken und letzte Fahrten parallel
2. Nutzt `repo.getCurrentUser()`, `repo.getStatistics()`, `repo.getUserStatuses()`

### Einstellungen-Einstieg

Der `ProfileScreen` enthält einen Button zum `SettingsScreen`. Dort werden Theme und TTS konfiguriert.

### Statistiken

Zeigt Fahrten (letzte 28 Tage) nach Verkehrsmittel kategorisiert:

- Kategorien wie ICE, IC, RE, RB, S-Bahn, etc.
- Jeweils Anzahl und Dauer

### UI-Darstellung

- Profilkopf als große Gradient-Hero-Card mit Avatar, Benutzername, Bio und Statistik-Chips
- Lade- und Fehlerzustände via `StateMessage`
- Letzte Fahrten werden weiterhin über `StatusCard` dargestellt

## UI-Zustand (ProfileUiState)

| Feld                                   | Typ             | Beschreibung               |
| -------------------------------------- | --------------- | -------------------------- |
| `user`                                 | User?           | Eigene Nutzerdaten         |
| `statistics`                           | StatisticsData? | Fahrten-Statistiken        |
| `recentStatuses`                       | List<Status>    | Letzte Check-ins           |
| `isTtsEnabled`                         | Boolean         | TTS aktiviert              |
| `selectedTtsEngine/Language/Voice`     | String?         | Gewählte TTS-Einstellungen |
| `availableTtsEngines/Languages/Voices` | List            | Verfügbare Optionen        |

## Abhängigkeiten

- **TraewellingRepository**: getCurrentUser, getStatistics, getUserStatuses
- **SettingsScreen**: Ziel für globale Theme- und TTS-Konfiguration

## Offene Fragen

- Keine spezifischen aktuell.

## Verwandte Seiten

- [TripTracking](./trip-tracking.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
