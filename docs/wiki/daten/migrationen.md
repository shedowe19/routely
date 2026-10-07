# Daten: Migrationen

## Zweck

Datenbank-Migrationen.

## Status

Zurzeit wird `fallbackToDestructiveMigration()` in `AppDatabase` verwendet. Keine manuellen SQL-Migrationen (`Migration(1, 2)`) vorhanden.

## API-Modellmigration vom 05.10.2026

Die Träwelling-Anpassung ändert die JSON-Modelle, aber nicht die Room-Tabelle `feed_statuses` oder deren Datenbankversion. `statusJson` bleibt ein Gson-serialisierter Status.

Alte Cache-Einträge ohne verschachteltes `station`-Objekt können ihren früheren Halt-Namen weiterhin anzeigen. Sie liefern jedoch keine `stationId` aus dem alten Stopover-`id`: Dieses Feld wird serverseitig umgewidmet und ist keine sichere Stationsreferenz. Aktuelle Stationsdaten werden bei der nächsten erfolgreichen API-Aktualisierung neu geladen.

## Getrennter Feedcache im Main-Audit vom 06.10.2026

Room wird von Version 1 auf 2 angehoben. `StatusEntity` verwendet nun `(id, type)` als Primärschlüssel; `type` trennt Feedart und einen Digest der Server-/Tokenzuordnung. Ein Status kann damit gleichzeitig in mehreren Feedpartitionen liegen. Es gibt keine SQL-Migration: `fallbackToDestructiveMigration()` verwirft den alten Feedcache, der bei der nächsten erfolgreichen Antwort neu entsteht. DataStore-Einstellungen und der aktive Fahrtcache werden durch diesen Room-Versionswechsel nicht migriert oder gelöscht. Details: [Schemas](./schemas.md) und [Feed](../module/feed.md).

## SEV-Fahrtcache vom 06.10.2026

Die [SEV-Ergänzung](../module/sev-haltestellen.md) ändert keine Room-Tabelle und keine Datenbankversion. Im vorhandenen Version-1-DataStore-JSON sind `fullStopovers` und `sevMaps` neu und optional. Der Service liest alte Einträge mit der vorhandenen Haltfolge als Richtungskontext und einer leeren SEV-Quelle. Neue Einträge bewahren öffentliche Karten samt Abrufzeit; Alter, Maßnahme und Richtung werden nach Wiederanlauf neu geprüft. Eine SQL-Migration oder neue Preference ist dafür nicht erforderlich.

## Native Geometrie und Bibliotheksstand vom 06.10.2026

Die zusätzliche Bahn-/Tram-Geometrie bleibt RAM-Zustand und verlangt kein neues Room-/DataStore-Feld. Das aktive Fahrtcache-JSON bleibt Version 1, Room bleibt Version 2. Auch das Bibliotheksupdate ist kein Grund, eine andere Schemaänderung zu erfinden; die bestehenden Feedcache-Upgraderegeln gelten weiter. Der vorhandene Fahrtcache liefert nur die Haltbasis für erneutes Geometrieladen. Details: [GPS-Zeiten](../module/gps-zeiten.md) und [Build](../entwicklung/build.md).

## Flutter-Migrationsstand vom 07.10.2026

Die plattformübergreifende Anwendung liegt unter `flutter/`; der bisherige Kotlin-/Compose-Quellstand unter `app/` bleibt eine Verhaltensreferenz. Aktuelle Schichten, Funktionsvergleich und Plattformgrenzen stehen in der [Flutter-Architektur](../architektur/flutter-migration.md), Werkzeugketten und Releasepfade unter [Flutter-Entwicklung](../entwicklung/flutter.md). Die übrigen Kotlin-Dateipfade auf dieser Seite beschreiben den erhaltenen Ausgangsstand.

## Verwandte Seiten

- [Datenbank](./datenbank.md)
- [Datenmodell](./datenmodell.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
