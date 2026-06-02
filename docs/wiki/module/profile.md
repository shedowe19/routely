# Modul: Profile

## Zweck

Eigenes Profil mit Statistiken, TTS-Einstellungen und Logout-Funktionalität.

## Kontext

Der Profile-Tab zeigt nach dem Login die eigenen Nutzerdaten, Statistiken (Fahrten, Distanz, Zeit) und Einstellungen für die Text-to-Speech Haltestellenansage.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/ProfileScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/ProfileViewModel.kt`

## Verhalten

### Lade-Prozess

1. `loadProfile()` lädt User, Statistiken und letzte Fahrten parallel
2. Nutzt `repo.getCurrentUser()`, `repo.getStatistics()`, `repo.getUserStatuses()`

### TTS-Einstellungen

Im ProfileScreen können TTS-Einstellungen vorgenommen werden:

- **Engine**: Auswahl der TTS-Engine (z.B. Google, Samsung, etc.)
- **Sprache**: BCP47 Language Tag (z.B. "de-DE")
- **Stimme**: Voice-Name
- **Test**: Probiert die konfigurierte Stimme aus

ProfileViewModel implementiert `TextToSpeech.OnInitListener` für TTS-Management.

### Statistiken

Zeigt Fahrten (letzte 28 Tage) nach Verkehrsmittel kategorisiert:

- Kategorien wie ICE, IC, RE, RB, S-Bahn, etc.
- Jeweils Anzahl und Dauer

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
- **TextToSpeech**: Android TTS für Sprachausgabe-Test

## Offene Fragen

- Keine spezifischen aktuell.

## Verwandte Seiten

- [TripTracking](./trip-tracking.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
