# Modul: Feed

## Zweck

Zeigt die Timeline der Status-Einträge von abonnierten Nutzern oder global.

## Kontext

Der Feed ist die soziale Hauptkomponente der App nach dem Login.

## Wichtige Dateien

- `app/src/main/kotlin/de/traewelling/app/ui/screens/FeedScreen.kt`
- `app/src/main/kotlin/de/traewelling/app/viewmodel/FeedViewModel.kt`
- `app/src/main/kotlin/de/traewelling/app/data/local/StatusDao.kt`

## Verhalten

Lädt paginierte Listen von Status-Objekten vom Backend. Unterstützt lokale Caching-Strategien über Room (`StatusDao`), um eine flüssige Offline-Erfahrung zu bieten und Ladezeiten zu reduzieren.

Die Feed-Einträge werden über `StatusCard` dargestellt. Die Karte nutzt die Verkehrsmittel-Farbe (`TransportColors`) als Akzent, zeigt die Route in einem getönten Panel und bietet am Ende einen sichtbaren Details-Hinweis. Lade-, Fehler- und Empty-States verwenden `StateMessage`, damit der Feed dieselbe visuelle Zustandsdarstellung wie Check-in und StatusDetail nutzt.

## Abhängigkeiten

- **Room**: Für das Caching der Feed-Daten.
- **TraewellingApiService**: Zum Laden neuer Feed-Seiten (`/api/v1/dashboard`).
- **StateMessage**: Einheitliche UI für Lade-, Fehler- und Empty-States.

## Offene Fragen

- Keine spezifischen aktuell.

## Verwandte Seiten

- [Datenbank](../daten/datenbank.md)
- [Module Übersicht](./README.md)
