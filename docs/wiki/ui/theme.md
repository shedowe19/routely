# UI: Theme

## Zweck

Dokumentation des Farbschemas und der Typografie.

## Markenfarben

| Name               | Hex     | Verwendung            |
| ------------------ | ------- | --------------------- |
| TraewellingRed     | #C72730 | Logo, primäre Akzente |
| TraewellingRedDark | #A51F27 | Dunklere Variante     |

## Erweiterte Palette

### Primär (Deep Indigo)

- DeepIndigo: #1A237E (helle Primaryfarbe; Dark-/AMOLED-Primarycontainer)
- IndigoLight: #534BAE (heller Primarycontainer; Dark-/AMOLED-Primaryfarbe)

### Sekundär (Teal)

- TealAccent: #00897B (Fortschritt, Start/End-Badges)
- TealLight: #B2DFDB (Container)
- TealDark: #00695C (auf Container)

### Tertiär (Amber)

- AmberAccent: #FF8F00 (persönliche Marker)
- AmberLight: #FFF8E1 (Container)
- AmberDark: #E65100 (auf Container)

### Semantische Farben

- SuccessGreen: #2E7D32 (pünktlich/früh)
- WarningOrange: #E65100 (Verspätung)
- ErrorRed: #C62828 (Ausfall/Fehler)

## Material3 ColorSchemes

Das Theme unterstützt nun mehrere Modi (Light, Dark, AMOLED), die reaktiv basierend auf der Nutzereinstellung über den `PreferencesManager` und `SettingsViewModel` geladen werden.

```kotlin
private val LightColorScheme = lightColorScheme(
    primary = DeepIndigo,
    secondary = TealAccent,
    tertiary = AmberAccent,
    error = ErrorRed,
    background = SurfaceBlue (#FAFBFF),
    surface = SurfaceCard (#FFFFFF)
)

private val DarkColorScheme = darkColorScheme(
    primary = IndigoLight,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E)
)

private val AmoledColorScheme = darkColorScheme(
    primary = IndigoLight,
    background = Color(0xFF000000), // Tiefes Schwarz für OLED
    surface = Color(0xFF000000)
)
```

## Prüfgrenze für Textakzente

TODO: Kleine Primaryfarbtexte auf dunklen Grundflächen visuell und mit zugänglicher Schrift prüfen. IndigoLight auf Surface `#1E1E1E` ergibt rechnerisch 2,370:1, auf AMOLED-Schwarz 2,985:1. Das ist eine Palettenberechnung, keine Gerätebildschirmmessung. Die Semantiklücken U7 sind gemäß [Nachreview-Korrektur](../entwicklung/main-review-2026-10-06.md) behoben; die separate Farbprüfung bleibt offen.

## Typografie

Standard Material3 Typography (Roboto).

## TransportColors

Farben für Verkehrsmittel-Kategorien (siehe [Komponenten](./komponenten.md)).

## Flutter-Migrationsstand vom 07.10.2026

Die plattformübergreifende Anwendung liegt unter `flutter/`; der bisherige Kotlin-/Compose-Quellstand unter `app/` bleibt eine Verhaltensreferenz. Aktuelle Schichten, Funktionsvergleich und Plattformgrenzen stehen in der [Flutter-Architektur](../architektur/flutter-migration.md), Werkzeugketten und Releasepfade unter [Flutter-Entwicklung](../entwicklung/flutter.md). Die übrigen Kotlin-Dateipfade auf dieser Seite beschreiben den erhaltenen Ausgangsstand.

## Verwandte Seiten

- [Komponenten](./komponenten.md)
