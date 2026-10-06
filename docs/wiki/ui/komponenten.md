# UI: Komponenten

## Zweck

Dokumentation wiederverwendbarer UI-Komponenten.

## Wichtige Komponenten

### TraewellingTopAppBar

Gradient-TopAppBar mit der aktuellen Material-Primaryfarbe.

```kotlin
@Composable
fun TraewellingTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
)
```

Hintergrund: Horizontaler Gradient von `MaterialTheme.colorScheme.primary` zu derselben Farbe mit Alpha 0,8. Das helle Theme verwendet DeepIndigo; Dark/AMOLED verwenden IndigoLight. Der Verlauf passt sich daher dem eingestellten Theme an.

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

`onLike` ist nullable: ohne Handler oder bei `status.isLikable = false` ist die Herz-Aktion deaktiviert. Feedkarten besitzen einen Handler; eigene und fremde Profilkarten übergeben ausdrücklich `null`.

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

### Beschriftete Einstellungen

`SettingsToggle` verbindet Titel, Beschreibung, Schalterzustand und genau eine Aktion in der gesamten Zeile (`Role.Switch`, mindestens 48 dp). Die innere Switchanzeige besitzt keinen eigenen Handler. Auswahlfelder tragen ihre Titel direkt als Textfeldlabel. Der Setup-Tokenbutton nennt abhängig vom Zustand `Token anzeigen` oder `Token verbergen` aus Stringressourcen. Dies ist eine Quell-/Semantikkorrektur; instrumentierte und TalkBack-Prüfung bleiben offen.

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
