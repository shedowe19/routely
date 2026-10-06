# Modul: Profile

## Zweck

Eigenes Profil mit Statistiken, letzten Fahrten, Einstellungen-Einstieg und Logout-Funktionalität.

## Kontext

Der Profile-Tab zeigt nach dem Login die eigenen Nutzerdaten, Statistiken (Fahrten, Distanz, Zeit), letzte Fahrten und Aktionen für Einstellungen und Logout. Die Detailkonfiguration für Theme und Text-to-Speech liegt im `SettingsScreen`.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/ProfileScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/ProfileViewModel.kt`

## Verhalten

Profil-Refresh ersetzt den bisherigen Ladeauftrag und erhöht eine Generation. Späte Profil-, Statistik- oder Historienantworten dürfen nur in die weiterhin aktuelle Ladung übernommen werden. Ein Fehler beim eigenen Profil beendet den Durchlauf mit Hinweis; fehlgeschlagene Statistik-/Historienteile erhalten vorhandene passende Daten und zeigen den Teilfehler, statt ihn zu verschweigen. Fahrten werden nach Status-ID dedupliziert. Konto-/Sitzungswechsel löscht das gesamte Feature-ViewModel über den [Auth-Store](./auth.md).

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
- Letzte Fahrten werden über `StatusCard` dargestellt. Das Profil übergibt `onLike = null`; die Herz-Aktion ist dadurch ausdrücklich deaktiviert. Like/Unlike ist derzeit im Feed verfügbar.

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

- Fehler beim Aktualisieren bleiben auch mit vorhandenen Profilinhalten sichtbar. Die deaktivierte Herz-Aktion ist eine bewusst angezeigte Funktionsgrenze, kein leerer Klickhandler.

## Verwandte Seiten

- [TripTracking](./trip-tracking.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [Settings](./settings.md)
- [Offene Fragen](../offene-fragen.md)
