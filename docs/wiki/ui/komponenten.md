# UI: Komponenten

## Zweck

Dokumentation wiederverwendbarer UI-Komponenten.

## Wichtige Komponenten

### TraewellingTopAppBar

Gradient-TopAppBar mit DeepIndigo Theme.

```kotlin
@Composable
fun TraewellingTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
)
```

Hintergrund: Horizontaler Gradient von DeepIndigo (#1A237E) zu #283593

### StatusCard

Check-in-Karte für Feed-Listen.

Zeigt:

- User-Avatar und Name
- Zug/Linie mit Farbe (`TransportColors`) und farblich getöntem Route-Panel
- Start → Ziel Stationen mit Mini-Timeline
- Punkte-Badge
- Status-Text (body)
- Like-Button mit Zähler
- Details-Hinweis als visuelle Affordance für den Status-Detail-Screen

### StateMessage

Einheitliche Darstellung für Lade-, Fehler- und Empty-States in Screens.

```kotlin
@Composable
fun StateMessage(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    loading: Boolean = false
)
```

Wird unter anderem im Feed, Check-in, StatusDetail, Profil, Benutzerprofil, Benutzersuche und Notifications genutzt, um leere Zustände, Ladezustände und Fehler visuell konsistent darzustellen.

### StatPill

Kleines Inline-Statistik-Badge.

```kotlin
@Composable
fun StatPill(icon: ImageVector, text: String, color: Color)
```

## Farben (TransportColors)

| Kategorie           | Farbe                |
| ------------------- | -------------------- |
| ICE/NationalExpress | #9B1B30 (Wine Red)   |
| IC/National         | #9B1B30              |
| RE/RegionalExp      | #0064B0 (DB Blue)    |
| RB/Regional         | #0064B0              |
| S-Bahn              | #408335 (Green)      |
| U-Bahn              | #0054A6 (Blue)       |
| Tram                | #CE1417 (Red)        |
| Bus                 | #A5107F (Purple)     |
| Ferry               | #009FE3 (Water Blue) |

## Verwandte Seiten

- [Screens](./screens.md)
- [Theme](./theme.md)
