# Lokale Speicherung

Status: active
Last verified: 2026-11-04
Confidence: high

## Zweck

Routely speichert zwei Arten von Daten lokal: einen Feed-Cache in Room und
Session/Einstellungen/Trackingzustand in einem DataStore. Diese Seite
beschreibt Schema, Schlüssel und Aufbewahrung.

## Room

- Datenbank `traewelling_database`, Version 2, `fallbackToDestructiveMigration`.
- Tabelle `feed_statuses`:
  - `id: Int`
  - `statusJson: String` (Gson-serialisierter `Status`)
  - `type: String`
  - `position: Int`
  - Primärschlüssel `(id, type)`
- DAO liest geordnet nach `position ASC, id DESC`; Insert ist `REPLACE`;
  `replaceStatuses` ist transaktional.
- `type` ist `dashboard:<hash>` oder `global:<hash>`, wobei der Hash aus
  Server-URL und Access-Token gebildet wird. Der rohe Token steht nicht im
  Schlüssel; ein Test sichert das ab.

## DataStore

Datei `traewelling_prefs` (aus `PreferenceStorageRegistry`). Auszug der
Schlüsselnamen:

- Session: `server_url`, `access_token`, `refresh_token`, `client_id`,
  `client_secret`, `username`, `auth_session_revision`.
- Fahrt: `active_status_id`, `trip_tracking_state`.
- Verhalten: `gps_tracking_enabled`, `ride_recognition_enabled`,
  `public_rail_realtime_enabled`, `announcement_radius_meters`.
- Darstellung/Ansagen: `app_theme`, `tts_enabled`, `tts_engine`,
  `tts_language`, `tts_voice`, `live_progress_enabled`,
  `lock_screen_details_enabled`, `trip_change_alerts_enabled`,
  `trip_change_speech_enabled`.
- Recovery: `storage_probe_revision`.

Werte werden hier nicht dokumentiert. Es gibt kein
`EncryptedSharedPreferences` und keinen Keystore.

## Aufbewahrung

- Feed-Cache: nur Seite 1, pro Konto/Server partitioniert. Ein Logout löscht
  die Partition nicht; alte Token-Hashes bleiben liegen.
- `trip_tracking_state`: wird bei Fahrtende gelöscht; bei Storage-Fehler
  bewahrt die Recovery ihn auf.
- Gerätepositionen werden nie persistiert. Nur Routenmetadaten und
  Besuchsfortschritt landen in `trip_tracking_state`.
- Ein Storage-Reset löscht Token, aktive Fahrt und Trackingzustand.

## Backup-Ausschluss

`res/xml/backup_rules.xml` und `res/xml/data_extraction_rules.xml` schließen
aus:

- `datastore/` (Dateien und Device-Files),
- die gesamte Datenbank-Domain inklusive Journal/WAL/SHM,
- dieselben Regeln für Cloud-Backup und Gerätetransfer.

Dadurch gelangen keine Tokens oder privaten Feed-Inhalte in ein Backup.

## Gesundheit

Die DataStore-Schicht hat einen expliziten Zustand (`LOADING`, `READY`,
`RETRYING`, `IO_ERROR`, `CORRUPTED`) und verlangt für einen Reset die aktuelle
Fehler-ID. Details: [Storage-Recovery](../modules/storage-recovery.md).

## Risiken

- Der Feed-Cache enthält vollständige Status-JSONs, also auch private Inhalte,
  bis die Partition ersetzt oder die App-Daten gelöscht werden.
- Room nutzt destruktive Migrationen. Ein Schema-Upgrade verwirft den Cache.
- `active_status_id` wird als String gespeichert und als `Int?` gelesen; ein
  unlesbarer Wert darf nicht überschrieben werden.

## Verwandte Seiten

- [Datenzugriff](../modules/datenzugriff.md)
- [Auth und Sitzung](../modules/auth-und-sitzung.md)
- [Sicherheit](../security/sicherheit.md)
- [Index](../index.md)
