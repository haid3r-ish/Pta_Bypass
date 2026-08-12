package com.nonpta.bridge.data.db.dao

import androidx.room.*
import com.nonpta.bridge.data.db.entity.CallLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallLogDao {

    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs WHERE number LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchFlow(query: String): Flow<List<CallLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: CallLogEntity)

    @Query("DELETE FROM call_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM call_logs")
    suspend fun deleteAll()
}
