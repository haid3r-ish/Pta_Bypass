package com.nonpta.bridge.data.db.dao

import androidx.room.*
import com.nonpta.bridge.data.db.entity.BlockedNumberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedNumberDao {

    @Query("SELECT * FROM blocked_numbers ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<BlockedNumberEntity>>

    @Query("SELECT COUNT(*) FROM blocked_numbers WHERE number = :number")
    suspend fun isBlocked(number: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: BlockedNumberEntity)

    @Query("DELETE FROM blocked_numbers WHERE number = :number")
    suspend fun delete(number: String)
}
