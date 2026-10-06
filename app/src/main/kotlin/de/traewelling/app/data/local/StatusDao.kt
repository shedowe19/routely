package de.traewelling.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface StatusDao {

    @Query("SELECT * FROM feed_statuses WHERE type = :type ORDER BY position ASC, id DESC")
    suspend fun getStatuses(type: String): List<StatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatuses(statuses: List<StatusEntity>)

    @Query("DELETE FROM feed_statuses WHERE type = :type")
    suspend fun clearStatuses(type: String)

    @Transaction
    suspend fun replaceStatuses(type: String, statuses: List<StatusEntity>) {
        clearStatuses(type)
        insertStatuses(statuses)
    }
}
