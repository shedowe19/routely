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

Lädt Statuslisten vom Backend. Das persönliche Dashboard verwendet numerische Pagination; der aktuelle globale `statuses`-Vertrag ist unpaginiert. Die UI lädt weitere Seiten nur bei geliefertem `links.next`. Room (`StatusDao`) speichert Seite 1 getrennt nach persönlichem/globalem Feed sowie Server-/Tokenzuordnung. Ein SHA-256-Digest im Partitionstyp enthält keinen Klartexttoken. `(id, type)` ist der zusammengesetzte Primärschlüssel; die neue erste Seite ersetzt ihre Partition transaktional, auch bei einer erfolgreichen leeren Liste. Historische unpartitionierte Einträge werden nicht als kontobezogener Rückfall verwendet.

Offline-Rückfall gilt ausschließlich für Seite 1 bei Netzwerkfehlern oder HTTP 408/429/5xx und nur für die weiterhin passende Sitzung. Authfehler, unvollständige Antworten und Coroutine-Abbrüche dürfen keinen früheren privaten Feed wieder anzeigen. Weitere Seiten verwenden keinen Seite-1-Cache; die Cacheantwort hat keine weiteren Paginationlinks. Sitzungsprüfungen vor und nach Netzwerk-/Room-Verarbeitung verwerfen späte Antworten nach einem Konto- oder Revisionswechsel.

Der Cache speichert außerdem den ursprünglichen Seitenindex `position`. Das nach Abfahrt sortierte Dashboard bleibt dadurch offline in derselben Reihenfolge; Status-ID beziehungsweise Erstellungsfolge werden nicht zur Ersatzsortierung.

Die Feed-Einträge werden über `StatusCard` dargestellt. Die Karte nutzt die Verkehrsmittel-Farbe (`TransportColors`) als Akzent, zeigt die Route in einem getönten Panel und bietet am Ende einen sichtbaren Details-Hinweis. Lade-, Fehler- und Empty-States verwenden `StateMessage`, damit der Feed dieselbe visuelle Zustandsdarstellung wie Check-in und StatusDetail nutzt.

Refresh und Feedwechsel ersetzen den bisherigen Ladeauftrag und erhöhen eine Generation; alte Antworten überschreiben den neuen Feed nicht. Pagination läuft nicht gleichzeitig mit Refresh und dedupliziert nach Status-ID. Pro Status ist höchstens ein Like-/Unlike-Auftrag offen. Nicht likbare Status werden übersprungen; bei einem API-Fehler wird nur noch die passende optimistische Änderung zurückgenommen, ohne einen inzwischen aktualisierten Serverwert pauschal zurückzusetzen.

## Abhängigkeiten

- **Room**: Für das Caching der Feed-Daten.
- **TraewellingApiService**: Zum Laden neuer Feed-Seiten (`/api/v1/dashboard`).
- **StateMessage**: Einheitliche UI für Lade-, Fehler- und Empty-States.

## Offene Fragen

- TODO: Nach erfolgreichem Löschen im Fahrtdetail bleiben geladene Karte und Room-Feedcache bis zum Refresh erhalten. [Main-Review](../entwicklung/main-review-2026-10-06.md), U4, beschreibt den fehlenden Mutations-/Invalidierungspfad.
- Keine spezifischen aktuell.

## Verwandte Seiten

- [Datenbank](../daten/datenbank.md)
- [Module Übersicht](./README.md)
