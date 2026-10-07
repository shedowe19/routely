# Settings Modul

## Zweck

Dieses Modul verwaltet Theme (Hell, Dunkel, AMOLED), GPS-Stationsalarme, Fahrterkennung, Fahrtänderungen, Live-Fortschritt, Sperrbildschirmdetails und Sprachausgabe (TTS). Es zeigt außerdem den aktuellen Android-Akkuoptimierungsstatus und bietet eine ausdrückliche Freigabeaktion für die Begleitung bei ausgeschaltetem Display.

## Kontext

Der SettingsScreen wird über den `ProfileScreen` aufgerufen. Die hier getroffenen Einstellungen wirken sich global auf die App aus (z.B. durch reaktives Theming auf Root-Ebene in der `MainActivity`).

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/SettingsScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/SettingsViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/util/PreferencesManager.kt`
- `app/src/main/kotlin/de/traewelling/app/util/BatteryOptimization.kt`
- `app/src/main/kotlin/de/traewelling/app/MainActivity.kt`

## Verhalten

Das `SettingsViewModel` liest und schreibt Präferenzen asynchron mittels des `PreferencesManager` (welcher Android DataStore verwendet). Änderungen, wie z.B. das App-Theme, werden als StateFlow bereitgestellt, wodurch die UI (wie das `TraewellingTheme`) automatisch auf Änderungen reagiert und sich neu zeichnet.

### UI-Darstellung

- Nutzt `TraewellingTopAppBar` für konsistente Routely-Navigation
- Theme-Auswahl wird als direkte `FilterChip`-Auswahl (`Hell`, `Dunkel`, `AMOLED`) dargestellt
- Einstellungsbereiche werden als abgerundete Cards mit Icon, Beschreibung und dezentem Border angezeigt
- TTS-Optionen bleiben als Dropdowns verfügbar, sobald Haltestellenansagen aktiviert sind

TTS-Aktivierung, Engine, Sprache und Stimme werden als gemeinsame Konfiguration verarbeitet. Initialisierung und Enginewechsel tragen eine Generation; späte Callbacks einer verworfenen Engine dürfen keine neue Auswahl überschreiben. `null` bezeichnet die Android-Standardengine und ist ein gültiger Initialisierungszustand. Verfügbare Sprachen kommen aus `TextToSpeech.availableLanguages`, Stimmen aus der gewählten Engine; es wird nicht jede mögliche Java-Locale einzeln über Binder geprüft. Ausschalten und ViewModel-Ende geben die Testinstanz frei. Der aktive Tracking-Service beobachtet dieselbe Konfiguration unabhängig, erneuert seine eigene Sprachengine bei Engine-/Enablewechsel und übernimmt Sprach-/Stimmänderungen. Initialisierungsfrist, begrenzte Wiederholung und aktuelle Ansagerelevanz beschreibt [TripTracking](./trip-tracking.md).

Das Manifest deklariert `android.intent.action.TTS_SERVICE` unter `<queries>`, damit installierte Sprachengines bei eingeschränkter Paketsichtbarkeit auffindbar bleiben. Dies ist keine zusätzliche Berechtigung. Der [Android-Vertrag zur Paketsichtbarkeit](https://developer.android.com/training/package-visibility/use-cases) beschreibt diese TTS-Service-Abfrage.

### Stationsansagen mit GPS

Der Bereich `Stationsansagen mit GPS` bietet `GPS verwenden` und die Entfernung für die Ansage. Standard ist GPS mit automatischem Radius von 300–2.000 Metern; alternativ sind 300, 500, 1.000 oder 2.000 Meter fest wählbar. `SettingsViewModel` schreibt `gps_tracking_enabled` und `announcement_radius_meters` über DataStore.

`Standort freigeben` übergibt die Freigabeaktion an `MainActivity`. Dort werden präziser Standort und aktivierte Ortungsdienste geprüft; bei ausgeschalteter Ortung wird die Android-Standorteinstellung geöffnet. Bei vorheriger dauerhafter Ablehnung öffnet der manuelle Button die App-Berechtigungen. Der GPS-Schalter ersetzt die Runtimefreigabe nicht. Ohne nutzbaren Standort wird der gekennzeichnete Fahrplan-Rückfall verwendet.

Die Sprachausgabe benötigt weiterhin die separate Option `Haltestellen ansagen`. GPS-Tracking und TTS-Aktivierung sind getrennte Einstellungen. Das genaue Trigger- und Rückfallverhalten beschreibt [TripTracking](./trip-tracking.md).

### Begleitung bei ausgeschaltetem Display

Die gleichnamige Einstellungs-Card liest `PowerManager.isIgnoringBatteryOptimizations(packageName)` und zeigt `Von Android-Akkuoptimierung ausgenommen`, `Android-Akkuoptimierung aktiv` oder einen nicht verfügbaren Status. Der Systemwert wird beim Einfügen der Card und bei `Lifecycle.Event.ON_RESUME` neu gelesen; dadurch ist eine Ablehnung oder spätere Änderung in Android nach der Rückkehr sichtbar. Es gibt keinen DataStore-Key oder Schalter, der eine Ausnahme behauptet.

`Akkuoptimierung aufheben` erscheint, solange keine Ausnahme bestätigt ist. Erst dieser ausdrückliche Nutzertipp öffnet über `BatteryOptimization.requestExemption` `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` mit der `package:`-URI der App. Routely nimmt keine Ausnahme automatisch vor. Bei fehlender Activity oder verweigertem Start folgen die allgemeine Akkuoptimierungsliste und die Android-App-Detailseite. Ein erfolgreich geöffneter Dialog zählt noch nicht als Freigabe; entscheidend bleibt die erneute Abfrage des Systemwerts.

Der separate Button `Android-App-Einstellungen` öffnet die App-Detailseite mit Rückfall auf die allgemeinen Android-Einstellungen. Sind alle passenden Starts erfolglos, zeigt die Card eine Anleitung zum manuellen Öffnen. Auf Samsung-Geräten nennt sie zusätzlich `Uneingeschränkt` und die Liste der Apps, die nie im Standby sind; Menübezeichnungen unterscheiden sich je Systemversion. Eine Android-Doze-Ausnahme ersetzt zusätzliche Herstellerbeschränkungen nicht.

Die aktive Fahrt verwendet eine begrenzte CPU-WakeLock-Haltung im [Tracking-Service](./trip-tracking.md). Das Display wird nicht eingeschaltet. Diese Absicherung kann mehr Akku verbrauchen; sie garantiert weder feste GPS-Intervalle noch Weiterbetrieb nach Nutzer-Force-Stop, Systemprozessende oder eingeschränkter TTS-Engine. Die manuelle [Display-aus-/Doze-Prüfung](../entwicklung/tests.md) bleibt erforderlich.

Systemgrundlagen: [Android Doze und App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby). Zusätzliche Samsung-Hintergrundregeln beschreibt die [offizielle Anleitung zu schlafenden Apps](https://www.samsung.com/us/support/answer/ANS10003442/); sie ist kein Nachweis eines festen Menüpfads auf One UI 9.

### Reisebegleitung

- **Fahrten automatisch erkennen:** Standardmäßig aus. Aktivierung erfolgt über `MainActivity` mit präziser Freigabe und startet einen sichtbaren Location-Service; es werden mögliche Fahrten vorgeschlagen, keine automatischen Check-ins erstellt. Die UI erklärt die Standortübertragung an den konfigurierten Träwelling-Server. Technisch werden aus dem Standort abgeleitete Bounding-Box-Grenzen an `GET /api/v1/stations` gesendet.
- **Änderungen erklären:** Standardmäßig an. Meldet bekannte Änderungen der eigenen aktiven Fahrt. Der zusätzliche Schalter für Änderungsansagen ist nur bei aktivierten Hinweisen bedienbar; tatsächliche Sprache setzt ebenfalls die globale TTS-Option voraus.
- **Reisefortschritt anzeigen:** Standardmäßig an. Aktiviert den Haltefortschritt und auf geeigneten Android-Systemen die Anfrage nach Live-Update-Hervorhebung.
- **Reisedetails auf dem Sperrbildschirm:** Standardmäßig an. Ausschalten verwendet eine allgemeine öffentliche Ersatzanzeige und verhindert die Live-Update-Hervorhebungsanfrage. Android-Einstellungen gelten zusätzlich.

Die fünf DataStore-Keys und Standardwerte stehen im [PreferencesManager](../konfiguration/preferences-manager.md).

`Android-Anzeigeeinstellungen` öffnet über `MainActivity` ab API 36 die systemspezifische Freigabe über `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` mit `Settings.EXTRA_APP_PACKAGE`. Der Start wird direkt versucht, damit eingeschränkte Paketsichtbarkeit nicht fälschlich auf eine fehlende Activity schließen lässt. Bei `ActivityNotFoundException` folgt zunächst die allgemeine App-Benachrichtigungseinstellung und danach die App-Detailseite. Auf älteren Geräten beginnt diese Rückfallfolge bei der allgemeinen Benachrichtigungseinstellung. Eine Freigabe allein garantiert keine Hervorhebung durch das System.

### TTS-Wiederanlauf und zugängliche Aktionen

- U6/U7 sind korrigiert: `SettingsSpeechInitializer` lädt den installierten TTS-Servicekatalog unabhängig vom Synthesestart. Alternative Engines bleiben nach Fehler oder zehn Sekunden ohne Initantwort auswählbar; ein sichtbarer Hinweis bietet `Wiederholen`. Abschalten, Wechsel und Timeout geben die Instanz frei; eine Generation sperrt alte Callbacks. Synchron eintreffende Initcallbacks werden bis nach Konstruktorabschluss zurückgestellt. Der Sprachtest ist nur bei bereiter Engine ausführbar. Einstellungen und Tracking-Service besitzen weiterhin getrennte Instanzen.
- `SettingsToggle` verbindet Titel, Beschreibung, Zustand und eine einzige `Role.Switch`-Aktion in einer mindestens 48 dp hohen Zeile. Der innere Switch besitzt keinen zweiten Handler. Engine-/Sprach-/Stimm- und Radiusfelder erhalten eigene Labels; der Tokenbutton benennt Anzeigen beziehungsweise Verbergen. TODO: Diese Semantik zusätzlich instrumentiert und mit TalkBack prüfen.

## Abhängigkeiten

- `PreferencesManager` (DataStore)
- `android.speech.tts.TextToSpeech`
- `TraewellingTheme` (für das reaktive Styling)
- Android `PowerManager`, Akkuoptimierungs- und App-Einstellungsintents

## Offene Fragen

- Fehlerbehandlung in PreferencesManager — offen — @dev
- TODO: Auswahl und Wechsel von TTS-Engine, Sprache und Stimme mit den tatsächlich installierten Engines auf einem Gerät prüfen; die Auswahl ist bereits integriert.
- TODO: Freigabe, Ablehnung, spätere Änderung und fehlende System-Activities auf Android/Samsung einschließlich Rückkehr zur Card prüfen.

## Flutter-Umsetzung

`flutter/lib/features/settings/` erhält alle vier Themes, GPS/Erkennung, Ansageradius, installierte Stimmen, Änderungs- und Sperrbildschirmoptionen. Batterie-/Berechtigungseinstellungen werden live vom nativen Host abgefragt. Weitere Details stehen in der [Flutter-Architektur](../architektur/flutter-migration.md). Die bisherigen Kotlin-Verträge bleiben die Verhaltensreferenz.

Die Flutter-Android-Akku- und Mitteilungseinstellungen melden `opened: false`, wenn das System die angefragte Einstellungsansicht nicht öffnen kann. Ein fehlgeschlagener `startActivity` darf weder als erfolgreicher Einstellungswechsel erscheinen noch die App abstürzen lassen.

## Verwandte Seiten

- [Theme Konfiguration](../ui/theme.md)
- [PreferencesManager](../konfiguration/preferences-manager.md)
- [ADR Dark Mode & Settings](../entscheidungen/2026-04-29-dark-mode-und-settings.md)
- [TripTracking](./trip-tracking.md)
- [Fahrterkennung](./ride-recognition.md)
- [Fahrtänderungen](./trip-changes.md)
- [Reisefortschritt](./trip-progress.md)
- [Tests](../entwicklung/tests.md)
