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

## Abhängigkeiten

- **Room**: Für das Caching der Feed-Daten.
- **TraewellingApiService**: Zum Laden neuer Feed-Seiten (`/api/v1/dashboard`).

## Offene Fragen

- Keine spezifischen aktuell.

## Verwandte Seiten

- [Datenbank](../daten/datenbank.md)
- [Module Übersicht](./README.md)
