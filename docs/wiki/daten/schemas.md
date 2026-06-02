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

## Verwandte Seiten

- [Datenbank](./datenbank.md)
- [Datenmodell](./datenmodell.md)
