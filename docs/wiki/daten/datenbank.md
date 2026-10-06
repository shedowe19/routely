# Daten: Datenbank

## Zweck

Lokale Speicherung.

## Room

Die App nutzt `androidx.room`. Die Klasse `AppDatabase.kt` stellt die Datenbank (`traewelling_database`) bereit, Version 2.
Zurzeit gibt es einen `StatusDao` für `StatusEntity`. Die Methode `fallbackToDestructiveMigration()` ist bei Versionsupgrades aktiv, was bedeutet, Cache-Daten können bei Schema-Änderungen gelöscht werden.

`feed_statuses` hat einen zusammengesetzten Primärschlüssel aus Status-ID und Feedpartition. Persönlicher und globaler Feed sowie verschiedene Server-/Tokenzuordnungen überschreiben einander dadurch nicht. Seite 1 einer Partition wird mit `replaceStatuses` transaktional ersetzt. Dieser Feedcache ist unabhängig vom aktiven Fahrtcache in DataStore und wird beim Versionswechsel neu aufgebaut.

Die Spalte `position` erhält die Reihenfolge der erfolgreichen API-Seite. Offline wird nicht nach Status-ID neu sortiert; ein später erstellter Check-in kann eine frühere Abfahrt betreffen.

## Verwandte Seiten

- [Migrationen](./migrationen.md)
- [Schemas](./schemas.md)
- [Feed](../module/feed.md)
