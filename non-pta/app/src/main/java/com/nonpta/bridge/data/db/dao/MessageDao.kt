package com.nonpta.bridge.data.db.dao

import androidx.room.*
import com.nonpta.bridge.data.db.entity.MessageEntity
import com.nonpta.bridge.model.ConversationSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE threadNumber = :number ORDER BY timestamp ASC")
    fun getThreadFlow(number: String): Flow<List<MessageEntity>>

    @Query("""
        SELECT m.threadNumber, m.body AS latestBody, m.timestamp AS latestTimestamp,
            (SELECT COUNT(*) FROM messages
             WHERE threadNumber = m.threadNumber AND isRead = 0 AND isOutgoing = 0) AS unreadCount
        FROM messages m
        INNER JOIN (
            SELECT threadNumber, MAX(timestamp) AS maxTs
            FROM messages GROUP BY threadNumber
        ) g ON m.threadNumber = g.threadNumber AND m.timestamp = g.maxTs
        ORDER BY m.timestamp DESC
    """)
    fun getConversationsFlow(): Flow<List<ConversationSummary>>

    @Query("""
        SELECT m.threadNumber, m.body AS latestBody, m.timestamp AS latestTimestamp,
            (SELECT COUNT(*) FROM messages
             WHERE threadNumber = m.threadNumber AND isRead = 0 AND isOutgoing = 0) AS unreadCount
        FROM messages m
        INNER JOIN (
            SELECT threadNumber, MAX(timestamp) AS maxTs
            FROM messages GROUP BY threadNumber
        ) g ON m.threadNumber = g.threadNumber AND m.timestamp = g.maxTs
        WHERE m.threadNumber LIKE '%' || :query || '%' OR m.body LIKE '%' || :query || '%'
        ORDER BY m.timestamp DESC
    """)
    fun searchConversationsFlow(query: String): Flow<List<ConversationSummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: MessageEntity)

    @Query("UPDATE messages SET isRead = 1 WHERE threadNumber = :number AND isOutgoing = 0")
    suspend fun markThreadRead(number: String)

    @Query("DELETE FROM messages WHERE threadNumber = :number")
    suspend fun deleteThread(number: String)
}
