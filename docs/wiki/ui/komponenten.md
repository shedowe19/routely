# UI: Komponenten

## Zweck

Dokumentation wiederverwendbarer UI-Komponenten.

## Wichtige Komponenten

### TraewellingTopAppBar

Kompakte TopAppBar auf einem weichen Indigo-Verlauf. Die Haupt-Tabs zeigen optional eine
Markenmarke; Detail-Screens nutzen den Zurück-Pfeil.

```kotlin
@Composable
fun TraewellingTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    showBrandMark: Boolean = false
)
```

Hintergrund: vertikaler Verlauf von `primaryContainer` (55 % Alpha) nach `background`.
Titel, Icons und Aktionen kommen aus dem ColorScheme und funktionieren dadurch in Light,
Dark und AMOLED. `showBrandMark = true` wird nur im Feed verwendet.

### FeedTypeSwitcher

Segmentierte Umschaltung zwischen `Freunde` und `Global` im Feed. Sitzt in einer
`surfaceContainer`-Pille, aktive Fläche `primaryContainer`, Rollen-Semantik `Role.Tab`.
Ersetzt die frühere `TabRow` mit Icons und spart vertikale Fläche.

### StatusCard

Check-in-Karte für Feed- und Profil-Listen.

Zeigt:

- User-Avatar (Fallback: Initiale auf `primaryContainer`) und Name
- Linien-Badge mit `TransportColors` und passendem Verkehrsmittel-Icon
- Start → Ziel mit Mini-Timeline und **Abfahrts-/Ankunftszeit** (bevorzugt manuelle
  Korrektur, sonst effektive API-Zeit)
- Punkte-Badge
- Status-Text (body)
- Like-Button mit Zähler
- Details-Hinweis als visuelle Affordance für den Status-Detail-Screen

Karten liegen auf `surfaceContainerLow` mit 18-dp-Radius und einer feinen
`outlineVariant`-Kontur; das Routenpanel auf `surfaceContainer`. `onLike` ist nullable:
ohne Handler oder bei `status.isLikable = false` ist die Herz-Aktion deaktiviert.
Feedkarten besitzen einen Handler; eigene und fremde Profilkarten übergeben `null`.

### StateMessage

Einheitliche Darstellung für Lade-, Fehler- und Empty-States in Screens. Der Icon-Kreis ist
60 dp groß, Titel `titleMedium`/SemiBold, Erklärung `bodyMedium` auf `onSurfaceVariant`.
Wird im Feed, Check-in, StatusDetail, Profil, Benutzerprofil, Benutzersuche und
Notifications genutzt.

### StatPill

Kleines Inline-Statistik-Badge, weiterhin für Detail-Metriken und Distanz/Punkte im
Fahrtdetail verwendet.

### NotificationItem

Dichte Meldungszeile statt großer Einzelkarte:

- 38-dp-Icon-Kreis mit typabhängiger Farbe
- Lead, Notice und relative Zeit
- ungelesene Zeile mit dezentem Primary-Tint und Punkt
- optionale Öffnungsaffordance (`ChevronRight`)
- Trenner mit 66-dp-Einzug

### ProfileHeader

Eigener Profilkopf mit 86-dp-Avatar, Name, Handle-Pille und einer gemeinsamen
Statistikfläche (Distanz/Zeit/Punkte) mit vertikalen Trennern. Der Verlauf nutzt
`primaryContainer` nach transparent; der frühere Blau-Türkis-Verlauf entfällt.

### Expertise-Flächen

`SettingsToggle` verbindet Titel, Beschreibung, Schalterzustand und genau eine Aktion in der
gesamten Zeile (`Role.Switch`, mindestens 48 dp). Die innere Switchanzeige besitzt keinen
eigenen Handler. Auswahlfelder tragen ihre Titel direkt als Textfeldlabel. Der
Setup-Tokenbutton nennt abhängig vom Zustand `Token anzeigen` oder `Token verbergen` aus
Stringressourcen. Dies ist eine Quell-/Semantikkorrektur; instrumentierte und
TalkBack-Prüfung bleiben offen.

## Farben (TransportColors)

| Kategorie           | Farbe                |
| ------------------- | -------------------- |
| ICE/NationalExpress | #A31E39 (Wine Red)   |
| IC/National         | #A31E39              |
| RE/RegionalExp      | #14618F (DB Blue)    |
| RB/Regional         | #14618F              |
| S-Bahn              | #2F7D32 (Green)      |
| U-Bahn              | #1E5FA8 (Blue)       |
| Tram                | #C62828 (Red)        |
| Bus                 | #A3156E (Purple)     |
| Ferry               | #0077A8 (Water Blue) |

## Verwandte Seiten

- [Screens](./screens.md)
- [Theme](./theme.md)
- [ADR Design-Refresh](../entscheidungen/2026-11-06-design-refresh-klar-und-ruhig.md)
