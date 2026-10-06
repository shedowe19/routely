package de.traewelling.app.data.local

import androidx.room.Entity

@Entity(tableName = "feed_statuses", primaryKeys = ["id", "type"])
data class StatusEntity(
    val id: Int,
    val statusJson: String,
    val type: String, // feed kind + digest of its server/account credentials
    val position: Int = 0 // preserve the first page's server ordering
)
