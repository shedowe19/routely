# Daten: Schemas

## Zweck

Beschreibung der lokalen Entity Schemas.

## StatusEntity

```kotlin
@Entity(tableName = "feed_statuses", primaryKeys = ["id", "type"])
data class StatusEntity(
    val id: Int,
    val statusJson: String,
    val type: String, // Feedart + Digest der Server-/Tokenzuordnung
    val position: Int = 0 // ursprüngliche Reihenfolge der API-Seite
)
```

**Tabelle:** `feed_statuses`

| Spalte       | Typ      | Beschreibung                           |
| ------------ | -------- | -------------------------------------- |
| `id`         | Int (Teil des PK) | Status-ID von der API                  |
| `statusJson` | String   | Serialisiertes Status-Objekt (Gson)    |
| `type`       | String (Teil des PK) | `dashboard:<digest>` oder `global:<digest>`; Digest aus Server und Token |
| `position` | Int | Index innerhalb der erfolgreich gespeicherten ersten API-Seite |

Der SHA-256-Digest partitioniert den Cache, ohne den Bearer-Token als Cachekey zu speichern. Er ist keine Verschlüsselung der enthaltenen Statusdaten. Alte unpartitionierte Typen `dashboard`/`global` werden nicht als kontobezogener Rückfall gelesen. Die Schemaänderung verwendet Datenbankversion 2 mit destruktivem Neuaufbau des Feedcaches; der Version-1-Fahrtcache in DataStore bleibt davon getrennt.

`StatusDao` liest nach `position ASC, id DESC`; das Repository speichert die ursprüngliche API-Reihenfolge mit `mapIndexed`. Das Dashboard sortiert nach Abfahrt, nicht nach Status-ID oder Erstellungsfolge. Offline wird deshalb die gespeicherte Reihenfolge erhalten.

## Aktiver Fahrtcache in DataStore

`trip_tracking_state` ist ein Gson-JSON mit `CachedTripTrackingState`, keine Room-Entity. Das Format bleibt Version 1. Es enthält aktive Status-ID, Check-in, eingegrenzte Haltfolge, Besuchs-/Ansagefortschritt sowie bereits vorhandene Änderungs- und Live-Anzeigemetadaten. Die [SEV-Ergänzung](../module/sev-haltestellen.md) fügt zwei optionale Felder hinzu:

| Feld | Typ | Bedeutung |
| --- | --- | --- |
| `fullStopovers` | List<StopStation>? | Vollständige API-Haltfolge als Richtungskontext, auch nach dem eigenen Ausstieg. Alte Einträge verwenden ersatzweise die vorhandene Haltfolge. |
| `sevMaps` | Map<String, SevMap>? | Öffentliche Karten je Bahnhofsslug mit SEV-Punkten, Hinweisen und Abrufzeit. Fehlendes Feld bedeutet keine SEV-Quelle. |

Das Wiederherstellen vertraut nicht unmittelbar auf eine frühere Auflösung: Quellenalter, Maßnahmendatum und Richtung werden erneut geprüft. Gerätepositionen, Bewegungshistorie und GPS-Zeitprognosen werden nicht in diesen Feldern gespeichert. Details zum Schreiben/Löschen: [PreferencesManager](../konfiguration/preferences-manager.md).

Native Träwelling-Linienzüge und SEV-Straßen-Geometrien sind keine zusätzlichen Schemafelder. Beide bleiben im RAM; die restaurierte Haltfolge bildet bei sichtbarem Wiederanlauf nur die Basis für neue passende Geometrieabrufe. Quelle und Lebensdauer stehen unter [GPS-Zeiten](../module/gps-zeiten.md).

## Verwandte Seiten

- [Datenbank](./datenbank.md)
- [Datenmodell](./datenmodell.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
