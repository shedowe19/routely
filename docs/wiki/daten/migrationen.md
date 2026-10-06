# Daten: Migrationen

## Zweck

Datenbank-Migrationen.

## Status

Zurzeit wird `fallbackToDestructiveMigration()` in `AppDatabase` verwendet. Keine manuellen SQL-Migrationen (`Migration(1, 2)`) vorhanden.

## API-Modellmigration vom 05.10.2026

Die Träwelling-Anpassung ändert die JSON-Modelle, aber nicht die Room-Tabelle `feed_statuses` oder deren Datenbankversion. `statusJson` bleibt ein Gson-serialisierter Status.

Alte Cache-Einträge ohne verschachteltes `station`-Objekt können ihren früheren Halt-Namen weiterhin anzeigen. Sie liefern jedoch keine `stationId` aus dem alten Stopover-`id`: Dieses Feld wird serverseitig umgewidmet und ist keine sichere Stationsreferenz. Aktuelle Stationsdaten werden bei der nächsten erfolgreichen API-Aktualisierung neu geladen.

## SEV-Fahrtcache vom 06.10.2026

Die [SEV-Ergänzung](../module/sev-haltestellen.md) ändert keine Room-Tabelle und keine Datenbankversion. Im vorhandenen Version-1-DataStore-JSON sind `fullStopovers` und `sevMaps` neu und optional. Der Service liest alte Einträge mit der vorhandenen Haltfolge als Richtungskontext und einer leeren SEV-Quelle. Neue Einträge bewahren öffentliche Karten samt Abrufzeit; Alter, Maßnahme und Richtung werden nach Wiederanlauf neu geprüft. Eine SQL-Migration oder neue Preference ist dafür nicht erforderlich.

## Verwandte Seiten

- [Datenbank](./datenbank.md)
- [Datenmodell](./datenmodell.md)
- [Träwelling-API-Kompatibilität](../api/traewelling-kompatibilitaet.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
