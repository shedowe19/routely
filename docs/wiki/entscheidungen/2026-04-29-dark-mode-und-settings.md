# ADR: Einführung von Dark Mode und Einstellungsmenü

**Datum:** 2026-04-29

## Status

Akzeptiert

## Zweck

Schaffung eines zentralen Einstellungsmenüs zur Entlastung des Profil-Screens und Implementierung eines flexiblen Theming-Systems (Light, Dark, AMOLED) zur Verbesserung der UX.

## Kontext

Die App hatte bisher kein zentrales Einstellungsmenü. Einstellungen, wie die für die Sprachausgabe (TTS), waren stattdessen inline im `ProfileScreen` platziert. Dies führte zu einer Überfrachtung des Profil-Bildschirms und limitierte die Erweiterbarkeit für zukünftige App-Einstellungen.

Gleichzeitig gab es den Bedarf, verschiedene UI-Themes anzubieten (insbesondere Dark Mode und einen reinen AMOLED-Modus für OLED-Displays), um die User Experience zu verbessern und die Barrierefreiheit zu erhöhen.

## Entscheidung

Wir haben uns für folgende Architektur- und Design-Anpassungen entschieden:

1.  **Zentrales Einstellungsmenü (`SettingsScreen` & `SettingsViewModel`):**
    Wir haben einen neuen Bereich für Einstellungen geschaffen. Das `SettingsViewModel` übernimmt alle Zustandsverwaltungen für Anwendungspräferenzen. Der TTS-Code wurde aus dem `ProfileViewModel` extrahiert und in das neue ViewModel überführt. Auf dem `ProfileScreen` wurde die TTS-Sektion durch einen Button ersetzt, der zum `SettingsScreen` navigiert.

2.  **App-Theme Präferenz:**
    In `PreferencesManager` wurde ein neuer Key `KEY_APP_THEME` hinzugefügt, der einen String repräsentiert (`"LIGHT"`, `"DARK"`, `"AMOLED"`). Die Flow-Property `appTheme` macht diese Einstellung reaktiv in der gesamten App verfügbar.

3.  **Erweiterung des Theming-Systems (`Theme.kt`):**
    Die Datei `Theme.kt` wurde um zwei neue Farbschemata erweitert: `DarkColorScheme` und `AmoledColorScheme` (welches primär auf tiefem Schwarz als Hintergrund basiert). Die `@Composable TraewellingTheme` Funktion wurde so angepasst, dass sie einen `theme` Parameter (String) akzeptiert und das entsprechende Material 3 `ColorScheme` anwendet.

4.  **Reaktives UI-Update:**
    In der `MainActivity.kt` wird der Theme-State (`appTheme`) via `settingsViewModel` aus dem `PreferencesManager` gelesen. Die `TraewellingTheme`-Komponente umschließt den `NavHost` und reagiert automatisch auf Änderungen dieses States.

## Wichtige Dateien

- `SettingsScreen.kt`: Neue UI für Einstellungen.
- `SettingsViewModel.kt`: Zustandsverwaltung für Einstellungen und TTS.
- `PreferencesManager.kt`: Speicherung des Theme- und TTS-Zustands.
- `Theme.kt`: Erweiterung um Dark- und AMOLED-Farbschemata.
- `MainActivity.kt`: Reagiert auf Theme-Änderungen und wendet diese global an.

## Verhalten

Die App startet standardmäßig im Light Mode. Wenn der Nutzer über die Einstellungen (via Profil-Screen erreichbar) das Theme auf Dark oder AMOLED ändert, wird der `KEY_APP_THEME` im Datastore aktualisiert. Das `SettingsViewModel` propagiert diese Änderung als StateFlow zur `MainActivity`, was einen sofortigen Re-Render der gesamten App mit dem neuen Farbschema (DarkColorScheme oder AmoledColorScheme) auslöst. TTS-Einstellungen werden durch eine leere String-Korrektur zu null ebenfalls sicher an das System-Standard-TTS delegiert.

## Abhängigkeiten

- `androidx.datastore:datastore-preferences`: Zur Speicherung der Einstellungen (`PreferencesManager`).
- Android `TextToSpeech` API: Für die Sprachausgabe (TTS).
- Jetpack Compose: Für dynamisches Theming und UI.

## Konsequenzen

- **Positiv:** Der Profil-Screen ist nun aufgeräumter. Das neue Einstellungsmenü bietet einen klaren Ort für zukünftige Konfigurationen. Die Unterstützung von Dark- und AMOLED-Modes erhöht den Komfort, schont die Augen und potenziell den Akku bei OLED-Geräten.
- **Positiv:** Bessere Separation of Concerns, da `ProfileViewModel` nicht mehr für globale Einstellungen wie TTS zuständig ist.
- **Negativ:** Leicht erhöhte Komplexität in `MainActivity` durch das Hinzufügen einer weiteren ViewModel-Abhängigkeit zur Überwachung des Theme-States auf Root-Ebene.

## Offene Fragen

- Weitere Anpassung individueller Farbskalen bei künftigen Features — offen — @dev
- Mögliche automatische Anpassung des Themes nach System-Einstellungen (Day/Night) — offen — @dev

## Verwandte Seiten

- [Theme Konfiguration](../ui/theme.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
