# Daten: Datenbank

## Zweck

Lokale Speicherung.

## Room

Die App nutzt `androidx.room`. Die Klasse `AppDatabase.kt` stellt die Datenbank (`traewelling_database`) bereit, version 1.
Zurzeit gibt es einen `StatusDao` für `StatusEntity`. Die Methode `fallbackToDestructiveMigration()` ist bei Versionsupgrades aktiv, was bedeutet, Cache-Daten können bei Schema-Änderungen gelöscht werden.

## Verwandte Seiten

- [Migrationen](./migrationen.md)
