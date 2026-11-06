# UI: Theme

## Zweck

Dokumentation des Farbschemas, der Typografie und der Formgebung. Die Werte sind seit dem
Design-Refresh vom 06.11.2026 tokenbasiert; Screens sollen bevorzugt `MaterialTheme.colorScheme`
und `MaterialTheme.typography` verwenden statt eigener Farben.

## Markenfarben

| Name               | Hex     | Verwendung                         |
| ------------------ | ------- | ---------------------------------- |
| TraewellingRed     | #C72730 | Träwelling-Bezug, externe Marke    |
| TraewellingRedDark | #A51F27 | Dunklere Variante                  |

## Erweiterte Palette

Die Legacy-Konstanten bleiben für bestehende Importe erhalten, werden aber in Screens zunehmend
durch Theme-Rollen ersetzt.

### Primär (Indigo)

- `DeepIndigo`: #241A56 (Markenfläche, dunkler Container)
- `IndigoLight`: #B4A6FF (Primary im Dark-/AMOLED-Theme)

### Sekundär (Teal)

- `TealAccent`: #2BB3A0 (Startmarker, Fortschritt)
- `TealLight`: #BFF2E8 (Container)
- `TealDark`: #0B6B5E (Altbestand; auf dunklen Flächen nicht als Text verwenden)

### Tertiär (Amber)

- `AmberAccent`: #FFB74D (Zielmarker, Punkte)
- `AmberLight`: #FFE0B5 (Container)
- `AmberDark`: #8A4F00 (Altbestand)

### Semantische Farben

- `SuccessGreen`: #6FE0A6 (pünktlich/früh; Dark)
- `WarningOrange`: #FFB37A (Verspätung; Dark)
- `ErrorRed`: #FFB4AB (Ausfall/Fehler; Dark)

Auf Theme-Ebene liefert `MaterialTheme.successColor` die passende Success-Farbe für Light
(#1B7F4B) und Dark/AMOLED (#6FE0A6). Warnungen verwenden `colorScheme.tertiary`, Fehler
`colorScheme.error`; Container-Farben kommen aus `tertiaryContainer`, `errorContainer` und
`secondaryContainer`.

## Material3 ColorSchemes

Drei feste Schemata werden über `TraewellingTheme(theme = ...)` ausgewählt:

| Rolle | Light | Dark | AMOLED |
| --- | --- | --- | --- |
| background | #F7F7FC | #0B0D13 | #000000 |
| surface | #FFFFFF | #0F1118 | #000000 |
| surfaceContainerLow | #F5F4FA | #12151E | #0A0C12 |
| surfaceContainer | #EFEDF6 | #171A24 | #10131B |
| surfaceContainerHigh | #E8E5F1 | #1B2030 | #161A24 |
| surfaceContainerHighest | #E1DEEC | #202534 | #1B202D |
| primary | #4B3BB5 | #B4A6FF | #B4A6FF |
| primaryContainer | #E6E0FF | #3A2F73 | #352B69 |
| secondary | #0F7669 | #6FD6C2 | #6FD6C2 |
| tertiary | #8A4F00 | #FFC46B | #FFC46B |
| error | #B3261E | #FFB4AB | #FFB4AB |
| outlineVariant | #D6D2E0 | #313546 | #2A2E3E |

Regeln:

- Karten liegen auf `surfaceContainerLow`, verschachtelte Flächen auf `surfaceContainer`.
- Trennlinien verwenden `outlineVariant` mit reduzierter Alpha.
- Statusleisten-Symbole werden per `SideEffect` passend zur App-Theme-Wahl (nicht zur
  Systemeinstellung) auf hell/dunkel gesetzt.
- AMOLED behält echte schwarze Hintergründe; Karten bleiben über Container-Tonwerte sichtbar.

## Typografie

Eigene Material3-`Typography` auf Basis von Roboto:

| Rolle | Größe/Zeile | Gewicht |
| --- | --- | --- |
| displaySmall | 34/40 | SemiBold |
| headlineSmall | 24/30 | SemiBold |
| titleLarge | 21/27 | SemiBold |
| titleMedium | 16/22 | SemiBold |
| titleSmall | 14/20 | Medium |
| bodyLarge | 16/24 | Normal |
| bodyMedium | 14/20 | Normal |
| bodySmall | 12/16 | Normal |
| labelLarge | 14/20 | Medium |
| labelMedium | 12/16 | Medium |
| labelSmall | 11/14 | Medium |

## Formen

`Shapes`: extraSmall 6 dp, small 10 dp, medium 14 dp, large 18 dp, extraLarge 26 dp.
Karten verwenden 18 dp, Routenpanels 14 dp, Chips/Badges 7–10 dp.

## TransportColors

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

## Prüfgrenze

Die Kontrastwerte wurden visuell auf dem Testgerät (SM-S948B, Android 17, Light/Dark) geprüft.
Eine Messung mit einem Kontrastwerkzeug und eine TalkBack-Prüfung der neuen Flächen stehen
weiterhin aus. Das ist keine amtliche Barrierefreiheitszertifizierung.

## Verwandte Seiten

- [Komponenten](./komponenten.md)
- [Screens](./screens.md)
- [ADR Design-Refresh](../entscheidungen/2026-11-06-design-refresh-klar-und-ruhig.md)
