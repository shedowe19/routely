# Daten: Schemas

## Zweck

Beschreibung der lokalen Entity Schemas.

## StatusEntity

```kotlin
@Entity(tableName = "feed_statuses")
data class StatusEntity(
    @PrimaryKey val id: Int,
    val statusJson: String,
    val type: String // "dashboard" or "global"
)
```

**Tabelle:** `feed_statuses`

| Spalte       | Typ      | Beschreibung                           |
| ------------ | -------- | -------------------------------------- |
| `id`         | Int (PK) | Status-ID von der API                  |
| `statusJson` | String   | Serialisiertes Status-Objekt (Gson)    |
| `type`       | String   | "dashboard" oder "global" für Feed-Typ |

## Aktiver Fahrtcache in DataStore

`trip_tracking_state` ist ein Gson-JSON mit `CachedTripTrackingState`, keine Room-Entity. Das Format bleibt Version 1. Es enthält aktive Status-ID, Check-in, eingegrenzte Haltfolge, Besuchs-/Ansagefortschritt sowie bereits vorhandene Änderungs- und Live-Anzeigemetadaten. Die [SEV-Ergänzung](../module/sev-haltestellen.md) fügt zwei optionale Felder hinzu:

| Feld | Typ | Bedeutung |
| --- | --- | --- |
| `fullStopovers` | List<StopStation>? | Vollständige API-Haltfolge als Richtungskontext, auch nach dem eigenen Ausstieg. Alte Einträge verwenden ersatzweise die vorhandene Haltfolge. |
| `sevMaps` | Map<String, SevMap>? | Öffentliche Karten je Bahnhofsslug mit SEV-Punkten, Hinweisen und Abrufzeit. Fehlendes Feld bedeutet keine SEV-Quelle. |

Das Wiederherstellen vertraut nicht unmittelbar auf eine frühere Auflösung: Quellenalter, Maßnahmendatum und Richtung werden erneut geprüft. Gerätepositionen, Bewegungshistorie und GPS-Zeitprognosen werden nicht in diesen Feldern gespeichert. Details zum Schreiben/Löschen: [PreferencesManager](../konfiguration/preferences-manager.md).

## Verwandte Seiten

- [Datenbank](./datenbank.md)
- [Datenmodell](./datenmodell.md)
- [SEV-Ersatzhaltestellen](../module/sev-haltestellen.md)
